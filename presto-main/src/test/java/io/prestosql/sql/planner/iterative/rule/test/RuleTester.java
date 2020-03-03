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
package io.prestosql.sql.planner.iterative.rule.test;

import com.google.common.collect.ImmutableMap;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.metadata.Metadata;
import io.prestosql.plugin.tpch.TpchConnectorFactory;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.Plugin;
import io.prestosql.split.PageSourceManager;
import io.prestosql.split.SplitManager;
import io.prestosql.sql.planner.TypeAnalyzer;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.testing.LocalQueryRunner;
import io.prestosql.transaction.TransactionManager;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static java.util.Collections.emptyList;

public class RuleTester
        implements Closeable
{
    public static final String CATALOG_ID = "local";
    public static final CatalogName CONNECTOR_ID = new CatalogName(CATALOG_ID);

    private final Metadata metadata;
    private final Session session;
    private final LocalQueryRunner queryRunner;
    private final TransactionManager transactionManager;
    private final SplitManager splitManager;
    private final PageSourceManager pageSourceManager;
    private final AccessControl accessControl;
    private final TypeAnalyzer typeAnalyzer;

    public RuleTester()
    {
        this(emptyList());
    }

    public RuleTester(List<Plugin> plugins)
    {
        this(plugins, ImmutableMap.of());
    }

    public RuleTester(List<Plugin> plugins, Map<String, String> sessionProperties)
    {
        this(plugins, sessionProperties, Optional.empty());
    }

    public RuleTester(List<Plugin> plugins, Map<String, String> sessionProperties, Optional<Integer> nodeCountForStats)
    {
        Session.SessionBuilder sessionBuilder = testSessionBuilder()
                .setCatalog(CATALOG_ID)
                .setSchema("tiny")
                .setSystemProperty("task_concurrency", "1"); // these tests don't handle exchanges from local parallel

        for (Map.Entry<String, String> entry : sessionProperties.entrySet()) {
            sessionBuilder.setSystemProperty(entry.getKey(), entry.getValue());
        }

        session = sessionBuilder.build();

        queryRunner = nodeCountForStats
                .map(nodeCount -> LocalQueryRunner.queryRunnerWithFakeNodeCountForStats(session, nodeCount))
                .orElseGet(() -> new LocalQueryRunner(session));
        queryRunner.createCatalog(session.getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.of());
        plugins.stream().forEach(queryRunner::installPlugin);

        this.metadata = queryRunner.getMetadata();
        this.transactionManager = queryRunner.getTransactionManager();
        this.splitManager = queryRunner.getSplitManager();
        this.pageSourceManager = queryRunner.getPageSourceManager();
        this.accessControl = queryRunner.getAccessControl();
        this.typeAnalyzer = new TypeAnalyzer(queryRunner.getSqlParser(), metadata);
    }

    public RuleAssert assertThat(Rule<?> rule)
    {
        return new RuleAssert(metadata, queryRunner.getStatsCalculator(), queryRunner.getEstimatedExchangesCostCalculator(), session, rule, transactionManager, accessControl);
    }

    @Override
    public void close()
    {
        queryRunner.close();
    }

    public Metadata getMetadata()
    {
        return metadata;
    }

    public Session getSession()
    {
        return session;
    }

    public SplitManager getSplitManager()
    {
        return splitManager;
    }

    public PageSourceManager getPageSourceManager()
    {
        return pageSourceManager;
    }

    public TypeAnalyzer getTypeAnalyzer()
    {
        return typeAnalyzer;
    }

    public CatalogName getCurrentConnectorId()
    {
        return queryRunner.inTransaction(transactionSession -> metadata.getCatalogHandle(transactionSession, session.getCatalog().get())).get();
    }
}
