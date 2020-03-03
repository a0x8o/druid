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
package io.prestosql.cost;

import io.prestosql.Session;
import io.prestosql.cost.ComposableStatsCalculator.Rule;
import io.prestosql.matching.Pattern;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.plan.SortNode;

import java.util.Optional;

import static io.prestosql.sql.planner.plan.Patterns.sort;

public class SortStatsRule
        implements Rule<SortNode>
{
    private static final Pattern<SortNode> PATTERN = sort();

    @Override
    public Pattern<SortNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(SortNode node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types)
    {
        return Optional.of(sourceStats.getStats(node.getSource()));
    }
}
