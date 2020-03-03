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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.LongLiteral;
import org.testng.annotations.Test;

import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.union;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;

public class TestPushProjectionThroughUnion
        extends BaseRuleTest
{
    @Test
    public void testDoesNotFire()
    {
        tester().assertThat(new PushProjectionThroughUnion())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("x"), new LongLiteral("3")),
                                p.values(p.symbol("a"))))
                .doesNotFire();
    }

    @Test
    public void testTrivialProjection()
    {
        tester().assertThat(new PushProjectionThroughUnion())
                .on(p -> {
                    Symbol left = p.symbol("left");
                    Symbol right = p.symbol("right");
                    Symbol unioned = p.symbol("unioned");
                    Symbol renamed = p.symbol("renamed");
                    return p.project(
                            Assignments.of(renamed, unioned.toSymbolReference()),
                            p.union(
                                    ImmutableListMultimap.<Symbol, Symbol>builder()
                                            .put(unioned, left)
                                            .put(unioned, right)
                                            .build(),
                                    ImmutableList.of(
                                            p.values(left),
                                            p.values(right))));
                })
                .doesNotFire();
    }

    @Test
    public void test()
    {
        tester().assertThat(new PushProjectionThroughUnion())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol cTimes3 = p.symbol("c_times_3");
                    return p.project(
                            Assignments.of(cTimes3, new ArithmeticBinaryExpression(ArithmeticBinaryExpression.Operator.MULTIPLY, c.toSymbolReference(), new LongLiteral("3"))),
                            p.union(
                                    ImmutableListMultimap.<Symbol, Symbol>builder()
                                            .put(c, a)
                                            .put(c, b)
                                            .build(),
                                    ImmutableList.of(
                                            p.values(a),
                                            p.values(b))));
                })
                .matches(
                        union(
                                project(
                                        ImmutableMap.of("a_times_3", expression("a * 3")),
                                        values(ImmutableList.of("a"))),
                                project(
                                        ImmutableMap.of("b_times_3", expression("b * 3")),
                                        values(ImmutableList.of("b"))))
                                // verify that data originally on symbols aliased as x1 and x2 is part of exchange output
                                .withNumberOfOutputColumns(1)
                                .withAlias("a_times_3")
                                .withAlias("b_times_3"));
    }
}
