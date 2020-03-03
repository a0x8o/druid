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
package io.prestosql.tests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import io.airlift.discovery.server.testing.TestingDiscoveryServer;
import io.airlift.log.Logger;
import io.airlift.testing.Assertions;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.Session.SessionBuilder;
import io.prestosql.connector.CatalogName;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.execution.QueryManager;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.AllNodes;
import io.prestosql.metadata.Catalog;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.SessionPropertyManager;
import io.prestosql.server.BasicQueryInfo;
import io.prestosql.server.testing.TestingPrestoServer;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.QueryId;
import io.prestosql.split.PageSourceManager;
import io.prestosql.split.SplitManager;
import io.prestosql.sql.parser.SqlParserOptions;
import io.prestosql.sql.planner.NodePartitioningManager;
import io.prestosql.sql.planner.Plan;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.QueryRunner;
import io.prestosql.testing.TestingAccessControlManager;
import io.prestosql.transaction.TransactionManager;
import org.intellij.lang.annotations.Language;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.units.Duration.nanosSince;
import static io.prestosql.testing.TestingSession.TESTING_CATALOG;
import static io.prestosql.testing.TestingSession.createBogusTestingCatalog;
import static io.prestosql.tests.AbstractTestQueries.TEST_CATALOG_PROPERTIES;
import static io.prestosql.tests.AbstractTestQueries.TEST_SYSTEM_PROPERTIES;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class DistributedQueryRunner
        implements QueryRunner
{
    private static final Logger log = Logger.get(DistributedQueryRunner.class);
    private static final String ENVIRONMENT = "testing";
    private static final SqlParserOptions DEFAULT_SQL_PARSER_OPTIONS = new SqlParserOptions();

    private final TestingDiscoveryServer discoveryServer;
    private final TestingPrestoServer coordinator;
    private final List<TestingPrestoServer> servers;

    private final Closer closer = Closer.create();

    private final TestingPrestoClient prestoClient;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Deprecated
    public DistributedQueryRunner(Session defaultSession, int nodeCount)
            throws Exception
    {
        this(defaultSession, nodeCount, ImmutableMap.of());
    }

    @Deprecated
    public DistributedQueryRunner(Session defaultSession, int nodeCount, Map<String, String> extraProperties)
            throws Exception
    {
        this(defaultSession, nodeCount, extraProperties, ImmutableMap.of(), DEFAULT_SQL_PARSER_OPTIONS, ENVIRONMENT, Optional.empty());
    }

    public static Builder builder(Session defaultSession)
    {
        return new Builder(defaultSession);
    }

    private DistributedQueryRunner(
            Session defaultSession,
            int nodeCount,
            Map<String, String> extraProperties,
            Map<String, String> coordinatorProperties,
            SqlParserOptions parserOptions,
            String environment,
            Optional<Path> baseDataDir)
            throws Exception
    {
        requireNonNull(defaultSession, "defaultSession is null");

        try {
            long start = System.nanoTime();
            discoveryServer = new TestingDiscoveryServer(environment);
            closer.register(() -> closeUnchecked(discoveryServer));
            log.info("Created TestingDiscoveryServer in %s", nanosSince(start).convertToMostSuccinctTimeUnit());

            ImmutableList.Builder<TestingPrestoServer> servers = ImmutableList.builder();

            for (int i = 1; i < nodeCount; i++) {
                TestingPrestoServer worker = closer.register(createTestingPrestoServer(discoveryServer.getBaseUrl(), false, extraProperties, parserOptions, environment, baseDataDir));
                servers.add(worker);
            }

            Map<String, String> extraCoordinatorProperties = new HashMap<>();
            extraCoordinatorProperties.putAll(extraProperties);
            extraCoordinatorProperties.putAll(coordinatorProperties);
            coordinator = closer.register(createTestingPrestoServer(discoveryServer.getBaseUrl(), true, extraCoordinatorProperties, parserOptions, environment, baseDataDir));
            servers.add(coordinator);

            this.servers = servers.build();
        }
        catch (Exception e) {
            try {
                throw closer.rethrow(e, Exception.class);
            }
            finally {
                closer.close();
            }
        }

        // copy session using property manager in coordinator
        defaultSession = defaultSession.toSessionRepresentation().toSession(coordinator.getMetadata().getSessionPropertyManager(), defaultSession.getIdentity().getExtraCredentials());
        this.prestoClient = closer.register(new TestingPrestoClient(coordinator, defaultSession));

        long start = System.nanoTime();
        while (!allNodesGloballyVisible()) {
            Assertions.assertLessThan(nanosSince(start), new Duration(10, SECONDS));
            MILLISECONDS.sleep(10);
        }
        log.info("Announced servers in %s", nanosSince(start).convertToMostSuccinctTimeUnit());

        start = System.nanoTime();
        for (TestingPrestoServer server : servers) {
            server.getMetadata().addFunctions(AbstractTestQueries.CUSTOM_FUNCTIONS);
        }
        log.info("Added functions in %s", nanosSince(start).convertToMostSuccinctTimeUnit());

        for (TestingPrestoServer server : servers) {
            // add bogus catalog for testing procedures and session properties
            Catalog bogusTestingCatalog = createBogusTestingCatalog(TESTING_CATALOG);
            server.getCatalogManager().registerCatalog(bogusTestingCatalog);

            SessionPropertyManager sessionPropertyManager = server.getMetadata().getSessionPropertyManager();
            sessionPropertyManager.addSystemSessionProperties(TEST_SYSTEM_PROPERTIES);
            sessionPropertyManager.addConnectorSessionProperties(bogusTestingCatalog.getConnectorCatalogName(), TEST_CATALOG_PROPERTIES);
        }
    }

    private static TestingPrestoServer createTestingPrestoServer(URI discoveryUri, boolean coordinator, Map<String, String> extraProperties, SqlParserOptions parserOptions, String environment, Optional<Path> baseDataDir)
    {
        long start = System.nanoTime();
        ImmutableMap.Builder<String, String> propertiesBuilder = ImmutableMap.<String, String>builder()
                .put("query.client.timeout", "10m")
                .put("exchange.http-client.idle-timeout", "1h")
                .put("task.max-index-memory", "16kB") // causes index joins to fault load
                .put("distributed-index-joins-enabled", "true");
        if (coordinator) {
            propertiesBuilder.put("node-scheduler.include-coordinator", "true");
            propertiesBuilder.put("join-distribution-type", "PARTITIONED");
            propertiesBuilder.put("experimental.iterative-optimizer-enabled", "true");
        }
        HashMap<String, String> properties = new HashMap<>(propertiesBuilder.build());
        properties.putAll(extraProperties);

        TestingPrestoServer server = new TestingPrestoServer(coordinator, properties, environment, discoveryUri, parserOptions, ImmutableList.of(), baseDataDir);

        String nodeRole = coordinator ? "coordinator" : "worker";
        log.info("Created %s TestingPrestoServer in %s: %s", nodeRole, nanosSince(start).convertToMostSuccinctTimeUnit(), server.getBaseUrl());

        return server;
    }

    private boolean allNodesGloballyVisible()
    {
        for (TestingPrestoServer server : servers) {
            AllNodes allNodes = server.refreshNodes();
            if (!allNodes.getInactiveNodes().isEmpty() ||
                    (allNodes.getActiveNodes().size() != servers.size())) {
                return false;
            }
        }
        return true;
    }

    public TestingPrestoClient getClient()
    {
        return prestoClient;
    }

    @Override
    public int getNodeCount()
    {
        return servers.size();
    }

    @Override
    public Session getDefaultSession()
    {
        return prestoClient.getDefaultSession();
    }

    @Override
    public TransactionManager getTransactionManager()
    {
        return coordinator.getTransactionManager();
    }

    @Override
    public Metadata getMetadata()
    {
        return coordinator.getMetadata();
    }

    @Override
    public SplitManager getSplitManager()
    {
        return coordinator.getSplitManager();
    }

    @Override
    public PageSourceManager getPageSourceManager()
    {
        return coordinator.getPageSourceManager();
    }

    @Override
    public NodePartitioningManager getNodePartitioningManager()
    {
        return coordinator.getNodePartitioningManager();
    }

    @Override
    public StatsCalculator getStatsCalculator()
    {
        return coordinator.getStatsCalculator();
    }

    @Override
    public TestingAccessControlManager getAccessControl()
    {
        return coordinator.getAccessControl();
    }

    public TestingPrestoServer getCoordinator()
    {
        return coordinator;
    }

    public List<TestingPrestoServer> getServers()
    {
        return ImmutableList.copyOf(servers);
    }

    @Override
    public void installPlugin(Plugin plugin)
    {
        long start = System.nanoTime();
        for (TestingPrestoServer server : servers) {
            server.installPlugin(plugin);
        }
        log.info("Installed plugin %s in %s", plugin.getClass().getSimpleName(), nanosSince(start).convertToMostSuccinctTimeUnit());
    }

    public void createCatalog(String catalogName, String connectorName)
    {
        createCatalog(catalogName, connectorName, ImmutableMap.of());
    }

    @Override
    public void createCatalog(String catalogName, String connectorName, Map<String, String> properties)
    {
        long start = System.nanoTime();
        Set<CatalogName> catalogNames = new HashSet<>();
        for (TestingPrestoServer server : servers) {
            catalogNames.add(server.createCatalog(catalogName, connectorName, properties));
        }
        CatalogName catalog = getOnlyElement(catalogNames);
        log.info("Created catalog %s (%s) in %s", catalogName, catalog, nanosSince(start));

        // wait for all nodes to announce the new catalog
        start = System.nanoTime();
        while (!isConnectionVisibleToAllNodes(catalog)) {
            Assertions.assertLessThan(nanosSince(start), new Duration(100, SECONDS), "waiting for connector " + catalog + " to be initialized in every node");
            try {
                MILLISECONDS.sleep(10);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        log.info("Announced catalog %s (%s) in %s", catalogName, catalog, nanosSince(start));
    }

    private boolean isConnectionVisibleToAllNodes(CatalogName catalogName)
    {
        for (TestingPrestoServer server : servers) {
            server.refreshNodes();
            Set<InternalNode> activeNodesWithConnector = server.getActiveNodesWithConnector(catalogName);
            if (activeNodesWithConnector.size() != servers.size()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<QualifiedObjectName> listTables(Session session, String catalog, String schema)
    {
        lock.readLock().lock();
        try {
            return prestoClient.listTables(session, catalog, schema);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean tableExists(Session session, String table)
    {
        lock.readLock().lock();
        try {
            return prestoClient.tableExists(session, table);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public MaterializedResult execute(@Language("SQL") String sql)
    {
        lock.readLock().lock();
        try {
            return prestoClient.execute(sql).getResult();
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public MaterializedResult execute(Session session, @Language("SQL") String sql)
    {
        lock.readLock().lock();
        try {
            return prestoClient.execute(session, sql).getResult();
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public ResultWithQueryId<MaterializedResult> executeWithQueryId(Session session, @Language("SQL") String sql)
    {
        lock.readLock().lock();
        try {
            return prestoClient.execute(session, sql);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public MaterializedResultWithPlan executeWithPlan(Session session, String sql, WarningCollector warningCollector)
    {
        ResultWithQueryId<MaterializedResult> resultWithQueryId = executeWithQueryId(session, sql);
        return new MaterializedResultWithPlan(resultWithQueryId.getResult().toTestTypes(), getQueryPlan(resultWithQueryId.getQueryId()));
    }

    @Override
    public Plan createPlan(Session session, String sql, WarningCollector warningCollector)
    {
        QueryId queryId = executeWithQueryId(session, sql).getQueryId();
        Plan queryPlan = getQueryPlan(queryId);
        coordinator.getQueryManager().cancelQuery(queryId);
        return queryPlan;
    }

    public Plan getQueryPlan(QueryId queryId)
    {
        return coordinator.getQueryPlan(queryId);
    }

    @Override
    public Lock getExclusiveLock()
    {
        return lock.writeLock();
    }

    @Override
    public final void close()
    {
        cancelAllQueries();
        try {
            closer.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void cancelAllQueries()
    {
        QueryManager queryManager = coordinator.getQueryManager();
        for (BasicQueryInfo queryInfo : queryManager.getQueries()) {
            if (!queryInfo.getState().isDone()) {
                queryManager.cancelQuery(queryInfo.getQueryId());
            }
        }
    }

    private static void closeUnchecked(AutoCloseable closeable)
    {
        try {
            closeable.close();
        }
        catch (Exception e) {
            throwIfUnchecked(e);
            throw new RuntimeException(e);
        }
    }

    public static class Builder
    {
        private Session defaultSession;
        private int nodeCount = 4;
        private Map<String, String> extraProperties = ImmutableMap.of();
        private Map<String, String> coordinatorProperties = ImmutableMap.of();
        private SqlParserOptions parserOptions = DEFAULT_SQL_PARSER_OPTIONS;
        private String environment = ENVIRONMENT;
        private Optional<Path> baseDataDir = Optional.empty();

        protected Builder(Session defaultSession)
        {
            this.defaultSession = requireNonNull(defaultSession, "defaultSession is null");
        }

        public Builder amendSession(Function<SessionBuilder, SessionBuilder> amendSession)
        {
            SessionBuilder builder = Session.builder(defaultSession);
            this.defaultSession = amendSession.apply(builder).build();
            return this;
        }

        public Builder setNodeCount(int nodeCount)
        {
            this.nodeCount = nodeCount;
            return this;
        }

        public Builder setExtraProperties(Map<String, String> extraProperties)
        {
            this.extraProperties = extraProperties;
            return this;
        }

        /**
         * Sets extra properties being equal to a map containing given key and value.
         * Note, that calling this method OVERWRITES previously set property values.
         * As a result, it should only be used when only one extra property needs to be set.
         */
        public Builder setSingleExtraProperty(String key, String value)
        {
            return setExtraProperties(ImmutableMap.of(key, value));
        }

        public Builder setCoordinatorProperties(Map<String, String> coordinatorProperties)
        {
            this.coordinatorProperties = coordinatorProperties;
            return this;
        }

        /**
         * Sets coordinator properties being equal to a map containing given key and value.
         * Note, that calling this method OVERWRITES previously set property values.
         * As a result, it should only be used when only one coordinator property needs to be set.
         */
        public Builder setSingleCoordinatorProperty(String key, String value)
        {
            return setCoordinatorProperties(ImmutableMap.of(key, value));
        }

        public Builder setParserOptions(SqlParserOptions parserOptions)
        {
            this.parserOptions = parserOptions;
            return this;
        }

        public Builder setEnvironment(String environment)
        {
            this.environment = environment;
            return this;
        }

        public Builder setBaseDataDir(Optional<Path> baseDataDir)
        {
            this.baseDataDir = requireNonNull(baseDataDir, "baseDataDir is null");
            return this;
        }

        public DistributedQueryRunner build()
                throws Exception
        {
            return new DistributedQueryRunner(defaultSession, nodeCount, extraProperties, coordinatorProperties, parserOptions, environment, baseDataDir);
        }
    }
}
