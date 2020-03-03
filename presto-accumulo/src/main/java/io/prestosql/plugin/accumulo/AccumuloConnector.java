/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.accumulo;

import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.log.Logger;
import io.prestosql.plugin.accumulo.conf.AccumuloSessionProperties;
import io.prestosql.plugin.accumulo.conf.AccumuloTableProperties;
import io.prestosql.plugin.accumulo.io.AccumuloPageSinkProvider;
import io.prestosql.plugin.accumulo.io.AccumuloRecordSetProvider;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorPageSinkProvider;
import io.prestosql.spi.connector.ConnectorRecordSetProvider;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.transaction.IsolationLevel;

import javax.inject.Inject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.transaction.IsolationLevel.READ_UNCOMMITTED;
import static io.prestosql.spi.transaction.IsolationLevel.checkConnectorSupports;
import static java.util.Objects.requireNonNull;

/**
 * Presto Connector for Accumulo.
 * Defines several high-level classes for properties, metadata, retrieving splits, providing I/O operations, etc.
 */
public class AccumuloConnector
        implements Connector
{
    private static final Logger LOG = Logger.get(AccumuloConnector.class);

    private final LifeCycleManager lifeCycleManager;
    private final AccumuloMetadataFactory metadataFactory;
    private final AccumuloSplitManager splitManager;
    private final AccumuloRecordSetProvider recordSetProvider;
    private final AccumuloPageSinkProvider pageSinkProvider;
    private final AccumuloSessionProperties sessionProperties;
    private final AccumuloTableProperties tableProperties;
    private final ConcurrentMap<ConnectorTransactionHandle, AccumuloMetadata> transactions = new ConcurrentHashMap<>();

    @Inject
    public AccumuloConnector(
            LifeCycleManager lifeCycleManager,
            AccumuloMetadataFactory metadataFactory,
            AccumuloSplitManager splitManager,
            AccumuloRecordSetProvider recordSetProvider,
            AccumuloPageSinkProvider pageSinkProvider,
            AccumuloSessionProperties sessionProperties,
            AccumuloTableProperties tableProperties)
    {
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");
        this.metadataFactory = requireNonNull(metadataFactory, "metadata is null");
        this.splitManager = requireNonNull(splitManager, "splitManager is null");
        this.recordSetProvider = requireNonNull(recordSetProvider, "recordSetProvider is null");
        this.pageSinkProvider = requireNonNull(pageSinkProvider, "pageSinkProvider is null");
        this.sessionProperties = requireNonNull(sessionProperties, "sessionProperties is null");
        this.tableProperties = requireNonNull(tableProperties, "tableProperties is null");
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorTransactionHandle transactionHandle)
    {
        ConnectorMetadata metadata = transactions.get(transactionHandle);
        checkArgument(metadata != null, "no such transaction: %s", transactionHandle);
        return metadata;
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly)
    {
        checkConnectorSupports(READ_UNCOMMITTED, isolationLevel);
        ConnectorTransactionHandle transaction = new AccumuloTransactionHandle();
        transactions.put(transaction, metadataFactory.create());
        return transaction;
    }

    @Override
    public void commit(ConnectorTransactionHandle transactionHandle)
    {
        checkArgument(transactions.remove(transactionHandle) != null, "no such transaction: %s", transactionHandle);
    }

    @Override
    public void rollback(ConnectorTransactionHandle transactionHandle)
    {
        AccumuloMetadata metadata = transactions.remove(transactionHandle);
        checkArgument(metadata != null, "no such transaction: %s", transactionHandle);
        metadata.rollback();
    }

    @Override
    public ConnectorSplitManager getSplitManager()
    {
        return splitManager;
    }

    @Override
    public ConnectorRecordSetProvider getRecordSetProvider()
    {
        return recordSetProvider;
    }

    @Override
    public ConnectorPageSinkProvider getPageSinkProvider()
    {
        return pageSinkProvider;
    }

    @Override
    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties.getTableProperties();
    }

    @Override
    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties.getSessionProperties();
    }

    @Override
    public final void shutdown()
    {
        lifeCycleManager.stop();
    }
}
