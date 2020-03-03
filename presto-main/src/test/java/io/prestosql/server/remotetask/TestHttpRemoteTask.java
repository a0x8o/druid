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
package io.prestosql.server.remotetask;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.http.client.testing.TestingHttpClient;
import io.airlift.jaxrs.JsonMapper;
import io.airlift.jaxrs.testing.JaxrsTestingHttpProcessor;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonModule;
import io.airlift.units.Duration;
import io.prestosql.client.NodeVersion;
import io.prestosql.connector.CatalogName;
import io.prestosql.execution.Lifespan;
import io.prestosql.execution.NodeTaskMap;
import io.prestosql.execution.QueryManagerConfig;
import io.prestosql.execution.RemoteTask;
import io.prestosql.execution.TaskId;
import io.prestosql.execution.TaskInfo;
import io.prestosql.execution.TaskManagerConfig;
import io.prestosql.execution.TaskSource;
import io.prestosql.execution.TaskState;
import io.prestosql.execution.TaskStatus;
import io.prestosql.execution.TaskTestUtils;
import io.prestosql.execution.TestSqlTaskManager;
import io.prestosql.execution.buffer.OutputBuffers;
import io.prestosql.metadata.HandleJsonModule;
import io.prestosql.metadata.HandleResolver;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Split;
import io.prestosql.server.HttpRemoteTaskFactory;
import io.prestosql.server.TaskUpdateRequest;
import io.prestosql.spi.ErrorCode;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.testing.TestingHandleResolver;
import io.prestosql.testing.TestingSplit;
import io.prestosql.type.TypeDeserializer;
import org.testng.annotations.Test;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.json.JsonBinder.jsonBinder;
import static io.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.client.PrestoHeaders.PRESTO_CURRENT_STATE;
import static io.prestosql.client.PrestoHeaders.PRESTO_MAX_WAIT;
import static io.prestosql.execution.TaskTestUtils.TABLE_SCAN_NODE_ID;
import static io.prestosql.execution.buffer.OutputBuffers.createInitialEmptyOutputBuffers;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static io.prestosql.spi.StandardErrorCode.REMOTE_TASK_MISMATCH;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertTrue;

public class TestHttpRemoteTask
{
    // This 30 sec per-test timeout should never be reached because the test should fail and do proper cleanup after 20 sec.
    private static final Duration POLL_TIMEOUT = new Duration(100, MILLISECONDS);
    private static final Duration IDLE_TIMEOUT = new Duration(3, SECONDS);
    private static final Duration FAIL_TIMEOUT = new Duration(20, SECONDS);
    private static final TaskManagerConfig TASK_MANAGER_CONFIG = new TaskManagerConfig()
            // Shorten status refresh wait and info update interval so that we can have a shorter test timeout
            .setStatusRefreshMaxWait(new Duration(IDLE_TIMEOUT.roundTo(MILLISECONDS) / 100, MILLISECONDS))
            .setInfoUpdateInterval(new Duration(IDLE_TIMEOUT.roundTo(MILLISECONDS) / 10, MILLISECONDS));

    private static final boolean TRACE_HTTP = false;

    @Test(timeOut = 30000)
    public void testRemoteTaskMismatch()
            throws Exception
    {
        runTest(FailureScenario.TASK_MISMATCH);
    }

    @Test(timeOut = 30000)
    public void testRejectedExecutionWhenVersionIsHigh()
            throws Exception
    {
        runTest(FailureScenario.TASK_MISMATCH_WHEN_VERSION_IS_HIGH);
    }

    @Test(timeOut = 30000)
    public void testRejectedExecution()
            throws Exception
    {
        runTest(FailureScenario.REJECTED_EXECUTION);
    }

