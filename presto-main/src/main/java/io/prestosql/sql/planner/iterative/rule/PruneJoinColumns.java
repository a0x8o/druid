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

import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Predicates.not;
import static io.prestosql.sql.planner.plan.Patterns.join;
import static io.prestosql.util.MoreLists.filteredCopy;

/**
 * Non-cross joins support output symbol selection, so absorb any project-off into the node.
 */
public class PruneJoinColumns
        extends ProjectOffPushDownRule<JoinNode>
{
    public PruneJoinColumns()
    {
        super(join().matching(not(JoinNode::isCrossJoin)));
    }

    @Override
    protected Optional<PlanNode> pushDownProjectOff(PlanNodeIdAllocator idAllocator, JoinNode joinNode, Set<Symbol> referencedOutputs)
    {
        return Optional.of(
                new JoinNode(
                        joinNode.getId(),
                        joinNode.getType(),
                        joinNode.getLeft(),
                        joinNode.getRight(),
                        joinNode.getCriteria(),
                        filteredCopy(joinNode.getOutputSymbols(), referencedOutputs::contains),
                        joinNode.getFilter(),
                        joinNode.getLeftHashSymbol(),
                        joinNode.getRightHashSymbol(),
                        joinNode.getDistributionType(),
                        joinNode.isSpillable(),
                        joinNode.getDynamicFilters()));
    }
}
