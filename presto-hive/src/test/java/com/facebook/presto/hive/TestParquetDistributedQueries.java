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
package com.facebook.presto.hive;

import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.QueryRunner;
import com.facebook.presto.tests.AbstractTestDistributedQueries;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.hive.HiveQueryRunner.createQueryRunner;
import static com.facebook.presto.sql.tree.ExplainType.Type.LOGICAL;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.tpch.TpchTable.getTables;
import static org.testng.Assert.assertEquals;

public class TestParquetDistributedQueries
        extends AbstractTestDistributedQueries
{
    protected TestParquetDistributedQueries()
    {
        super(TestParquetDistributedQueries::createQueryRunner);
    }

    private static QueryRunner createQueryRunner()
            throws Exception
    {
        Map<String, String> parquetProperties = ImmutableMap.<String, String>builder()
                .put("hive.storage-format", "PARQUET")
                .put("hive.parquet.use-column-names", "true")
                .put("hive.compression-codec", "GZIP")
                .build();
        return HiveQueryRunner.createQueryRunner(getTables(),
                ImmutableMap.of("experimental.pushdown-subfields-enabled", "true"),
                "sql-standard",
                parquetProperties,
                Optional.empty());
    }

    @Test
    public void testSubfieldPruning()
    {
        getQueryRunner().execute("CREATE TABLE test_subfield_pruning AS " +
                "SELECT orderkey, linenumber, shipdate, " +
                "   CAST(ROW(orderkey, linenumber, ROW(day(shipdate), month(shipdate), year(shipdate))) " +
                "       AS ROW(orderkey BIGINT, linenumber INTEGER, shipdate ROW(ship_day TINYINT, ship_month TINYINT, ship_year INTEGER))) AS info " +
                "FROM lineitem");

        try {
            assertQuery("SELECT info.orderkey, info.shipdate.ship_month FROM test_subfield_pruning", "SELECT orderkey, month(shipdate) FROM lineitem");

            assertQuery("SELECT orderkey FROM test_subfield_pruning WHERE info.shipdate.ship_month % 2 = 0", "SELECT orderkey FROM lineitem WHERE month(shipdate) % 2 = 0");
        }
        finally {
            getQueryRunner().execute("DROP TABLE test_subfield_pruning");
        }
    }

    @Override
    protected boolean supportsNotNullColumns()
    {
        return false;
    }

    @Override
    public void testDelete()
    {
        // Hive connector currently does not support row-by-row delete
    }

    @Override
    public void testRenameColumn()
    {
        // Parquet field lookup use column name does not support Rename
    }

    @Test
    public void testExplainOfCreateTableAs()
    {
        String query = "CREATE TABLE copy_orders AS SELECT * FROM orders";
        MaterializedResult result = computeActual("EXPLAIN " + query);
        assertEquals(getOnlyElement(result.getOnlyColumnAsSet()), getExplainPlan(query, LOGICAL));
    }
}
