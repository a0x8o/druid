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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.Iterables;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.TableDeleteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableScanNode;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Converts delete followed immediately by table scan to a special table-only delete node
 * <p>
 * Turn
 * <pre>
 *     TableCommit - Delete - TableScanNode (no node allowed in between except Exchanges)
 * </pre>
 * into
 * <pre>
 *     TableDelete
 * </pre>
 */
public class TableDeleteOptimizer
        implements PlanOptimizer
{
    private final Metadata metadata;

    public TableDeleteOptimizer(Metadata metadata)
    {
        requireNonNull(metadata, "metadata is null");

        this.metadata = metadata;
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        return SimplePlanRewriter.rewriteWith(new Optimizer(session, metadata, idAllocator), plan, null);
    }

    private static class Optimizer
            extends SimplePlanRewriter<Void>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final Session session;
        private final Metadata metadata;

        private Optimizer(Session session, Metadata metadata, PlanNodeIdAllocator idAllocator)
        {
            this.session = session;
            this.metadata = metadata;
            this.idAllocator = idAllocator;
        }

        @Override
        public PlanNode visitTableFinish(TableFinishNode node, RewriteContext<Void> context)
        {
            Optional<DeleteNode> delete = findNode(node.getSource(), DeleteNode.class);
            if (!delete.isPresent()) {
                return context.defaultRewrite(node);
            }
            Optional<TableScanNode> tableScan = findNode(delete.get().getSource(), TableScanNode.class);
            if (!tableScan.isPresent()) {
                return context.defaultRewrite(node);
            }
            TableScanNode tableScanNode = tableScan.get();
            if (!metadata.supportsMetadataDelete(session, tableScanNode.getTable())) {
                return context.defaultRewrite(node);
            }
            return new TableDeleteNode(
                    idAllocator.getNextId(),
                    tableScanNode.getTable(),
                    Iterables.getOnlyElement(node.getOutputSymbols()));
        }

        private static <T> Optional<T> findNode(PlanNode source, Class<T> clazz)
        {
            while (true) {
                // allow any chain of linear exchanges
                if (source instanceof ExchangeNode) {
                    List<PlanNode> sources = source.getSources();
                    if (sources.size() != 1) {
                        return Optional.empty();
                    }
                    source = sources.get(0);
                }
                else if (clazz.isInstance(source)) {
                    return Optional.of(clazz.cast(source));
                }
                else {
                    return Optional.empty();
                }
            }
        }
    }
}
