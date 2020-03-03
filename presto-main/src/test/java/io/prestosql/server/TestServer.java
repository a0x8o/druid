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
package io.prestosql.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.http.client.FullJsonResponseHandler.JsonResponse;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpUriBuilder;
import io.airlift.http.client.Request;
import io.airlift.http.client.StatusResponseHandler;
import io.airlift.http.client.jetty.JettyHttpClient;
import io.airlift.json.JsonCodec;
import io.airlift.testing.Closeables;
import io.prestosql.client.QueryError;
import io.prestosql.client.QueryResults;
import io.prestosql.server.testing.TestingPrestoServer;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.type.TimeZoneNotSupportedException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;

import static io.airlift.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static io.airlift.http.client.JsonResponseHandler.createJsonResponseHandler;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.prestosql.SystemSessionProperties.HASH_PARTITION_COUNT;
import static io.prestosql.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.prestosql.SystemSessionProperties.QUERY_MAX_MEMORY;
import static io.prestosql.client.PrestoHeaders.PRESTO_CATALOG;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLIENT_INFO;
import static io.prestosql.client.PrestoHeaders.PRESTO_PATH;
import static io.prestosql.client.PrestoHeaders.PRESTO_PREPARED_STATEMENT;
import static io.prestosql.client.PrestoHeaders.PRESTO_SCHEMA;
import static io.prestosql.client.PrestoHeaders.PRESTO_SESSION;
import static io.prestosql.client.PrestoHeaders.PRESTO_SOURCE;
import static io.prestosql.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static io.prestosql.client.PrestoHeaders.PRESTO_TIME_ZONE;
import static io.prestosql.client.PrestoHeaders.PRESTO_TRANSACTION_ID;
import static io.prestosql.client.PrestoHeaders.PRESTO_USER;
import static io.prestosql.spi.StandardErrorCode.INCOMPATIBLE_CLIENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.ws.rs.core.Response.Status.OK;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class TestServer
{
    private static final JsonCodec<QueryResults> QUERY_RESULTS_CODEC = jsonCodec(QueryResults.class);
    private TestingPrestoServer server;
    private HttpClient client;

    @BeforeClass
    public void setup()
    {
        server = new TestingPrestoServer();
        client = new JettyHttpClient();
    }

    @SuppressWarnings("deprecation")
    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        Closeables.closeQuietly(server);
        Closeables.closeQuietly(client);
    }

    @Test
    public void testInvalidSessionError()
    {
        String invalidTimeZone = "this_is_an_invalid_time_zone";
        Request request = preparePost().setHeader(PRESTO_USER, "user")
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .setHeader(PRESTO_PATH, "path")
                .setHeader(PRESTO_TIME_ZONE, invalidTimeZone)
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }
        QueryError queryError = queryResults.getError();
        assertNotNull(queryError);

        TimeZoneNotSupportedException expected = new TimeZoneNotSupportedException(invalidTimeZone);
        assertEquals(queryError.getErrorCode(), expected.getErrorCode().getCode());
        assertEquals(queryError.getErrorName(), expected.getErrorCode().getName());
        assertEquals(queryError.getErrorType(), expected.getErrorCode().getType().name());
        assertEquals(queryError.getMessage(), expected.getMessage());
    }

    @Test
    public void testServerStarts()
    {
        StatusResponseHandler.StatusResponse response = client.execute(
                prepareGet().setUri(server.resolve("/v1/query")).build(),
                createStatusResponseHandler());

        assertEquals(response.getStatusCode(), OK.getStatusCode());
    }

    @Test
    public void testQuery()
    {
        // start query
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .setHeader(PRESTO_PATH, "path")
                .setHeader(PRESTO_CLIENT_INFO, "{\"clientVersion\":\"testVersion\"}")
                .addHeader(PRESTO_SESSION, QUERY_MAX_MEMORY + "=1GB")
                .addHeader(PRESTO_SESSION, JOIN_DISTRIBUTION_TYPE + "=partitioned," + HASH_PARTITION_COUNT + " = 43")
                .addHeader(PRESTO_PREPARED_STATEMENT, "foo=select * from bar")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        ImmutableList.Builder<List<Object>> data = ImmutableList.builder();
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));

            if (queryResults.getData() != null) {
                data.addAll(queryResults.getData());
            }
        }
        assertNull(queryResults.getError());

        // get the query info
        BasicQueryInfo queryInfo = server.getQueryManager().getQueryInfo(new QueryId(queryResults.getId()));

        // verify session properties
        assertEquals(queryInfo.getSession().getSystemProperties(), ImmutableMap.builder()
                .put(QUERY_MAX_MEMORY, "1GB")
                .put(JOIN_DISTRIBUTION_TYPE, "partitioned")
                .put(HASH_PARTITION_COUNT, "43")
                .build());

        // verify client info in session
        assertEquals(queryInfo.getSession().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");

        // verify prepared statements
        assertEquals(queryInfo.getSession().getPreparedStatements(), ImmutableMap.builder()
                .put("foo", "select * from bar")
                .build());

        // only the system catalog exists by default
        List<List<Object>> rows = data.build();
        assertEquals(rows, ImmutableList.of(ImmutableList.of("system")));
    }

    @Test
    public void testTransactionSupport()
    {
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("start transaction", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_TRANSACTION_ID, "none")
                .build();

        JsonResponse<QueryResults> queryResults = client.execute(request, createFullJsonResponseHandler(QUERY_RESULTS_CODEC));
        ImmutableList.Builder<List<Object>> data = ImmutableList.builder();
        while (true) {
            if (queryResults.getValue().getData() != null) {
                data.addAll(queryResults.getValue().getData());
            }

            if (queryResults.getValue().getNextUri() == null) {
                break;
            }
            queryResults = client.execute(prepareGet().setUri(queryResults.getValue().getNextUri()).build(), createFullJsonResponseHandler(QUERY_RESULTS_CODEC));
        }
        assertNull(queryResults.getValue().getError());
        assertNotNull(queryResults.getHeader(PRESTO_STARTED_TRANSACTION_ID));
    }

    @Test
    public void testNoTransactionSupport()
    {
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("start transaction", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }

        assertNotNull(queryResults.getError());
        assertEquals(queryResults.getError().getErrorCode(), INCOMPATIBLE_CLIENT.toErrorCode().getCode());
    }

    private URI uriFor(String path)
    {
        return HttpUriBuilder.uriBuilderFrom(server.getBaseUrl()).replacePath(path).build();
    }
}