    @Test(timeOut = 30000)
    public void testRegular()
            throws Exception
    {
        AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        TestingTaskResource testingTaskResource = new TestingTaskResource(lastActivityNanos, FailureScenario.NO_FAILURE);

        HttpRemoteTaskFactory httpRemoteTaskFactory = createHttpRemoteTaskFactory(testingTaskResource);

        RemoteTask remoteTask = createRemoteTask(httpRemoteTaskFactory);

        testingTaskResource.setInitialTaskInfo(remoteTask.getTaskInfo());
        remoteTask.start();

        Lifespan lifespan = Lifespan.driverGroup(3);
        remoteTask.addSplits(ImmutableMultimap.of(TABLE_SCAN_NODE_ID, new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), lifespan)));
        poll(() -> testingTaskResource.getTaskSource(TABLE_SCAN_NODE_ID) != null);
        poll(() -> testingTaskResource.getTaskSource(TABLE_SCAN_NODE_ID).getSplits().size() == 1);

        remoteTask.noMoreSplits(TABLE_SCAN_NODE_ID, lifespan);
        poll(() -> testingTaskResource.getTaskSource(TABLE_SCAN_NODE_ID).getNoMoreSplitsForLifespan().size() == 1);

        remoteTask.noMoreSplits(TABLE_SCAN_NODE_ID);
        poll(() -> testingTaskResource.getTaskSource(TABLE_SCAN_NODE_ID).isNoMoreSplits());

        remoteTask.cancel();
        poll(() -> remoteTask.getTaskStatus().getState().isDone());
        poll(() -> remoteTask.getTaskInfo().getTaskStatus().getState().isDone());

        httpRemoteTaskFactory.stop();
    }

    private void runTest(FailureScenario failureScenario)
            throws Exception
    {
        AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        TestingTaskResource testingTaskResource = new TestingTaskResource(lastActivityNanos, failureScenario);

        HttpRemoteTaskFactory httpRemoteTaskFactory = createHttpRemoteTaskFactory(testingTaskResource);
        RemoteTask remoteTask = createRemoteTask(httpRemoteTaskFactory);

        testingTaskResource.setInitialTaskInfo(remoteTask.getTaskInfo());
        remoteTask.start();

        waitUntilIdle(lastActivityNanos);

        httpRemoteTaskFactory.stop();
        assertTrue(remoteTask.getTaskStatus().getState().isDone(), format("TaskStatus is not in a done state: %s", remoteTask.getTaskStatus()));

        ErrorCode actualErrorCode = getOnlyElement(remoteTask.getTaskStatus().getFailures()).getErrorCode();
        switch (failureScenario) {
            case TASK_MISMATCH:
            case TASK_MISMATCH_WHEN_VERSION_IS_HIGH:
                assertTrue(remoteTask.getTaskInfo().getTaskStatus().getState().isDone(), format("TaskInfo is not in a done state: %s", remoteTask.getTaskInfo()));
                assertEquals(actualErrorCode, REMOTE_TASK_MISMATCH.toErrorCode());
                break;
            case REJECTED_EXECUTION:
                // for a rejection to occur, the http client must be shutdown, which means we will not be able to ge the final task info
                assertEquals(actualErrorCode, REMOTE_TASK_ERROR.toErrorCode());
                break;
            default:
                throw new UnsupportedOperationException();
        }
    }

    private RemoteTask createRemoteTask(HttpRemoteTaskFactory httpRemoteTaskFactory)
    {
        return httpRemoteTaskFactory.createRemoteTask(
                TEST_SESSION,
                new TaskId("test", 1, 2),
                new InternalNode("node-id", URI.create("http://fake.invalid/"), new NodeVersion("version"), false),
                TaskTestUtils.PLAN_FRAGMENT,
                ImmutableMultimap.of(),
                OptionalInt.empty(),
                createInitialEmptyOutputBuffers(OutputBuffers.BufferType.BROADCAST),
                new NodeTaskMap.PartitionedSplitCountTracker(i -> {}),
                true);
    }

    private static HttpRemoteTaskFactory createHttpRemoteTaskFactory(TestingTaskResource testingTaskResource)
    {
        Bootstrap app = new Bootstrap(
                new JsonModule(),
                new HandleJsonModule(),
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        binder.bind(JsonMapper.class);
                        binder.bind(Metadata.class).toInstance(createTestMetadataManager());
                        jsonBinder(binder).addDeserializerBinding(Type.class).to(TypeDeserializer.class);
                        jsonCodecBinder(binder).bindJsonCodec(TaskStatus.class);
                        jsonCodecBinder(binder).bindJsonCodec(TaskInfo.class);
                        jsonCodecBinder(binder).bindJsonCodec(TaskUpdateRequest.class);
                    }

                    @Provides
                    private HttpRemoteTaskFactory createHttpRemoteTaskFactory(
                            JsonMapper jsonMapper,
                            JsonCodec<TaskStatus> taskStatusCodec,
                            JsonCodec<TaskInfo> taskInfoCodec,
                            JsonCodec<TaskUpdateRequest> taskUpdateRequestCodec)
                    {
                        JaxrsTestingHttpProcessor jaxrsTestingHttpProcessor = new JaxrsTestingHttpProcessor(URI.create("http://fake.invalid/"), testingTaskResource, jsonMapper);
                        TestingHttpClient testingHttpClient = new TestingHttpClient(jaxrsTestingHttpProcessor.setTrace(TRACE_HTTP));
                        testingTaskResource.setHttpClient(testingHttpClient);
                        return new HttpRemoteTaskFactory(
                                new QueryManagerConfig(),
                                TASK_MANAGER_CONFIG,
                                testingHttpClient,
                                new TestSqlTaskManager.MockLocationFactory(),
                                taskStatusCodec,
                                taskInfoCodec,
                                taskUpdateRequestCodec,
                                new RemoteTaskStats());
                    }
                });
        Injector injector = app
                .strictConfig()
                .doNotInitializeLogging()
                .quiet()
                .initialize();
        HandleResolver handleResolver = injector.getInstance(HandleResolver.class);
        handleResolver.addConnectorName("test", new TestingHandleResolver());
        return injector.getInstance(HttpRemoteTaskFactory.class);
    }

    private static void poll(BooleanSupplier success)
            throws InterruptedException
    {
        long failAt = System.nanoTime() + FAIL_TIMEOUT.roundTo(NANOSECONDS);

        while (!success.getAsBoolean()) {
            long millisUntilFail = (failAt - System.nanoTime()) / 1_000_000;
            if (millisUntilFail <= 0) {
                throw new AssertionError(format("Timeout of %s reached", FAIL_TIMEOUT));
            }
            Thread.sleep(min(POLL_TIMEOUT.toMillis(), millisUntilFail));
        }
    }

    private static void waitUntilIdle(AtomicLong lastActivityNanos)
            throws InterruptedException
    {
        long startTimeNanos = System.nanoTime();

        while (true) {
            long millisSinceLastActivity = (System.nanoTime() - lastActivityNanos.get()) / 1_000_000L;
            long millisSinceStart = (System.nanoTime() - startTimeNanos) / 1_000_000L;
            long millisToIdleTarget = IDLE_TIMEOUT.toMillis() - millisSinceLastActivity;
            long millisToFailTarget = FAIL_TIMEOUT.toMillis() - millisSinceStart;
            if (millisToFailTarget < millisToIdleTarget) {
                throw new AssertionError(format("Activity doesn't stop after %s", FAIL_TIMEOUT));
            }
            if (millisToIdleTarget < 0) {
                return;
            }
            Thread.sleep(millisToIdleTarget);
        }
    }

    private enum FailureScenario
    {
        NO_FAILURE,
        TASK_MISMATCH,
        TASK_MISMATCH_WHEN_VERSION_IS_HIGH,
        REJECTED_EXECUTION,
    }

    @Path("/task/{nodeId}")
    public static class TestingTaskResource
    {
        private static final String INITIAL_TASK_INSTANCE_ID = "task-instance-id";
        private static final String NEW_TASK_INSTANCE_ID = "task-instance-id-x";

        private final AtomicLong lastActivityNanos;
        private final FailureScenario failureScenario;

        private AtomicReference<TestingHttpClient> httpClient = new AtomicReference<>();

        private TaskInfo initialTaskInfo;
        private TaskStatus initialTaskStatus;
        private long version;
        private TaskState taskState;
        private String taskInstanceId = INITIAL_TASK_INSTANCE_ID;

        private long statusFetchCounter;

        public TestingTaskResource(AtomicLong lastActivityNanos, FailureScenario failureScenario)
        {
            this.lastActivityNanos = requireNonNull(lastActivityNanos, "lastActivityNanos is null");
            this.failureScenario = requireNonNull(failureScenario, "failureScenario is null");
        }

        public void setHttpClient(TestingHttpClient newValue)
        {
            httpClient.set(newValue);
        }

        @GET
        @Path("{taskId}")
        @Produces(MediaType.APPLICATION_JSON)
        public synchronized TaskInfo getTaskInfo(
                @PathParam("taskId") final TaskId taskId,
                @HeaderParam(PRESTO_CURRENT_STATE) TaskState currentState,
                @HeaderParam(PRESTO_MAX_WAIT) Duration maxWait,
                @Context UriInfo uriInfo)
        {
            lastActivityNanos.set(System.nanoTime());
            return buildTaskInfo();
        }

        Map<PlanNodeId, TaskSource> taskSourceMap = new HashMap<>();

        @POST
        @Path("{taskId}")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public synchronized TaskInfo createOrUpdateTask(
                @PathParam("taskId") TaskId taskId,
                TaskUpdateRequest taskUpdateRequest,
                @Context UriInfo uriInfo)
        {
            for (TaskSource source : taskUpdateRequest.getSources()) {
                taskSourceMap.compute(source.getPlanNodeId(), (planNodeId, taskSource) -> taskSource == null ? source : taskSource.update(source));
            }
            lastActivityNanos.set(System.nanoTime());
            return buildTaskInfo();
        }

        public synchronized TaskSource getTaskSource(PlanNodeId planNodeId)
        {
            TaskSource source = taskSourceMap.get(planNodeId);
            if (source == null) {
                return null;
            }
            return new TaskSource(source.getPlanNodeId(), source.getSplits(), source.getNoMoreSplitsForLifespan(), source.isNoMoreSplits());
        }

        @GET
        @Path("{taskId}/status")
        @Produces(MediaType.APPLICATION_JSON)
        public synchronized TaskStatus getTaskStatus(
                @PathParam("taskId") TaskId taskId,
                @HeaderParam(PRESTO_CURRENT_STATE) TaskState currentState,
                @HeaderParam(PRESTO_MAX_WAIT) Duration maxWait,
                @Context UriInfo uriInfo)
                throws InterruptedException
        {
            lastActivityNanos.set(System.nanoTime());

            wait(maxWait.roundTo(MILLISECONDS));
            return buildTaskStatus();
        }

        @DELETE
        @Path("{taskId}")
        @Produces(MediaType.APPLICATION_JSON)
        public synchronized TaskInfo deleteTask(
                @PathParam("taskId") TaskId taskId,
                @QueryParam("abort") @DefaultValue("true") boolean abort,
                @Context UriInfo uriInfo)
        {
            lastActivityNanos.set(System.nanoTime());

            taskState = abort ? TaskState.ABORTED : TaskState.CANCELED;
            return buildTaskInfo();
        }

        public void setInitialTaskInfo(TaskInfo initialTaskInfo)
        {
            this.initialTaskInfo = initialTaskInfo;
            this.initialTaskStatus = initialTaskInfo.getTaskStatus();
            this.taskState = initialTaskStatus.getState();
            this.version = initialTaskStatus.getVersion();
            switch (failureScenario) {
                case TASK_MISMATCH_WHEN_VERSION_IS_HIGH:
                    // Make the initial version large enough.
                    // This way, the version number can't be reached if it is reset to 0.
                    version = 1_000_000;
                    break;
                case TASK_MISMATCH:
                case REJECTED_EXECUTION:
                case NO_FAILURE:
                    break; // do nothing
                default:
                    throw new UnsupportedOperationException();
            }
        }

        private TaskInfo buildTaskInfo()
        {
            return new TaskInfo(
                    buildTaskStatus(),
                    initialTaskInfo.getLastHeartbeat(),
                    initialTaskInfo.getOutputBuffers(),
                    initialTaskInfo.getNoMoreSplits(),
                    initialTaskInfo.getStats(),
                    initialTaskInfo.isNeedsPlan());
        }

        private TaskStatus buildTaskStatus()
        {
            statusFetchCounter++;
            // Change the task instance id after 10th fetch to simulate worker restart
            switch (failureScenario) {
                case TASK_MISMATCH:
                case TASK_MISMATCH_WHEN_VERSION_IS_HIGH:
                    if (statusFetchCounter == 10) {
                        taskInstanceId = NEW_TASK_INSTANCE_ID;
                        version = 0;
                    }
                    break;
                case REJECTED_EXECUTION:
                    if (statusFetchCounter >= 10) {
                        httpClient.get().close();
                        throw new RejectedExecutionException();
                    }
                    break;
                case NO_FAILURE:
                    break;
                default:
                    throw new UnsupportedOperationException();
            }

            return new TaskStatus(
                    initialTaskStatus.getTaskId(),
                    taskInstanceId,
                    ++version,
                    taskState,
                    initialTaskStatus.getSelf(),
                    "fake",
                    ImmutableSet.of(),
                    initialTaskStatus.getFailures(),
                    initialTaskStatus.getQueuedPartitionedDrivers(),
                    initialTaskStatus.getRunningPartitionedDrivers(),
                    initialTaskStatus.isOutputBufferOverutilized(),
                    initialTaskStatus.getPhysicalWrittenDataSize(),
                    initialTaskStatus.getMemoryReservation(),
                    initialTaskStatus.getSystemMemoryReservation(),
                    initialTaskStatus.getRevocableMemoryReservation(),
                    initialTaskStatus.getFullGcCount(),
                    initialTaskStatus.getFullGcTime());
        }
    }
}
