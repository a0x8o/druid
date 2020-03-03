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

import com.google.common.collect.ImmutableMap;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.client.ClientSelectedRole;
import io.prestosql.client.ClientSession;
import io.prestosql.client.Column;
import io.prestosql.client.QueryError;
import io.prestosql.client.QueryStatusInfo;
import io.prestosql.client.StatementClient;
import io.prestosql.metadata.MetadataUtil;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.QualifiedTablePrefix;
import io.prestosql.server.testing.TestingPrestoServer;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.session.ResourceEstimates;
import io.prestosql.spi.type.Type;
import okhttp3.OkHttpClient;
import org.intellij.lang.annotations.Language;

import java.io.Closeable;
import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.prestosql.client.StatementClientFactory.newStatementClient;
import static io.prestosql.spi.session.ResourceEstimates.CPU_TIME;
import static io.prestosql.spi.session.ResourceEstimates.EXECUTION_TIME;
import static io.prestosql.spi.session.ResourceEstimates.PEAK_MEMORY;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static java.util.Objects.requireNonNull;

public abstract class AbstractTestingPrestoClient<T>
        implements Closeable
{
    private final TestingPrestoServer prestoServer;
    private final Session defaultSession;

    private final OkHttpClient httpClient = new OkHttpClient();

    protected AbstractTestingPrestoClient(TestingPrestoServer prestoServer,
            Session defaultSession)
    {
        this.prestoServer = requireNonNull(prestoServer, "prestoServer is null");
        this.defaultSession = requireNonNull(defaultSession, "defaultSession is null");
    }

    @Override
    public void close()
    {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    protected abstract ResultsSession<T> getResultSession(Session session);

    public ResultWithQueryId<T> execute(@Language("SQL") String sql)
    {
        return execute(defaultSession, sql);
    }

    public ResultWithQueryId<T> execute(Session session, @Language("SQL") String sql)
    {
        ResultsSession<T> resultsSession = getResultSession(session);

        ClientSession clientSession = toClientSession(session, prestoServer.getBaseUrl(), new Duration(2, TimeUnit.MINUTES));

        try (StatementClient client = newStatementClient(httpClient, clientSession, sql)) {
            while (client.isRunning()) {
                resultsSession.addResults(client.currentStatusInfo(), client.currentData());
                client.advance();
            }

            checkState(client.isFinished());
            QueryError error = client.finalStatusInfo().getError();

            if (error == null) {
                QueryStatusInfo results = client.finalStatusInfo();
                if (results.getUpdateType() != null) {
                    resultsSession.setUpdateType(results.getUpdateType());
                }
                if (results.getUpdateCount() != null) {
                    resultsSession.setUpdateCount(results.getUpdateCount());
                }

                resultsSession.setWarnings(results.getWarnings());

                T result = resultsSession.build(client.getSetSessionProperties(), client.getResetSessionProperties());
                return new ResultWithQueryId<>(new QueryId(results.getId()), result);
            }

            if (error.getFailureInfo() != null) {
                RuntimeException remoteException = error.getFailureInfo().toException();
                throw new RuntimeException(Optional.ofNullable(remoteException.getMessage()).orElseGet(remoteException::toString), remoteException);
            }
            throw new RuntimeException("Query failed: " + error.getMessage());

            // dump query info to console for debugging (NOTE: not pretty printed)
            // JsonCodec<QueryInfo> queryInfoJsonCodec = createCodecFactory().prettyPrint().jsonCodec(QueryInfo.class);
            // log.info("\n" + queryInfoJsonCodec.toJson(queryInfo));
        }
    }

    private static ClientSession toClientSession(Session session, URI server, Duration clientRequestTimeout)
    {
        ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();
        properties.putAll(session.getSystemProperties());
        for (Entry<String, Map<String, String>> connectorProperties : session.getUnprocessedCatalogProperties().entrySet()) {
            for (Entry<String, String> entry : connectorProperties.getValue().entrySet()) {
                properties.put(connectorProperties.getKey() + "." + entry.getKey(), entry.getValue());
            }
        }

        ImmutableMap.Builder<String, String> resourceEstimates = ImmutableMap.builder();
        ResourceEstimates estimates = session.getResourceEstimates();
        estimates.getExecutionTime().ifPresent(e -> resourceEstimates.put(EXECUTION_TIME, e.toString()));
        estimates.getCpuTime().ifPresent(e -> resourceEstimates.put(CPU_TIME, e.toString()));
        estimates.getPeakMemory().ifPresent(e -> resourceEstimates.put(PEAK_MEMORY, e.toString()));

        return new ClientSession(
                server,
                session.getIdentity().getUser(),
                session.getSource().orElse(null),
                session.getTraceToken(),
                session.getClientTags(),
                session.getClientInfo().orElse(null),
                session.getCatalog().orElse(null),
                session.getSchema().orElse(null),
                session.getPath().toString(),
                ZoneId.of(session.getTimeZoneKey().getId()),
                session.getLocale(),
                resourceEstimates.build(),
                properties.build(),
                session.getPreparedStatements(),
                session.getIdentity().getRoles().entrySet().stream()
                        .collect(toImmutableMap(Entry::getKey, entry ->
                                new ClientSelectedRole(
                                        ClientSelectedRole.Type.valueOf(entry.getValue().getType().toString()),
                                        entry.getValue().getRole()))),
                session.getIdentity().getExtraCredentials(),
                session.getTransactionId().map(Object::toString).orElse(null),
                clientRequestTimeout);
    }

    public List<QualifiedObjectName> listTables(Session session, String catalog, String schema)
    {
        return transaction(prestoServer.getTransactionManager(), prestoServer.getAccessControl())
                .readOnly()
                .execute(session, transactionSession -> {
                    return prestoServer.getMetadata().listTables(transactionSession, new QualifiedTablePrefix(catalog, schema));
                });
    }

    public boolean tableExists(Session session, String table)
    {
        return transaction(prestoServer.getTransactionManager(), prestoServer.getAccessControl())
                .readOnly()
                .execute(session, transactionSession -> {
                    return MetadataUtil.tableExists(prestoServer.getMetadata(), transactionSession, table);
                });
    }

    public Session getDefaultSession()
    {
        return defaultSession;
    }

    public TestingPrestoServer getServer()
    {
        return prestoServer;
    }

    protected List<Type> getTypes(List<Column> columns)
    {
        return columns.stream()
                .map(Column::getType)
                .map(prestoServer.getMetadata()::fromSqlType)
                .collect(toImmutableList());
    }
}
