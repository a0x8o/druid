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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableMap;
import io.prestosql.sql.planner.assertions.BasePlanTest;
import io.prestosql.sql.planner.plan.AggregationNode;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.sql.planner.assertions.PlanMatchPattern.aggregation;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.globalAggregation;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;

public class TestHaving
        extends BasePlanTest
{
    @Test
    public void testImplicitGroupBy()
    {
        assertPlan(
                "SELECT 'a' FROM (VALUES 1, 1, 2) t(a) HAVING true",
                anyTree(
                        aggregation(
                                globalAggregation(),
                                ImmutableMap.of(),
                                ImmutableMap.of(),
                                Optional.empty(),
                                AggregationNode.Step.SINGLE,
                                values())));
    }
}
