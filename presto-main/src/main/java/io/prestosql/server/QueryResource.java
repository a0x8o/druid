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
import io.prestosql.dispatcher.DispatchManager;
import io.prestosql.execution.QueryInfo;
import io.prestosql.execution.QueryState;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.QueryId;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import static io.prestosql.connector.system.KillQueryProcedure.createKillQueryException;
import static io.prestosql.connector.system.KillQueryProcedure.createPreemptQueryException;
import static java.util.Objects.requireNonNull;

/**
 * Manage queries scheduled on this node
 */
@Path("/v1/query")
public class QueryResource
{
    private final DispatchManager dispatchManager;

    @Inject
    public QueryResource(DispatchManager dispatchManager)
    {
        this.dispatchManager = requireNonNull(dispatchManager, "dispatchManager is null");
    }

    @GET
    public List<BasicQueryInfo> getAllQueryInfo(@QueryParam("state") String stateFilter)
    {
        QueryState expectedState = stateFilter == null ? null : QueryState.valueOf(stateFilter.toUpperCase(Locale.ENGLISH));
        ImmutableList.Builder<BasicQueryInfo> builder = new ImmutableList.Builder<>();
        for (BasicQueryInfo queryInfo : dispatchManager.getQueries()) {
            if (stateFilter == null || queryInfo.getState() == expectedState) {
                builder.add(queryInfo);
            }
        }
        return builder.build();
    }

    @GET
    @Path("{queryId}")
    public Response getQueryInfo(@PathParam("queryId") QueryId queryId)
    {
        requireNonNull(queryId, "queryId is null");

        Optional<QueryInfo> queryInfo = dispatchManager.getFullQueryInfo(queryId);
        if (queryInfo.isPresent()) {
            return Response.ok(queryInfo.get()).build();
        }
        return Response.status(Status.GONE).build();
    }

    @DELETE
    @Path("{queryId}")
    public void cancelQuery(@PathParam("queryId") QueryId queryId)
    {
        requireNonNull(queryId, "queryId is null");
        dispatchManager.cancelQuery(queryId);
    }

    @PUT
    @Path("{queryId}/killed")
    public Response killQuery(@PathParam("queryId") QueryId queryId, String message)
    {
        return failQuery(queryId, createKillQueryException(message));
    }

    @PUT
    @Path("{queryId}/preempted")
    public Response preemptQuery(@PathParam("queryId") QueryId queryId, String message)
    {
        return failQuery(queryId, createPreemptQueryException(message));
    }

    private Response failQuery(QueryId queryId, PrestoException queryException)
    {
        requireNonNull(queryId, "queryId is null");

        try {
            QueryState state = dispatchManager.getQueryInfo(queryId).getState();

            // check before killing to provide the proper error code (this is racy)
            if (state.isDone()) {
                return Response.status(Status.CONFLICT).build();
            }

            dispatchManager.failQuery(queryId, queryException);

            return Response.status(Status.ACCEPTED).build();
        }
        catch (NoSuchElementException e) {
            return Response.status(Status.GONE).build();
        }
    }
}
