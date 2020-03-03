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
package io.prestosql.tests.sqlserver;

import io.airlift.log.Logger;
import io.prestosql.tempto.AfterTestWithContext;
import io.prestosql.tempto.BeforeTestWithContext;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import java.sql.Date;

import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.prestosql.tests.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.prestosql.tests.TestGroups.SQL_SERVER;
import static io.prestosql.tests.sqlserver.SqlServerDataTypesTableDefinition.SQLSERVER_INSERT;
import static io.prestosql.tests.sqlserver.TestConstants.KEY_SPACE;
import static io.prestosql.tests.utils.QueryExecutors.onPresto;
import static io.prestosql.tests.utils.QueryExecutors.onSqlServer;
import static java.lang.String.format;

public class TestInsert
        extends ProductTest
        implements RequirementsProvider
{
    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return immutableTable(SQLSERVER_INSERT);
    }

    private static final String SQLSERVER = "sqlserver";
    private static final String MASTER = "master";
    private static final String INSERT_TABLE_NAME = format("%s.%s", KEY_SPACE, SQLSERVER_INSERT.getName());

    @BeforeTestWithContext
    @AfterTestWithContext
    public void dropTestTables()
    {
        try {
            onPresto().executeQuery(format("DROP TABLE IF EXISTS %s", INSERT_TABLE_NAME));
        }
        catch (Exception e) {
            Logger.get(getClass()).warn(e, "failed to drop table");
        }
    }

    @Test(groups = {SQL_SERVER, PROFILE_SPECIFIC_TESTS})
    public void testInsertMin()
    {
        String sql = format(
                "INSERT INTO %s.%s values (BIGINT '%s', SMALLINT '%s', INTEGER '%s', DOUBLE '%s', " +
                        "CHAR 'a   ', 'aa', DOUBLE '%s', DATE '%s')",
                SQLSERVER, INSERT_TABLE_NAME, Long.valueOf("-9223372036854775807"), Short.MIN_VALUE, Integer.MIN_VALUE,
                Double.MIN_VALUE, Double.MIN_VALUE, Date.valueOf("1970-01-01"));
        // Min value for BIGINT would be updated to "-9223372036854775808" post https://github.com/prestodb/presto/issues/4571
        onPresto().executeQuery(sql);

        sql = format(
                "SELECT * FROM %s.%s",
                MASTER, INSERT_TABLE_NAME);
        QueryResult queryResult = onSqlServer()
                .executeQuery(sql);

        assertThat(queryResult).contains(
                row(Long.valueOf("-9223372036854775807"), Short.MIN_VALUE, Integer.MIN_VALUE, Double.MIN_VALUE, "a   ", "aa", Double.MIN_VALUE, Date.valueOf("1970-01-01")));
    }

    @Test(groups = {SQL_SERVER, PROFILE_SPECIFIC_TESTS})
    public void testInsertMax()
    {
        String sql = format(
                "INSERT INTO %s.%s values (BIGINT '%s', SMALLINT '%s', INTEGER '%s', DOUBLE '%s', " +
                        "CHAR 'aaaa', 'aaaaaa', DOUBLE '%s', DATE '%s' )",
                SQLSERVER, INSERT_TABLE_NAME, Long.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE,
                Double.MAX_VALUE, Double.valueOf("12345678912.3456756"), Date.valueOf("9999-12-31"));
        onPresto().executeQuery(sql);

        sql = format(
                "SELECT * FROM %s.%s",
                MASTER, INSERT_TABLE_NAME);
        QueryResult queryResult = onSqlServer()
                .executeQuery(sql);

        assertThat(queryResult).contains(
                row(Long.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE, Double.MAX_VALUE, "aaaa", "aaaaaa", Double.valueOf("12345678912.3456756"),
                        Date.valueOf("9999-12-31")));
    }

    @Test(groups = {SQL_SERVER, PROFILE_SPECIFIC_TESTS})
    public void testInsertNull()
    {
        String sql = format(
                "INSERT INTO %s.%s values (null, null, null, null, null, null, null, null)",
                SQLSERVER, INSERT_TABLE_NAME);
        onPresto().executeQuery(sql);

        sql = format(
                "SELECT * FROM %s.%s",
                MASTER, INSERT_TABLE_NAME);
        QueryResult queryResult = onSqlServer()
                .executeQuery(sql);

        assertThat(queryResult).contains(row(null, null, null, null, null, null, null, null));
    }
}
