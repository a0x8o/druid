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
package io.prestosql.connector.system;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.InternalNodeManager;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.connector.SystemTable;
import io.prestosql.spi.connector.SystemTable.Distribution;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.predicate.TupleDomain;

import java.util.Set;

import static io.prestosql.metadata.NodeState.ACTIVE;
import static io.prestosql.spi.connector.SystemTable.Distribution.ALL_COORDINATORS;
import static io.prestosql.spi.connector.SystemTable.Distribution.ALL_NODES;
import static io.prestosql.spi.connector.SystemTable.Distribution.SINGLE_COORDINATOR;
import static java.util.Objects.requireNonNull;

public class SystemSplitManager
        implements ConnectorSplitManager
{
    private final InternalNodeManager nodeManager;
    private final SystemTablesProvider tables;

    public SystemSplitManager(InternalNodeManager nodeManager, SystemTablesProvider tables)
    {
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.tables = requireNonNull(tables, "tables is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            SplitSchedulingStrategy splitSchedulingStrategy)
    {
        SystemTableHandle table = (SystemTableHandle) tableHandle;
        TupleDomain<ColumnHandle> constraint = table.getConstraint();

        SystemTable systemTable = tables.getSystemTable(session, table.getSchemaTableName())
                // table might disappear in the meantime
                .orElseThrow(() -> new TableNotFoundException(table.getSchemaTableName()));

        Distribution tableDistributionMode = systemTable.getDistribution();
        if (tableDistributionMode == SINGLE_COORDINATOR) {
            HostAddress address = nodeManager.getCurrentNode().getHostAndPort();
            ConnectorSplit split = new SystemSplit(address, constraint);
            return new FixedSplitSource(ImmutableList.of(split));
        }

        ImmutableList.Builder<ConnectorSplit> splits = ImmutableList.builder();
        ImmutableSet.Builder<InternalNode> nodes = ImmutableSet.builder();
        if (tableDistributionMode == ALL_COORDINATORS) {
            nodes.addAll(nodeManager.getCoordinators());
        }
        else if (tableDistributionMode == ALL_NODES) {
            nodes.addAll(nodeManager.getNodes(ACTIVE));
        }
        Set<InternalNode> nodeSet = nodes.build();
        for (InternalNode node : nodeSet) {
            splits.add(new SystemSplit(node.getHostAndPort(), constraint));
        }
        return new FixedSplitSource(splits.build());
    }
}
