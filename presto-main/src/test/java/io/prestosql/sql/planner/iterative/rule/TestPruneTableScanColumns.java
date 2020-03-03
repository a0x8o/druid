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
import com.google.common.collect.ImmutableMap;
import io.prestosql.connector.CatalogName;
import io.prestosql.metadata.TableHandle;
import io.prestosql.plugin.tpch.TpchColumnHandle;
import io.prestosql.plugin.tpch.TpchTableHandle;
import io.prestosql.plugin.tpch.TpchTransactionHandle;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.assertions.PlanMatchPattern;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.testing.TestingMetadata.TestingColumnHandle;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.plugin.tpch.TpchMetadata.TINY_SCALE_FACTOR;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.strictTableScan;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.expression;

public class TestPruneTableScanColumns
        extends BaseRuleTest
{
    @Test
    public void testNotAllOutputsReferenced()
    {
        tester().assertThat(new PruneTableScanColumns())
                .on(p ->
                {
                    Symbol orderdate = p.symbol("orderdate", DATE);
                    Symbol totalprice = p.symbol("totalprice", DOUBLE);
                    return p.project(
                            Assignments.of(p.symbol("x"), totalprice.toSymbolReference()),
                            p.tableScan(
                                    new TableHandle(
                                            new CatalogName("local"),
                                            new TpchTableHandle("orders", TINY_SCALE_FACTOR),
                                            TpchTransactionHandle.INSTANCE,
                                            Optional.empty()),
                                    ImmutableList.of(orderdate, totalprice),
                                    ImmutableMap.of(
                                            orderdate, new TpchColumnHandle(orderdate.getName(), DATE),
                                            totalprice, new TpchColumnHandle(totalprice.getName(), DOUBLE))));
                })
                .matches(
                        strictProject(
                                ImmutableMap.of("x_", PlanMatchPattern.expression("totalprice_")),
                                strictTableScan("orders", ImmutableMap.of("totalprice_", "totalprice"))));
    }

    @Test
    public void testAllOutputsReferenced()
    {
        tester().assertThat(new PruneTableScanColumns())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("y"), expression("x")),
                                p.tableScan(
                                        ImmutableList.of(p.symbol("x")),
                                        ImmutableMap.of(p.symbol("x"), new TestingColumnHandle("x")))))
                .doesNotFire();
    }
}
