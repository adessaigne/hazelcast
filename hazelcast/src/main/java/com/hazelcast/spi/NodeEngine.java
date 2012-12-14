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

package com.hazelcast.spi;

import com.hazelcast.config.Config;
import com.hazelcast.core.Cluster;
import com.hazelcast.instance.GroupProperties;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.transaction.TransactionImpl;

/**
 * @mdogan 8/24/12
 */
public interface NodeEngine {

    InvocationService getInvocationService();

    ExecutionService getExecutionService();

    EventService getEventService();

    Address getThisAddress();

    int getPartitionId(Data key);

    int getPartitionCount();

    PartitionInfo getPartitionInfo(int partitionId);

    Config getConfig();

    GroupProperties getGroupProperties();

    Cluster getCluster();

    ILogger getLogger(String name);

    Data toData(Object object);

    Object toObject(Object object);

    TransactionImpl getTransaction();
}
