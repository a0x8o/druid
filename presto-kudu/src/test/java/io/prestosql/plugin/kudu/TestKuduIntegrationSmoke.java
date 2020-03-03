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
package io.prestosql.plugin.kudu;

import io.prestosql.spi.type.VarcharType;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.QueryRunner;
import io.prestosql.tests.AbstractTestIntegrationSmokeTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.regex.Pattern;

import static io.airlift.tpch.TpchTable.ORDERS;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static org.testng.Assert.assertTrue;

public class TestKuduIntegrationSmoke
        extends AbstractTestIntegrationSmokeTest
{
    public static final String SCHEMA = "tpch";

    private QueryRunner queryRunner;

    public TestKuduIntegrationSmoke()
    {
        super(() -> KuduQueryRunnerFactory.createKuduQueryRunnerTpch(ORDERS));
    }

    @BeforeClass
    public void setUp()
    {
        queryRunner = getQueryRunner();
    }

    /**
     * Overrides original implementation because of usage of 'extra' column.
     */
    @Test
    @Override
    public void testDescribeTable()
    {
        MaterializedResult actualColumns = this.computeActual("DESC orders").toTestTypes();
        MaterializedResult.Builder builder = MaterializedResult.resultBuilder(this.getQueryRunner().getDefaultSession(), VarcharType.VARCHAR, VarcharType.VARCHAR, VarcharType.VARCHAR, VarcharType.VARCHAR);
        for (MaterializedRow row : actualColumns.getMaterializedRows()) {
            builder.row(row.getField(0), row.getField(1), "", "");
        }
        MaterializedResult filteredActual = builder.build();
        builder = MaterializedResult.resultBuilder(this.getQueryRunner().getDefaultSession(), VarcharType.VARCHAR, VarcharType.VARCHAR, VarcharType.VARCHAR, VarcharType.VARCHAR);
        MaterializedResult expectedColumns = builder
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "varchar", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "integer", "", "")
                .row("comment", "varchar", "", "").build();
        assertEquals(filteredActual, expectedColumns, format("%s != %s", filteredActual, expectedColumns));
    }

    @Test
    public void testShowCreateTable()
    {
        queryRunner.execute("CREATE TABLE IF NOT EXISTS test_show_create_table (\n" +
                "id INT WITH (primary_key=true),\n" +
                "user_name VARCHAR\n" +
                ") WITH (\n" +
                " partition_by_hash_columns = ARRAY['id'],\n" +
                " partition_by_hash_buckets = 2," +
                " number_of_replicas = 1\n" +
                ")");

        MaterializedResult result = queryRunner.execute("SHOW CREATE TABLE test_show_create_table");
        String sqlStatement = (String) result.getOnlyValue();
        String tableProperties = sqlStatement.split("\\)\\s*WITH\\s*\\(")[1];
        assertTableProperty(tableProperties, "number_of_replicas", "1");
        assertTableProperty(tableProperties, "partition_by_hash_columns", Pattern.quote("ARRAY['id']"));
        assertTableProperty(tableProperties, "partition_by_hash_buckets", "2");
    }

    private void assertTableProperty(String tableProperties, String key, String regexValue)
    {
        assertTrue(Pattern.compile(key + "\\s*=\\s*" + regexValue + ",?\\s+").matcher(tableProperties).find(),
                "Not found: " + key + " = " + regexValue + " in " + tableProperties);
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        queryRunner.close();
        queryRunner = null;
    }
}
