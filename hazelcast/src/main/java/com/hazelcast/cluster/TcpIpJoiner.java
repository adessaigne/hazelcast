/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cluster;

import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.Node;
import com.hazelcast.config.Config;
import com.hazelcast.config.Interfaces;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Member;
import com.hazelcast.spi.*;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.spi.AbstractOperation;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.util.AddressUtil;
import com.hazelcast.util.AddressUtil.AddressMatcher;
import com.hazelcast.util.AddressUtil.InvalidAddressException;
import com.hazelcast.util.Clock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static com.hazelcast.util.AddressUtil.AddressHolder;

public class TcpIpJoiner extends AbstractJoiner {

    private static final int MAX_PORT_TRIES = 3;

    private volatile boolean claimingMaster = false;

    public TcpIpJoiner(Node node) {
        super(node);
    }

    private void joinViaTargetMember(AtomicBoolean joined, Address targetAddress, long maxJoinMillis) {
        try {
            logger.log(Level.FINEST, "Joining over target member " + targetAddress);
            if (targetAddress == null) {
                throw new RuntimeException("Invalid target address " + targetAddress);
            }
            if (targetAddress.equals(node.getThisAddress()) || isLocalAddress(targetAddress)) {
                node.setAsMaster();
                return;
            }
            long joinStartTime = Clock.currentTimeMillis();
            Connection connection = null;
            while (node.isActive() && !joined.get() && (Clock.currentTimeMillis() - joinStartTime < maxJoinMillis)) {
                connection = node.connectionManager.getOrConnect(targetAddress);
                if (connection == null) {
                    //noinspection BusyWait
                    Thread.sleep(2000L);
                    continue;
                }
                logger.log(Level.FINEST, "Sending joinRequest " + targetAddress);
                node.clusterService.sendJoinRequest(targetAddress, true);
                //noinspection BusyWait
                Thread.sleep(3000L);
            }
        } catch (final Exception e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public static class MasterClaim extends AbstractOperation implements JoinOperation {

        private transient boolean approvedAsMaster = false;

        public void run() {
            final NodeEngineImpl nodeService = (NodeEngineImpl) getNodeEngine();
            Node node = nodeService.getNode();
//            ResponseHandler responseHandler = getResponseHandler();
            Joiner joiner = node.getJoiner();
            final ILogger logger = node.getLogger(getClass().getName());
            if (joiner instanceof TcpIpJoiner) {
                TcpIpJoiner tcpIpJoiner = (TcpIpJoiner) joiner;
                final Address endpoint = getCaller();
                final Address masterAddress = node.getMasterAddress();
                approvedAsMaster = !tcpIpJoiner.claimingMaster && !node.isMaster()
                                   && (masterAddress == null || masterAddress.equals(endpoint));
            } else {
                approvedAsMaster = false;
                logger.log(Level.WARNING, "This node requires MulticastJoin strategy!");
            }
            logger.log(Level.FINEST, "Sending '" + approvedAsMaster + "' for master claim of node: " + getCaller());
//            responseHandler.sendResponse(approvedAsMaster);
        }

        @Override
        public boolean returnsResponse() {
            return true;
        }

        @Override
        public Object getResponse() {
            return approvedAsMaster;
        }
    }

    private void joinViaPossibleMembers(AtomicBoolean joined) {
        try {
            node.getFailedConnections().clear();
            final Collection<Address> colPossibleAddresses = getPossibleAddresses();
            colPossibleAddresses.remove(node.getThisAddress());
            for (final Address possibleAddress : colPossibleAddresses) {
                logger.log(Level.INFO, "Connecting to possible member: " + possibleAddress);
                node.connectionManager.getOrConnect(possibleAddress);
            }
            boolean foundConnection = false;
            int numberOfSeconds = 0;
            final int connectionTimeoutSeconds = config.getNetworkConfig().getJoin().getTcpIpConfig().getConnectionTimeoutSeconds();
            while (!foundConnection && numberOfSeconds < connectionTimeoutSeconds) {
                logger.log(Level.FINEST, "Removing failedConnections: " + node.getFailedConnections());
                colPossibleAddresses.removeAll(node.getFailedConnections());
                if (colPossibleAddresses.size() == 0) {
                    break;
                }
                //noinspection BusyWait
                Thread.sleep(1000L);
                numberOfSeconds++;
                logger.log(Level.FINEST, "We are going to try to connect to each address" + colPossibleAddresses);
                for (Address possibleAddress : colPossibleAddresses) {
                    final Connection conn = node.connectionManager.getOrConnect(possibleAddress);
                    if (conn != null) {
                        foundConnection = true;
                        logger.log(Level.FINEST, "Found and sending join request for " + possibleAddress);
                        node.clusterService.sendJoinRequest(possibleAddress, true);
                    }
                }
            }
            logger.log(Level.FINEST, "FOUND " + foundConnection);
            if (!foundConnection) {
                logger.log(Level.FINEST, "This node will assume master role since no possible member where connected to");
                node.setAsMaster();
            } else {
                if (!node.joined()) {
                    if (connectionTimeoutSeconds - numberOfSeconds > 0) {
                        logger.log(Level.FINEST, "Sleeping for " + (connectionTimeoutSeconds - numberOfSeconds) + " seconds.");
                        Thread.sleep((connectionTimeoutSeconds - numberOfSeconds) * 1000L);
                    }
                    colPossibleAddresses.removeAll(node.getFailedConnections());
                    if (colPossibleAddresses.size() == 0) {
                        logger.log(Level.FINEST, "This node will assume master role since all possible members didn't accept join request");
                        node.setAsMaster();
                    } else {
                        boolean masterCandidate = true;
                        for (Address address : colPossibleAddresses) {
                            if (node.connectionManager.getConnection(address) != null) {
                                if (node.getThisAddress().hashCode() > address.hashCode()) {
                                    masterCandidate = false;
                                }
                            }
                        }
                        if (masterCandidate) {
                            // ask others...
                            claimingMaster = true;
                            Collection<Future<Boolean>> responses = new LinkedList<Future<Boolean>>();
                            for (Address address : colPossibleAddresses) {
                                if (node.getConnectionManager().getConnection(address) != null) {
                                    logger.log(Level.FINEST, "Claiming myself as master node!");
                                    Invocation inv = node.nodeService.getInvocationService().createInvocationBuilder(
                                            ClusterService.SERVICE_NAME, new MasterClaim(), address)
                                            .setTryCount(1).build();
                                    responses.add(inv.invoke());
                                }
                            }
                            final long maxWait = TimeUnit.SECONDS.toMillis(10);
                            long waitTime = 0L;
                            boolean allApprovedAsMaster = true;
                            for (Future<Boolean> response : responses) {
                                if (!allApprovedAsMaster || waitTime > maxWait) {
                                    allApprovedAsMaster = false;
                                    break;
                                }
                                long t = Clock.currentTimeMillis();
                                try {
                                    allApprovedAsMaster &= response.get(1, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    logger.log(Level.FINEST, e.getMessage(), e);
                                    allApprovedAsMaster = false;
                                } finally {
                                    waitTime += (Clock.currentTimeMillis() - t);
                                }
                            }
                            if (allApprovedAsMaster) {
                                logger.log(Level.FINEST, node.getThisAddress() + " Setting myself as master! group " + node.getConfig().getGroupConfig().getName() + " possible addresses " + colPossibleAddresses.size() + "" + colPossibleAddresses);
                                node.setAsMaster();
                                return;
                            } else {
                                lookForMaster(colPossibleAddresses);
                            }
                        } else {
                            lookForMaster(colPossibleAddresses);
                        }
                    }
                }
            }
            colPossibleAddresses.clear();
            node.getFailedConnections().clear();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, t.getMessage(), t);
        }
    }

    private void lookForMaster(Collection<Address> colPossibleAddresses) throws InterruptedException {
        int tryCount = 0;
        claimingMaster = false;
        while (!node.joined() && tryCount++ < 20 && (node.getMasterAddress() == null)) {
            connectAndSendJoinRequest(colPossibleAddresses);
            //noinspection BusyWait
            Thread.sleep(1000L);
        }
        int requestCount = 0;
        colPossibleAddresses.removeAll(node.getFailedConnections());
        if (colPossibleAddresses.size() == 0) {
            node.setAsMaster();
            logger.log(Level.FINEST, node.getThisAddress() + " Setting myself as master! group " + node.getConfig().getGroupConfig().getName() + " no possible addresses without failed connection");
            return;
        }
        logger.log(Level.FINEST, node.getThisAddress() + " joining to master " + node.getMasterAddress() + ", group " + node.getConfig().getGroupConfig().getName());
        while (node.isActive() && !node.joined()) {
            //noinspection BusyWait
            Thread.sleep(1000L);
            final Address master = node.getMasterAddress();
            if (master != null) {
                node.clusterService.sendJoinRequest(master, true);
                if (requestCount++ > node.getGroupProperties().MAX_WAIT_SECONDS_BEFORE_JOIN.getInteger() + 10) {
                    logger.log(Level.WARNING, "Couldn't join to the master : " + master);
                    return;
                }
            } else {
                logger.log(Level.FINEST, node.getThisAddress() + " couldn't find a master! but there was connections available: " + colPossibleAddresses);
                return;
            }
        }
    }

    private Address getRequiredMemberAddress() {
        final TcpIpConfig tcpIpConfig = config.getNetworkConfig().getJoin().getTcpIpConfig();
        final String host = tcpIpConfig.getRequiredMember();
        try {
            final AddressHolder addressHolder = AddressUtil.getAddressHolder(host, config.getNetworkConfig().getPort());
            if (AddressUtil.isIpAddress(addressHolder.address)) {
                return new Address(addressHolder.address, addressHolder.port);
            } else {
                final Interfaces interfaces = config.getNetworkConfig().getInterfaces();
                if (interfaces.isEnabled()) {
                    final InetAddress[] inetAddresses = InetAddress.getAllByName(addressHolder.address);
                    if (inetAddresses.length > 1) {
                        for (InetAddress inetAddress : inetAddresses) {
                            if (AddressUtil.matchAnyInterface(inetAddress.getHostAddress(),
                                    interfaces.getInterfaces())) {
                                return new Address(inetAddress, addressHolder.port);
                            }
                        }
                    } else {
                        final InetAddress inetAddress = inetAddresses[0];
                        if (AddressUtil.matchAnyInterface(inetAddress.getHostAddress(),
                                interfaces.getInterfaces())) {
                            return new Address(addressHolder.address, addressHolder.port);
                        }
                    }
                } else {
                    return new Address(addressHolder.address, addressHolder.port);
                }
            }
        } catch (final Exception e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
        return null;
    }

    public void doJoin(AtomicBoolean joined) {
        if (targetAddress != null) {
            try {
                long maxJoinMergeTargetMillis = node.getGroupProperties().MAX_JOIN_MERGE_TARGET_SECONDS.getInteger() * 1000;
                joinViaTargetMember(joined, targetAddress, maxJoinMergeTargetMillis);
            } finally {
                targetAddress = null;
            }
            if (!joined.get()) {
                joinViaPossibleMembers(joined);
            }
        } else if (config.getNetworkConfig().getJoin().getTcpIpConfig().getRequiredMember() != null) {
            Address requiredMember = getRequiredMemberAddress();
            long maxJoinMillis = node.getGroupProperties().MAX_JOIN_SECONDS.getInteger() * 1000;
            joinViaTargetMember(joined, requiredMember, maxJoinMillis);
        } else {
            joinViaPossibleMembers(joined);
        }
    }

    private Collection<Address> getPossibleAddresses() {
        final Collection<String> possibleMembers = getMembers();
        final Set<Address> possibleAddresses = new HashSet<Address>();
        final NetworkConfig networkConfig = config.getNetworkConfig();
        for (String possibleMember : possibleMembers) {
            try {
                final AddressHolder addressHolder = AddressUtil.getAddressHolder(possibleMember);
                final boolean portIsDefined = addressHolder.port != -1 || !networkConfig.isPortAutoIncrement();
                final int count = portIsDefined ? 1 : MAX_PORT_TRIES;
                final int port = addressHolder.port != -1 ? addressHolder.port : networkConfig.getPort();
                AddressMatcher addressMatcher = null;
                try {
                    addressMatcher = AddressUtil.getAddressMatcher(addressHolder.address);
                } catch (InvalidAddressException ignore) {
                }
                if (addressMatcher != null) {
                    final Collection<String> matchedAddresses;
                    if (addressMatcher.isIPv4()) {
                        matchedAddresses = AddressUtil.getMatchingIpv4Addresses(addressMatcher);
                    } else {
                        // for IPv6 we are not doing wildcard matching
                        matchedAddresses = Collections.singleton(addressHolder.address);
                    }
                    for (String matchedAddress : matchedAddresses) {
                        addPossibleAddresses(possibleAddresses, null, InetAddress.getByName(matchedAddress), port, count);
                    }
                } else {
                    final String host = addressHolder.address;
                    final Interfaces interfaces = networkConfig.getInterfaces();
                    if (interfaces.isEnabled()) {
                        final InetAddress[] inetAddresses = InetAddress.getAllByName(host);
                        if (inetAddresses.length > 1) {
                            for (InetAddress inetAddress : inetAddresses) {
                                if (AddressUtil.matchAnyInterface(inetAddress.getHostAddress(),
                                        interfaces.getInterfaces())) {
                                    addPossibleAddresses(possibleAddresses, null, inetAddress, port, count);
                                }
                            }
                        } else {
                            final InetAddress inetAddress = inetAddresses[0];
                            if (AddressUtil.matchAnyInterface(inetAddress.getHostAddress(),
                                    interfaces.getInterfaces())) {
                                addPossibleAddresses(possibleAddresses, host, null, port, count);
                            }
                        }
                    } else {
                        addPossibleAddresses(possibleAddresses, host, null, port, count);
                    }
                }
            } catch (UnknownHostException e) {
                logger.log(Level.WARNING, e.getMessage(), e);
            }
        }
        possibleAddresses.addAll(networkConfig.getJoin().getTcpIpConfig().getAddresses());
        return possibleAddresses;
    }

    private void addPossibleAddresses(final Set<Address> possibleAddresses,
                                      final String host, final InetAddress inetAddress,
                                      final int port, final int count) throws UnknownHostException {
        for (int i = 0; i < count; i++) {
            final int currentPort = port + i;
            final Address address = host != null ? new Address(host, currentPort) : new Address(inetAddress, currentPort);
            if (!isLocalAddress(address)) {
                possibleAddresses.add(address);
            }
        }
    }

    private boolean isLocalAddress(final Address address) throws UnknownHostException {
        final Address thisAddress = node.getThisAddress();
        final boolean local = thisAddress.getInetSocketAddress().equals(address.getInetSocketAddress());
        logger.log(Level.FINEST, address + " is local? " + local);
        return local;
    }

    protected Collection<String> getMembers() {
        return getConfigurationMembers(config);
    }

    public static Collection<String> getConfigurationMembers(Config config) {
        final TcpIpConfig tcpIpConfig = config.getNetworkConfig().getJoin().getTcpIpConfig();
        final Collection<String> configMembers = tcpIpConfig.getMembers();
        final Set<String> possibleMembers = new HashSet<String>();
        for (String member : configMembers) {
            // split members defined in tcp-ip configuration by comma(,) semi-colon(;) space( ).
            String[] members = member.split("[,; ]");
            Collections.addAll(possibleMembers, members);
        }
        return possibleMembers;
    }

    public void searchForOtherClusters(SplitBrainHandler splitBrainHandler) {
        final Collection<Address> colPossibleAddresses;
        try {
            colPossibleAddresses = getPossibleAddresses();
        } catch (Throwable e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return;
        }
        colPossibleAddresses.remove(node.getThisAddress());
        for (Member member : node.getClusterService().getMembers()) {
            colPossibleAddresses.remove(((MemberImpl) member).getAddress());
        }
        if (colPossibleAddresses.isEmpty()) {
            return;
        }
        for (Address possibleAddress : colPossibleAddresses) {
            logger.log(Level.FINEST, node.getThisAddress() + " is connecting to " + possibleAddress);
            node.connectionManager.getOrConnect(possibleAddress, true);
            try {
                //noinspection BusyWait
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
            final Connection conn = node.connectionManager.getConnection(possibleAddress);
            if (conn != null) {
                final JoinInfo response = node.clusterService.checkJoinInfo(possibleAddress);
                System.out.println("response = " + response);
                if (response != null && shouldMerge(response)) {
                    logger.log(Level.WARNING, node.getThisAddress() + " is merging [tcp/ip] to " + possibleAddress);
                    targetAddress = possibleAddress;
                    sendClusterMergeToOthers(targetAddress);
                    splitBrainHandler.restart();
                    return;
                }
            }
        }
    }
}
