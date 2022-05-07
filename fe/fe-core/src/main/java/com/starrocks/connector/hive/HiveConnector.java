// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.connector.hive;

import com.starrocks.connector.Connector;
import com.starrocks.connector.ConnectorMetadata;

public class HiveConnector implements Connector {
    @Override
    public ConnectorMetadata getMetadata() {
        return new HiveMetadata();
    }
}