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
package io.prestosql.tests.jdbc;

import io.airlift.log.Logger;
import io.prestosql.jdbc.PrestoConnection;
import io.prestosql.jdbc.PrestoResultSet;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.Requires;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.fulfillment.table.hive.tpch.ImmutableTpchTablesRequirements.ImmutableNationTable;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import javax.inject.Inject;
import javax.inject.Named;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.google.common.base.Strings.repeat;
import static io.prestosql.tempto.Requirements.compose;
import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.fulfillment.table.MutableTableRequirement.State.CREATED;
import static io.prestosql.tempto.fulfillment.table.MutableTablesState.mutableTablesState;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.mutableTable;
import static io.prestosql.tempto.fulfillment.table.hive.tpch.TpchTableDefinitions.NATION;
import static io.prestosql.tempto.internal.convention.SqlResultDescriptor.sqlResultDescriptorForResource;
import static io.prestosql.tempto.query.QueryExecutor.defaultQueryExecutor;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.JDBC;
import static io.prestosql.tests.TestGroups.SIMBA_JDBC;
import static io.prestosql.tests.TpchTableResults.PRESTO_NATION_RESULT;
import static io.prestosql.tests.utils.JdbcDriverUtils.getSessionProperty;
import static io.prestosql.tests.utils.JdbcDriverUtils.resetSessionProperty;
import static io.prestosql.tests.utils.JdbcDriverUtils.setSessionProperty;
import static io.prestosql.tests.utils.JdbcDriverUtils.usingPrestoJdbcDriver;
import static io.prestosql.tests.utils.JdbcDriverUtils.usingSimbaJdbcDriver;
import static java.lang.String.format;
import static java.util.Locale.CHINESE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class JdbcTests
        extends ProductTest
{
    private static final Logger LOGGER = Logger.get(JdbcTests.class);
    private static final String TABLE_NAME = "nation_table_name";

    @Inject
    @Named("databases.presto.jdbc_url")
    private String prestoJdbcURL;

    @Inject
    @Named("databases.presto.jdbc_user")
    private String prestoJdbcUser;

    @Inject
    @Named("databases.presto.jdbc_password")
    private String prestoJdbcPassword;

    private static class ImmutableAndMutableNationTable
            implements RequirementsProvider
    {
        public Requirement getRequirements(Configuration configuration)
        {
            return compose(immutableTable(NATION), mutableTable(NATION, TABLE_NAME, CREATED));
        }
    }

    @Test(groups = JDBC)
    @Requires(ImmutableNationTable.class)
    public void shouldExecuteQuery()
            throws SQLException
    {
        try (Statement statement = connection().createStatement()) {
            QueryResult result = queryResult(statement, "select * from hive.default.nation");
            assertThat(result).matches(PRESTO_NATION_RESULT);
        }
    }

    @Test(groups = JDBC)
    @Requires(ImmutableAndMutableNationTable.class)
    public void shouldInsertSelectQuery()
            throws SQLException
    {
        String tableNameInDatabase = mutableTablesState().get(TABLE_NAME).getNameInDatabase();
        assertThat(query("SELECT * FROM " + tableNameInDatabase)).hasNoRows();

        try (Statement statement = connection().createStatement()) {
            assertThat(statement.executeUpdate("insert into " + tableNameInDatabase + " select * from nation"))
                    .isEqualTo(25);
        }

        assertThat(query("SELECT * FROM " + tableNameInDatabase)).matches(PRESTO_NATION_RESULT);
    }

    @Test(groups = JDBC)
    @Requires(ImmutableNationTable.class)
    public void shouldExecuteQueryWithSelectedCatalogAndSchema()
            throws SQLException
    {
        connection().setCatalog("hive");
        connection().setSchema("default");
        try (Statement statement = connection().createStatement()) {
            QueryResult result = queryResult(statement, "select * from nation");
            assertThat(result).matches(PRESTO_NATION_RESULT);
        }
    }

    @Test(groups = JDBC)
    public void shouldSetTimezone()
            throws SQLException
    {
        String timeZoneId = "Indian/Kerguelen";
        if (usingPrestoJdbcDriver(connection())) {
            ((PrestoConnection) connection()).setTimeZoneId(timeZoneId);
            assertConnectionTimezone(connection(), timeZoneId);
        }
        else {
            String prestoJdbcURLTestTimeZone;
            String testTimeZone = "TimeZoneID=" + timeZoneId + ";";
            if (prestoJdbcURL.contains("TimeZoneID=")) {
                prestoJdbcURLTestTimeZone = prestoJdbcURL.replaceFirst("TimeZoneID=[\\w/]*;", testTimeZone);
            }
            else {
                prestoJdbcURLTestTimeZone = prestoJdbcURL + ";" + testTimeZone;
            }
            Connection testConnection = DriverManager.getConnection(prestoJdbcURLTestTimeZone, prestoJdbcUser, prestoJdbcPassword);
            assertConnectionTimezone(testConnection, timeZoneId);
        }
    }

    private void assertConnectionTimezone(Connection connection, String timeZoneId)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            QueryResult result = queryResult(statement, "select current_timezone()");
            assertThat(result).contains(row(timeZoneId));
        }
    }

    @Test(groups = {SIMBA_JDBC})
    public void shouldSetApplicationPrefixAndName()
            throws SQLException
    {
        String applicationName = "starburst";
        String prestoJdbcURLWithAppPrefixAndName;
        String prestoApplicationPrefix = "applicationNamePrefix=appPrefix:;";

        if (prestoJdbcURL.contains("applicationNamePrefix")) {
            prestoJdbcURLWithAppPrefixAndName = prestoJdbcURL.replaceFirst("applicationNamePrefix=[\\w/]*;", prestoApplicationPrefix);
        }
        else {
            prestoJdbcURLWithAppPrefixAndName = prestoJdbcURL + ";" + prestoApplicationPrefix;
        }
        Connection testConnection = DriverManager.getConnection(prestoJdbcURLWithAppPrefixAndName, prestoJdbcUser, prestoJdbcPassword);
        testConnection.setClientInfo("APPLICATIONNAME", applicationName);
        assertConnectionSource(testConnection, "appPrefix:starburst");
    }

    private void assertConnectionSource(Connection connection, String expectedSource)
            throws SQLException
    {
        String queryId = getQueryId(connection);

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source FROM system.runtime.queries WHERE query_id = ?")) {
            statement.setString(1, queryId);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertThat(rs.getString("source")).isEqualTo(expectedSource);
                assertFalse(rs.next());
            }
        }
    }

    private String getQueryId(Connection connection)
            throws SQLException
    {
        String queryId;
        try (Statement statement = connection.createStatement()) {
            QueryResult result = queryResult(statement, "SELECT 123");
            if (result.getJdbcResultSet().isPresent() && result.getJdbcResultSet().get().isWrapperFor(PrestoResultSet.class)) {
                // if PrestoResult is available, just unwrap it from ResultSet and extract query id
                queryId = result.getJdbcResultSet().get().unwrap(PrestoResultSet.class).getQueryId();
            }
            else {
                // if there is no ResultSet (UPDATE statements), try to find it in system.runtime.queries table
                queryId = (String) query(format("select query_id from system.runtime.queries where query = '%s'", "SELECT 123")).row(0).get(0);
            }
        }
        return queryId;
    }

    @Test(groups = JDBC)
    public void shouldSetLocale()
            throws SQLException
    {
        if (usingPrestoJdbcDriver(connection())) {
            ((PrestoConnection) connection()).setLocale(CHINESE);
            try (Statement statement = connection().createStatement()) {
                QueryResult result = queryResult(statement, "SELECT date_format(TIMESTAMP '2001-01-09 09:04', '%M')");
                assertThat(result).contains(row("一月"));
            }
        }
        else {
            LOGGER.warn("shouldSetLocale() only applies to PrestoJdbcDriver");
        }
    }

    @Test(groups = JDBC)
    public void shouldGetSchemas()
            throws SQLException
    {
        QueryResult result = QueryResult.forResultSet(metaData().getSchemas("hive", null));
        assertThat(result).contains(row("default", "hive"));
    }

    @Test(groups = JDBC)
    @Requires(ImmutableNationTable.class)
    public void shouldGetTables()
            throws SQLException
    {
        QueryResult result = QueryResult.forResultSet(metaData().getTables("hive", null, null, null));
        assertThat(result).contains(row("hive", "default", "nation", "TABLE", null, null, null, null, null, null));
    }

    @Test(groups = JDBC)
    @Requires(ImmutableNationTable.class)
    public void shouldGetColumns()
            throws SQLException
    {
        // The JDBC spec is vague on what values getColumns() should return, so accept the values that Facebook or Simba return.

        QueryResult result = QueryResult.forResultSet(metaData().getColumns("hive", "default", "nation", null));
        if (usingPrestoJdbcDriver(connection())) {
            assertThat(result).matches(sqlResultDescriptorForResource("io/prestosql/tests/jdbc/get_nation_columns.result"));
        }
        else if (usingSimbaJdbcDriver(connection())) {
            assertThat(result).matches(sqlResultDescriptorForResource("io/prestosql/tests/jdbc/get_nation_columns_simba.result"));
        }
        else {
            throw new IllegalStateException();
        }
    }

    @Test(groups = JDBC)
    @Requires(ImmutableNationTable.class)
    public void shouldGetTableTypes()
            throws SQLException
    {
        QueryResult result = QueryResult.forResultSet(metaData().getTableTypes());
        assertThat(result).contains(row("TABLE"), row("VIEW"));
    }

    @Test(groups = {JDBC, SIMBA_JDBC})
    public void testSqlEscapeFunctions()
    {
        if (usingSimbaJdbcDriver(connection())) {
            // These functions, which are defined in the ODBC standard, are implemented within
            // the Simba JDBC and ODBC drivers.  The drivers translate them into equivalent Presto syntax.
            // The translated SQL is executed by Presto.  These tests do not make use of edge-case values or null
            // values because those code paths are covered by other (non-Simba specifc) tests.

            assertThat(query("select {fn char(40)}")).containsExactly(row("("));
            assertThat(query("select {fn convert('2016-10-10', SQL_DATE)}")).containsExactly(row(Date.valueOf("2016-10-10")));

            // This translates to: SELECT cast('1234.567' as DECIMAL).
            // When casting to DECIMAL without parameters, Presto rounds to the nearest integer value.
            assertThat(query("select {fn convert('1234.567', SQL_DECIMAL)}")).containsExactly(row(new BigDecimal(1235)));

            assertThat(query("select {fn convert('123456', SQL_INTEGER)}")).containsExactly(row(123456));
            assertThat(query("select {fn convert('123abcd', SQL_VARBINARY)}")).containsExactly(row("123abcd".getBytes()));
            assertThat(query("select {fn dayofmonth(date '2016-10-20')}")).containsExactly(row(20));
            assertThat(query("select {fn dayofweek(date '2016-10-20')}")).containsExactly(row(5));
            assertThat(query("select {fn dayofyear(date '2016-10-20')}")).containsExactly(row(294));
            assertThat(query("select {fn ifnull({fn ifnull(null, null)}, '2')}")).containsExactly(row("2"));
            assertThat(query("select {fn ifnull('abc', '2')}")).containsExactly(row("abc"));
            assertThat(query("select {fn ifnull(null, '2')}")).containsExactly(row("2"));
            assertThat(query("select {fn lcase('ABC def 123')}")).containsExactly(row("abc def 123"));
            assertThat(query("select {fn left('abc def', 2)}")).containsExactly(row("ab"));
            assertThat(query("select {fn locate('d', 'abc def')}")).containsExactly(row(5));
            assertThat(query("select {fn log(5)}")).containsExactly(row(1.60943791243));
            assertThat(query("select {fn right('abc def', 2)}")).containsExactly(row("ef"));
            assertThat(query("select {fn substring('abc def', 2)}")).containsExactly(row("bc def"));
            assertThat(query("select {fn substring('abc def', 2, 2)}")).containsExactly(row("bc"));
            assertThat(query("select {fn timestampadd(SQL_TSI_DAY, 21, date '2001-01-01')}")).containsExactly(row(Date.valueOf("2001-01-22")));
            assertThat(query("select {fn timestampdiff(SQL_TSI_DAY,date '2001-01-01',date '2002-01-01')}")).containsExactly(row(365));
            assertThat(query("select {fn ucase('ABC def 123')}")).containsExactly(row("ABC DEF 123"));
        }
        else {
            LOGGER.warn("testSqlEscapeFunctions() only applies to EnterpriseJdbcDriver");
        }
    }

    @Test(groups = JDBC)
    public void testSessionProperties()
            throws SQLException
    {
        final String joinDistributionType = "join_distribution_type";
        final String defaultValue = new FeaturesConfig().getJoinDistributionType().name();

        assertThat(getSessionProperty(connection(), joinDistributionType)).isEqualTo(defaultValue);
        setSessionProperty(connection(), joinDistributionType, "BROADCAST");
        assertThat(getSessionProperty(connection(), joinDistributionType)).isEqualTo("BROADCAST");
        resetSessionProperty(connection(), joinDistributionType);
        assertThat(getSessionProperty(connection(), joinDistributionType)).isEqualTo(defaultValue);
    }

    /**
     * Same as {@code io.prestosql.jdbc.TestJdbcPreparedStatement#testDeallocate()}. This one is run for EnterpriseJdbcDriver as well.
     */
    @Test(groups = JDBC)
    public void testDeallocate()
            throws Exception
    {
        try (Connection connection = connection()) {
            for (int i = 0; i < 200; i++) {
                try {
                    try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT '" + repeat("a", 300) + "'")) {
                        preparedStatement.executeQuery().close(); // Let's not assume when PREPARE actually happens
                    }
                }
                catch (Exception e) {
                    throw new RuntimeException("Failed at " + i, e);
                }
            }
        }
    }

    private QueryResult queryResult(Statement statement, String query)
            throws SQLException
    {
        return QueryResult.forResultSet(statement.executeQuery(query));
    }

    private DatabaseMetaData metaData()
            throws SQLException
    {
        return connection().getMetaData();
    }

    private Connection connection()
    {
        return defaultQueryExecutor().getConnection();
    }
}
