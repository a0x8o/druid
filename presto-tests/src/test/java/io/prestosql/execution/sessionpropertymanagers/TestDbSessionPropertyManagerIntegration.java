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
package io.prestosql.execution.sessionpropertymanagers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.json.JsonModule;
import io.airlift.testing.mysql.TestingMySqlServer;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.execution.QueryIdGenerator;
import io.prestosql.execution.QueryManagerConfig;
import io.prestosql.metadata.SessionPropertyManager;
import io.prestosql.plugin.session.db.DbSessionPropertyManager;
import io.prestosql.plugin.session.db.DbSessionPropertyManagerConfig;
import io.prestosql.plugin.session.db.DbSpecsProvider;
import io.prestosql.plugin.session.db.SessionPropertiesDao;
import io.prestosql.plugin.session.db.SessionPropertiesDaoProvider;
import io.prestosql.plugin.session.db.TestingDbSpecsProvider;
import io.prestosql.server.SessionPropertyDefaults;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.resourcegroups.SessionPropertyConfigurationManagerContext;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.session.SessionPropertyConfigurationManager;
import io.prestosql.spi.session.SessionPropertyConfigurationManagerFactory;
import io.prestosql.sql.SqlPath;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.tests.DistributedQueryRunner;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.prestosql.testing.TestingSession.DEFAULT_TIME_ZONE_KEY;
import static java.util.Collections.emptyMap;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class TestDbSessionPropertyManagerIntegration
{
    private DistributedQueryRunner queryRunner;

    private static final String EXAMPLE_PROPERTY = SystemSessionProperties.QUERY_MAX_CPU_TIME;
    private static final Duration EXAMPLE_VALUE_DEFAULT = new QueryManagerConfig().getQueryMaxCpuTime();
    private static final Duration EXAMPLE_VALUE_CONFIGURED = new Duration(50000, DAYS);

    TestingMySqlServer testingMySqlServer;
    private SessionPropertiesDao dao;
    private static final String MYSQL_TEST_USER = "testuser";
    private static final String MYSQL_TEST_PASSWORD = "testpassword";
    private static final String MYSQL_TEST_DATABASE = "test_database";

    private static DistributedQueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder().build();
        assertEquals(session.getSystemProperties(), emptyMap());

        Duration sessionValue = session.getSystemProperty(EXAMPLE_PROPERTY, Duration.class);
        assertEquals(EXAMPLE_VALUE_DEFAULT, sessionValue, "sessionValue is not equal to EXAMPLE_VALUE_DEFAULT");

        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).build();
        queryRunner.installPlugin(new TestSessionPropertyConfigurationManagerPlugin());
        return queryRunner;
    }

    @BeforeClass
    public void setup()
            throws Exception
    {
        testingMySqlServer = new TestingMySqlServer(MYSQL_TEST_USER, MYSQL_TEST_PASSWORD, MYSQL_TEST_DATABASE);
        assertFalse(EXAMPLE_VALUE_CONFIGURED.equals(EXAMPLE_VALUE_DEFAULT));
        queryRunner = createQueryRunner();
    }

    @AfterClass(alwaysRun = true)
    public void destroy()
    {
        testingMySqlServer.close();
        queryRunner.close();
    }

    @BeforeMethod
    public void setupTest()
    {
        SessionPropertyDefaults sessionDefaults = queryRunner.getCoordinator().getSessionPropertyDefaults();
        Map<String, String> configs = ImmutableMap.of("session-property-manager.db.url", testingMySqlServer.getJdbcUrl(MYSQL_TEST_DATABASE));
        sessionDefaults.setConfigurationManager("db-test", configs);

        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(testingMySqlServer.getJdbcUrl(MYSQL_TEST_DATABASE));
        dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .onDemand(SessionPropertiesDao.class);
    }

    @Test(description = "Test successful and unsuccessful reloading of SessionMatchSpecs from the database")
    public void testOperation()
    {
        // Configure the session property for users with user regex user1.*
        dao.insertSpecRow(1, "user1.*", null, null, null, 0);
        dao.insertSessionProperty(1, EXAMPLE_PROPERTY, EXAMPLE_VALUE_CONFIGURED.toString());

        // All queries with matching session should have overridden session properties
        assertSessionPropertyValue("user123", EXAMPLE_VALUE_CONFIGURED);

        // Add a spec and simulate a bad database operation to intentionally fail further queries
        dao.insertSpecRow(2, "user3.*", null, null, null, 0);
        dao.insertSessionProperty(2, EXAMPLE_PROPERTY, EXAMPLE_VALUE_CONFIGURED.toString());
        dao.dropSessionPropertiesTable();

        // Reloading should fail now, old values should still be in use.
        assertSessionPropertyValue("user123", EXAMPLE_VALUE_CONFIGURED);
        assertSessionPropertyValue("user345", EXAMPLE_VALUE_DEFAULT);

        // Fix the database by re-constructing the dropped table
        dao.createSessionPropertiesTable();
        dao.insertSessionProperty(1, EXAMPLE_PROPERTY, EXAMPLE_VALUE_CONFIGURED.toString());
        dao.insertSessionProperty(2, EXAMPLE_PROPERTY, EXAMPLE_VALUE_CONFIGURED.toString());

        // Fixing the database should enable successful reloading again.
        assertSessionPropertyValue("user123", EXAMPLE_VALUE_CONFIGURED);
        assertSessionPropertyValue("user312", EXAMPLE_VALUE_CONFIGURED);
    }

    private void assertSessionPropertyValue(String user, Duration expectedValue)
    {
        Session session = testSessionBuilder()
                .setIdentity(Identity.ofUser(user))
                .build();

        MaterializedResult result = queryRunner.execute(session, "SHOW SESSION");
        String actualValueString = (String) result.getMaterializedRows().stream()
                .filter(row -> (row.getField(0).equals(EXAMPLE_PROPERTY)))
                .collect(onlyElement())
                .getField(1);

        assertEquals(Duration.valueOf(actualValueString), expectedValue);
    }

    /**
     * Generates a test {@link Session.SessionBuilder} ensuring that no system properties are set within the builder.
     */
    private static Session.SessionBuilder testSessionBuilder()
    {
        return Session.builder(new SessionPropertyManager())
                .setQueryId(new QueryIdGenerator().createNextQueryId())
                .setIdentity(Identity.ofUser("user"))
                .setSource("test")
                .setCatalog("catalog")
                .setSchema("schema")
                .setPath(new SqlPath(Optional.of("path")))
                .setTimeZoneKey(DEFAULT_TIME_ZONE_KEY)
                .setLocale(ENGLISH)
                .setRemoteUserAddress("address")
                .setUserAgent("agent");
    }

    static class TestSessionPropertyConfigurationManagerPlugin
            implements Plugin
    {
        @Override
        public Iterable<SessionPropertyConfigurationManagerFactory> getSessionPropertyConfigurationManagerFactories()
        {
            return ImmutableList.of(new TestDbSessionPropertyManagerFactory());
        }
    }

    static class TestDbSessionPropertyManagerModule
            implements Module
    {
        @Override
        public void configure(Binder binder)
        {
            configBinder(binder).bindConfig(DbSessionPropertyManagerConfig.class);
            binder.bind(DbSessionPropertyManager.class).in(Scopes.SINGLETON);
            binder.bind(DbSpecsProvider.class).to(TestingDbSpecsProvider.class).in(Scopes.SINGLETON);
            binder.bind(SessionPropertiesDao.class).toProvider(SessionPropertiesDaoProvider.class).in(Scopes.SINGLETON);
            newExporter(binder).export(DbSessionPropertyManager.class).withGeneratedName();
        }
    }

    static class TestDbSessionPropertyManagerFactory
            implements SessionPropertyConfigurationManagerFactory
    {
        @Override
        public String getName()
        {
            return "db-test";
        }

        @Override
        public SessionPropertyConfigurationManager create(Map<String, String> config, SessionPropertyConfigurationManagerContext context)
        {
            try {
                Bootstrap app = new Bootstrap(
                        new JsonModule(),
                        new TestDbSessionPropertyManagerModule());

                Injector injector = app
                        .strictConfig()
                        .doNotInitializeLogging()
                        .setRequiredConfigurationProperties(config)
                        .quiet()
                        .initialize();
                return injector.getInstance(DbSessionPropertyManager.class);
            }
            catch (Exception e) {
                throwIfUnchecked(e);
                throw new RuntimeException(e);
            }
        }
    }
}
