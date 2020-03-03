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
package io.prestosql.sql.planner.assertions;

import io.prestosql.Session;
import io.prestosql.cost.CachingStatsProvider;
import io.prestosql.cost.StatsAndCosts;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.cost.StatsProvider;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.Plan;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.plan.PlanNode;

import static io.prestosql.sql.planner.iterative.Lookup.noLookup;
import static io.prestosql.sql.planner.iterative.Plans.resolveGroupReferences;
import static io.prestosql.sql.planner.planprinter.PlanPrinter.textLogicalPlan;
import static java.lang.String.format;

public final class PlanAssert
{
    private PlanAssert() {}

    public static void assertPlan(Session session, Metadata metadata, StatsCalculator statsCalculator, Plan actual, PlanMatchPattern pattern)
    {
        assertPlan(session, metadata, statsCalculator, actual, noLookup(), pattern);
    }

    public static void assertPlan(Session session, Metadata metadata, StatsCalculator statsCalculator, Plan actual, Lookup lookup, PlanMatchPattern pattern)
    {
        StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, session, actual.getTypes());
        assertPlan(session, metadata, statsProvider, actual, lookup, pattern);
    }

    public static void assertPlan(Session session, Metadata metadata, StatsProvider statsProvider, Plan actual, Lookup lookup, PlanMatchPattern pattern)
    {
        MatchResult matches = actual.getRoot().accept(new PlanMatchingVisitor(session, metadata, statsProvider, lookup), pattern);
        if (!matches.isMatch()) {
            String formattedPlan = textLogicalPlan(actual.getRoot(), actual.getTypes(), metadata, StatsAndCosts.empty(), session, 0, false);
            PlanNode resolvedPlan = resolveGroupReferences(actual.getRoot(), lookup);
            String resolvedFormattedPlan = textLogicalPlan(resolvedPlan, actual.getTypes(), metadata, StatsAndCosts.empty(), session, 0, false);
            throw new AssertionError(format(
                    "Plan does not match, expected [\n\n%s\n] but found [\n\n%s\n] which resolves to [\n\n%s\n]",
                    pattern,
                    formattedPlan,
                    resolvedFormattedPlan));
        }
    }
}
