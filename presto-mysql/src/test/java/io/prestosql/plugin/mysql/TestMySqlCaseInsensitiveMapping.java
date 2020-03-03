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
package io.prestosql.plugin.mysql;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.tpch.TpchTable;
import io.prestosql.testing.QueryRunner;
import io.prestosql.tests.AbstractTestQueryFramework;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static org.assertj.core.api.Assertions.assertThat;

@Test(singleThreaded = true)
public class TestMySqlCaseInsensitiveMapping
        extends AbstractTestQueryFramework
{
    private final DockerizedMySqlServer mySqlServer;

    public TestMySqlCaseInsensitiveMapping()
    {
        // Using dockerized MySQL server instead of TestingMySqlServer because TestingMySqlServer depends on host's file system.
        // Because host file system may be case insensitive, it would not be possible to create schemas/tables with names differing in case only.
        this(new DockerizedMySqlServer());
    }

    public TestMySqlCaseInsensitiveMapping(DockerizedMySqlServer mySqlServer)
    {
        super(() -> createMySqlQueryRunner(
                mySqlServer,
                ImmutableMap.of("case-insensitive-name-matching", "true"),
                ImmutableList.of()));
        this.mySqlServer = mySqlServer;
    }

    private static QueryRunner createMySqlQueryRunner(DockerizedMySqlServer mySqlServer, Map<String, String> connectorProperties, Iterable<TpchTable<?>> tables)
            throws Exception
    {
        try {
            mySqlServer.init();
            return MySqlQueryRunner.createMySqlQueryRunner(mySqlServer.getJdbcUrl(), connectorProperties, tables);
        }
        catch (Throwable e) {
            closeAllSuppress(e, mySqlServer);
            throw e;
        }
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        mySqlServer.close();
    }

    @Test
    public void testNonLowerCaseSchemaName()
            throws Exception
    {
        try (AutoCloseable ignore1 = withSchema("NonLowerCaseSchema");
                AutoCloseable ignore2 = withTable("NonLowerCaseSchema.lower_case_name", "(c varchar(5))");
                AutoCloseable ignore3 = withTable("NonLowerCaseSchema.Mixed_Case_Name", "(c varchar(5))");
                AutoCloseable ignore4 = withTable("NonLowerCaseSchema.UPPER_CASE_NAME", "(c varchar(5))")) {
            assertThat(computeActual("SHOW SCHEMAS").getOnlyColumn()).contains("nonlowercaseschema");
            assertQuery("SHOW SCHEMAS LIKE 'nonlowerc%'", "VALUES 'nonlowercaseschema'");
            assertQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE '%nonlowercaseschema'", "VALUES 'nonlowercaseschema'");
            assertQuery("SHOW TABLES FROM nonlowercaseschema", "VALUES 'lower_case_name', 'mixed_case_name', 'upper_case_name'");
            assertQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'nonlowercaseschema'", "VALUES 'lower_case_name', 'mixed_case_name', 'upper_case_name'");
            assertQueryReturnsEmptyResult("SELECT * FROM nonlowercaseschema.lower_case_name");
        }
    }

    @Test
    public void testNonLowerCaseTableName()
            throws Exception
    {
        try (AutoCloseable ignore1 = withSchema("SomeSchema");
                AutoCloseable ignore2 = withTable(
                        "SomeSchema.NonLowerCaseTable", "(lower_case_name varchar(10), Mixed_Case_Name varchar(10), UPPER_CASE_NAME varchar(10))")) {
            execute("INSERT INTO SomeSchema.NonLowerCaseTable VALUES ('a', 'b', 'c')");

            assertQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'someschema' AND table_name = 'nonlowercasetable'",
                    "VALUES 'lower_case_name', 'mixed_case_name', 'upper_case_name'");
            assertQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'nonlowercasetable'",
                    "VALUES 'lower_case_name', 'mixed_case_name', 'upper_case_name'");
            assertEquals(
                    computeActual("SHOW COLUMNS FROM someschema.nonlowercasetable").getMaterializedRows().stream()
                            .map(row -> row.getField(0))
                            .collect(toImmutableSet()),
                    ImmutableSet.of("lower_case_name", "mixed_case_name", "upper_case_name"));

            // Note: until https://github.com/prestodb/presto/issues/2863 is resolved, this is *the* way to access the tables.

            assertQuery("SELECT lower_case_name FROM someschema.nonlowercasetable", "VALUES 'a'");
            assertQuery("SELECT mixed_case_name FROM someschema.nonlowercasetable", "VALUES 'b'");
            assertQuery("SELECT upper_case_name FROM someschema.nonlowercasetable", "VALUES 'c'");
            assertQuery("SELECT upper_case_name FROM SomeSchema.NonLowerCaseTable", "VALUES 'c'");
            assertQuery("SELECT upper_case_name FROM \"SomeSchema\".\"NonLowerCaseTable\"", "VALUES 'c'");

            assertUpdate("INSERT INTO someschema.nonlowercasetable (lower_case_name) VALUES ('lower')", 1);
            assertUpdate("INSERT INTO someschema.nonlowercasetable (mixed_case_name) VALUES ('mixed')", 1);
            assertUpdate("INSERT INTO someschema.nonlowercasetable (upper_case_name) VALUES ('upper')", 1);
            assertQuery(
                    "SELECT * FROM someschema.nonlowercasetable",
                    "VALUES ('a', 'b', 'c')," +
                            "('lower', NULL, NULL)," +
                            "(NULL, 'mixed', NULL)," +
                            "(NULL, NULL, 'upper')");
        }
    }

    @Test
    public void testSchemaNameClash()
            throws Exception
    {
        String[] nameVariants = {"casesensitivename", "CaseSensitiveName", "CASESENSITIVENAME"};
        assertThat(Stream.of(nameVariants)
                .map(name -> name.toLowerCase(ENGLISH))
                .collect(toImmutableSet()))
                .hasSize(1);

        for (int i = 0; i < nameVariants.length; i++) {
            for (int j = i + 1; j < nameVariants.length; j++) {
                String schemaName = nameVariants[i];
                String otherSchemaName = nameVariants[j];
                try (AutoCloseable ignore1 = withSchema(schemaName);
                        AutoCloseable ignore2 = withSchema(otherSchemaName);
                        AutoCloseable ignore3 = withTable(schemaName + ".some_table_name", "(c varchar(5))")) {
                    assertThat(computeActual("SHOW SCHEMAS").getOnlyColumn()).contains("casesensitivename");
                    assertThat(computeActual("SHOW SCHEMAS").getOnlyColumn().filter("casesensitivename"::equals)).hasSize(1); // TODO change io.prestosql.plugin.jdbc.JdbcClient.getSchemaNames to return a List
                    assertQueryFails("SHOW TABLES FROM casesensitivename", "Failed to find remote schema name:.*Multiple entries with same key.*");
                    assertQueryFails("SELECT * FROM casesensitivename.some_table_name", "Failed to find remote schema name:.*Multiple entries with same key.*");
                }
            }
        }
    }

    @Test
    public void testTableNameClash()
            throws Exception
    {
        String[] nameVariants = {"casesensitivename", "CaseSensitiveName", "CASESENSITIVENAME"};
        assertThat(Stream.of(nameVariants)
                .map(name -> name.toLowerCase(ENGLISH))
                .collect(toImmutableSet()))
                .hasSize(1);

        for (int i = 0; i < nameVariants.length; i++) {
            for (int j = i + 1; j < nameVariants.length; j++) {
                try (AutoCloseable ignore1 = withTable("tpch." + nameVariants[i], "(c varchar(5))");
                        AutoCloseable ignore2 = withTable("tpch." + nameVariants[j], "(d varchar(5))")) {
                    assertThat(computeActual("SHOW TABLES").getOnlyColumn()).contains("casesensitivename");
                    assertThat(computeActual("SHOW TABLES").getOnlyColumn().filter("casesensitivename"::equals)).hasSize(1); // TODO, should be 2
                    assertQueryFails("SHOW COLUMNS FROM casesensitivename", "Failed to find remote table name:.*Multiple entries with same key.*");
                    assertQueryFails("SELECT * FROM casesensitivename", "Failed to find remote table name:.*Multiple entries with same key.*");
                }
            }
        }
    }

    private AutoCloseable withSchema(String schemaName)
    {
        execute(format("CREATE SCHEMA `%s`", schemaName));
        return () -> execute(format("DROP SCHEMA `%s`", schemaName));
    }

    private AutoCloseable withTable(String tableName, String tableDefinition)
    {
        execute(format("CREATE TABLE %s %s", tableName, tableDefinition));
        return () -> execute(format("DROP TABLE %s", tableName));
    }

    private void execute(String sql)
    {
        try (Connection connection = DriverManager.getConnection(mySqlServer.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to execute statement: " + sql, e);
        }
    }
}
