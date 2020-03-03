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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.Maps;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.IndexSourceNode;
import io.prestosql.sql.planner.plan.PlanNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.sql.planner.plan.Patterns.indexSource;

public class PruneIndexSourceColumns
        extends ProjectOffPushDownRule<IndexSourceNode>
{
    public PruneIndexSourceColumns()
    {
        super(indexSource());
    }

    @Override
    protected Optional<PlanNode> pushDownProjectOff(PlanNodeIdAllocator idAllocator, IndexSourceNode indexSourceNode, Set<Symbol> referencedOutputs)
    {
        Set<Symbol> prunedLookupSymbols = indexSourceNode.getLookupSymbols().stream()
                .filter(referencedOutputs::contains)
                .collect(toImmutableSet());

        Map<Symbol, ColumnHandle> prunedAssignments = Maps.filterEntries(
                indexSourceNode.getAssignments(),
                entry -> referencedOutputs.contains(entry.getKey()) ||
                        tupleDomainReferencesColumnHandle(indexSourceNode.getCurrentConstraint(), entry.getValue()));

        List<Symbol> prunedOutputList =
                indexSourceNode.getOutputSymbols().stream()
                        .filter(referencedOutputs::contains)
                        .collect(toImmutableList());

        return Optional.of(
                new IndexSourceNode(
                        indexSourceNode.getId(),
                        indexSourceNode.getIndexHandle(),
                        indexSourceNode.getTableHandle(),
                        prunedLookupSymbols,
                        prunedOutputList,
                        prunedAssignments,
                        indexSourceNode.getCurrentConstraint()));
    }

    private static boolean tupleDomainReferencesColumnHandle(
            TupleDomain<ColumnHandle> tupleDomain,
            ColumnHandle columnHandle)
    {
        return tupleDomain.getDomains()
                .map(domains -> domains.containsKey(columnHandle))
                .orElse(false);
    }
}
