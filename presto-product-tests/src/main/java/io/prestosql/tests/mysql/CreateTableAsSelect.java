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
package io.prestosql.tests.mysql;

import io.airlift.log.Logger;
import io.prestosql.tempto.AfterTestWithContext;
import io.prestosql.tempto.BeforeTestWithContext;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requires;
import io.prestosql.tempto.fulfillment.table.hive.tpch.ImmutableTpchTablesRequirements.ImmutableNationTable;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.JDBC;
import static io.prestosql.tests.TestGroups.MYSQL;
import static io.prestosql.tests.utils.QueryExecutors.onMySql;
import static java.lang.String.format;

public class CreateTableAsSelect
        extends ProductTest
{
    private static final String TABLE_NAME = "test.nation_tmp";

    @BeforeTestWithContext
    @AfterTestWithContext
    public void dropTestTable()
    {
        try {
            onMySql().executeQuery(format("DROP TABLE IF EXISTS %s", TABLE_NAME));
        }
        catch (Exception e) {
            Logger.get(getClass()).warn(e, "failed to drop table");
        }
    }

    @Requires(ImmutableNationTable.class)
    @Test(groups = {JDBC, MYSQL})
    public void testCreateTableAsSelect()
    {
        QueryResult queryResult = query(format("CREATE TABLE mysql.%s AS SELECT * FROM nation", TABLE_NAME));
        assertThat(queryResult).containsOnly(row(25));
    }
}
