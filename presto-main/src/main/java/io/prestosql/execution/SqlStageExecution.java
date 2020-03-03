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
package io.prestosql.execution;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.execution.StateMachine.StateChangeListener;
import io.prestosql.execution.buffer.OutputBuffers;
import io.prestosql.execution.scheduler.SplitSchedulerStats;
import io.prestosql.failuredetector.FailureDetector;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.Split;
import io.prestosql.spi.PrestoException;
import io.prestosql.split.RemoteSplit;
import io.prestosql.sql.planner.PlanFragment;
import io.prestosql.sql.planner.plan.PlanFragmentId;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.sql.planner.plan.RemoteSourceNode;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.newConcurrentHashSet;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.prestosql.failuredetector.FailureDetector.State.GONE;
import static io.prestosql.operator.ExchangeOperator.REMOTE_CONNECTOR_ID;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.StandardErrorCode.REMOTE_HOST_GONE;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public final class SqlStageExecution
{
    private final StageStateMachine stateMachine;
    private final RemoteTaskFactory remoteTaskFactory;
    private final NodeTaskMap nodeTaskMap;
    private final boolean summarizeTaskInfo;
    private final Executor executor;
    private final FailureDetector failureDetector;

    private final Map<PlanFragmentId, RemoteSourceNode> exchangeSources;

    private final Map<InternalNode, Set<RemoteTask>> tasks = new ConcurrentHashMap<>();

    @GuardedBy("this")
    private final AtomicInteger nextTaskId = new AtomicInteger();
    @GuardedBy("this")
    private final Set<TaskId> allTasks = newConcurrentHashSet();
    @GuardedBy("this")
    private final Set<TaskId> finishedTasks = newConcurrentHashSet();
    @GuardedBy("this")
    private final Set<TaskId> tasksWithFinalInfo = newConcurrentHashSet();
    @GuardedBy("this")
    private final AtomicBoolean splitsScheduled = new AtomicBoolean();

    @GuardedBy("this")
    private final Multimap<PlanNodeId, RemoteTask> sourceTasks = HashMultimap.create();
    @GuardedBy("this")
    private final Set<PlanNodeId> completeSources = newConcurrentHashSet();
    @GuardedBy("this")
    private final Set<PlanFragmentId> completeSourceFragments = newConcurrentHashSet();

    private final AtomicReference<OutputBuffers> outputBuffers = new AtomicReference<>();

    private final ListenerManager<Set<Lifespan>> completedLifespansChangeListeners = new ListenerManager<>();

    public static SqlStageExecution createSqlStageExecution(
            StageId stageId,
            URI location,
            PlanFragment fragment,
            Map<PlanNodeId, TableInfo> tables,
            RemoteTaskFactory remoteTaskFactory,
            Session session,
            boolean summarizeTaskInfo,
            NodeTaskMap nodeTaskMap,
            ExecutorService executor,
            FailureDetector failureDetector,
            SplitSchedulerStats schedulerStats)
    {
        requireNonNull(stageId, "stageId is null");
        requireNonNull(location, "location is null");
        requireNonNull(fragment, "fragment is null");
        requireNonNull(tables, "tables is null");
        requireNonNull(remoteTaskFactory, "remoteTaskFactory is null");
        requireNonNull(session, "session is null");
        requireNonNull(nodeTaskMap, "nodeTaskMap is null");
        requireNonNull(executor, "executor is null");
        requireNonNull(failureDetector, "failureDetector is null");
        requireNonNull(schedulerStats, "schedulerStats is null");

        SqlStageExecution sqlStageExecution = new SqlStageExecution(
                new StageStateMachine(stageId, location, session, fragment, tables, executor, schedulerStats),
                remoteTaskFactory,
                nodeTaskMap,
                summarizeTaskInfo,
                executor,
                failureDetector);
        sqlStageExecution.initialize();
        return sqlStageExecution;
    }

    private SqlStageExecution(StageStateMachine stateMachine, RemoteTaskFactory remoteTaskFactory, NodeTaskMap nodeTaskMap, boolean summarizeTaskInfo, Executor executor, FailureDetector failureDetector)
    {
        this.stateMachine = stateMachine;
        this.remoteTaskFactory = requireNonNull(remoteTaskFactory, "remoteTaskFactory is null");
        this.nodeTaskMap = requireNonNull(nodeTaskMap, "nodeTaskMap is null");
        this.summarizeTaskInfo = summarizeTaskInfo;
        this.executor = requireNonNull(executor, "executor is null");
        this.failureDetector = requireNonNull(failureDetector, "failureDetector is null");

        ImmutableMap.Builder<PlanFragmentId, RemoteSourceNode> fragmentToExchangeSource = ImmutableMap.builder();
        for (RemoteSourceNode remoteSourceNode : stateMachine.getFragment().getRemoteSourceNodes()) {
            for (PlanFragmentId planFragmentId : remoteSourceNode.getSourceFragmentIds()) {
                fragmentToExchangeSource.put(planFragmentId, remoteSourceNode);
            }
        }
        this.exchangeSources = fragmentToExchangeSource.build();
    }

    // this is a separate method to ensure that the `this` reference is not leaked during construction
    private void initialize()
    {
        stateMachine.addStateChangeListener(newState -> checkAllTaskFinal());
    }

    public StageId getStageId()
    {
        return stateMachine.getStageId();
    }

    public StageState getState()
    {
        return stateMachine.getState();
    }

    /**
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor.
     */
    public void addStateChangeListener(StateChangeListener<StageState> stateChangeListener)
    {
        stateMachine.addStateChangeListener(stateChangeListener);
    }

    /**
     * Add a listener for the final stage info.  This notification is guaranteed to be fired only once.
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    public void addFinalStageInfoListener(StateChangeListener<StageInfo> stateChangeListener)
    {
        stateMachine.addFinalStageInfoListener(stateChangeListener);
    }

    public void addCompletedDriverGroupsChangedListener(Consumer<Set<Lifespan>> newlyCompletedDriverGroupConsumer)
    {
        completedLifespansChangeListeners.addListener(newlyCompletedDriverGroupConsumer);
    }

    public PlanFragment getFragment()
    {
        return stateMachine.getFragment();
    }

    public OutputBuffers getOutputBuffers()
    {
        return outputBuffers.get();
    }

    public void beginScheduling()
    {
        stateMachine.transitionToScheduling();
    }

    public synchronized void transitionToSchedulingSplits()
    {
        stateMachine.transitionToSchedulingSplits();
    }

    public synchronized void schedulingComplete()
    {
        if (!stateMachine.transitionToScheduled()) {
            return;
        }

        if (getAllTasks().stream().anyMatch(task -> getState() == StageState.RUNNING)) {
            stateMachine.transitionToRunning();
        }
        if (finishedTasks.containsAll(allTasks)) {
            stateMachine.transitionToFinished();
        }

        for (PlanNodeId partitionedSource : stateMachine.getFragment().getPartitionedSources()) {
            schedulingComplete(partitionedSource);
        }
    }

    public synchronized void schedulingComplete(PlanNodeId partitionedSource)
    {
        for (RemoteTask task : getAllTasks()) {
            task.noMoreSplits(partitionedSource);
        }
        completeSources.add(partitionedSource);
    }

    public synchronized void cancel()
    {
        stateMachine.transitionToCanceled();
        getAllTasks().forEach(RemoteTask::cancel);
    }

    public synchronized void abort()
    {
        stateMachine.transitionToAborted();
        getAllTasks().forEach(RemoteTask::abort);
    }

    public long getUserMemoryReservation()
    {
        return stateMachine.getUserMemoryReservation();
    }

    public long getTotalMemoryReservation()
    {
        return stateMachine.getTotalMemoryReservation();
    }

    public synchronized Duration getTotalCpuTime()
    {
        long millis = getAllTasks().stream()
                .mapToLong(task -> task.getTaskInfo().getStats().getTotalCpuTime().toMillis())
                .sum();
        return new Duration(millis, TimeUnit.MILLISECONDS);
    }

    public BasicStageStats getBasicStageStats()
    {
        return stateMachine.getBasicStageStats(this::getAllTaskInfo);
    }

    public StageInfo getStageInfo()
    {
        return stateMachine.getStageInfo(this::getAllTaskInfo);
    }

    private Iterable<TaskInfo> getAllTaskInfo()
    {
        return getAllTasks().stream()
                .map(RemoteTask::getTaskInfo)
                .collect(toImmutableList());
    }

    public synchronized void addExchangeLocations(PlanFragmentId fragmentId, Set<RemoteTask> sourceTasks, boolean noMoreExchangeLocations)
    {
        requireNonNull(fragmentId, "fragmentId is null");
        requireNonNull(sourceTasks, "sourceTasks is null");

        RemoteSourceNode remoteSource = exchangeSources.get(fragmentId);
        checkArgument(remoteSource != null, "Unknown remote source %s. Known sources are %s", fragmentId, exchangeSources.keySet());

        this.sourceTasks.putAll(remoteSource.getId(), sourceTasks);

        for (RemoteTask task : getAllTasks()) {
            ImmutableMultimap.Builder<PlanNodeId, Split> newSplits = ImmutableMultimap.builder();
            for (RemoteTask sourceTask : sourceTasks) {
                URI exchangeLocation = sourceTask.getTaskStatus().getSelf();
                newSplits.put(remoteSource.getId(), createRemoteSplitFor(task.getTaskId(), exchangeLocation));
            }
            task.addSplits(newSplits.build());
        }

        if (noMoreExchangeLocations) {
            completeSourceFragments.add(fragmentId);

            // is the source now complete?
            if (completeSourceFragments.containsAll(remoteSource.getSourceFragmentIds())) {
                completeSources.add(remoteSource.getId());
                for (RemoteTask task : getAllTasks()) {
                    task.noMoreSplits(remoteSource.getId());
                }
            }
        }
    }

    public synchronized void setOutputBuffers(OutputBuffers outputBuffers)
    {
        requireNonNull(outputBuffers, "outputBuffers is null");

        while (true) {
            OutputBuffers currentOutputBuffers = this.outputBuffers.get();
            if (currentOutputBuffers != null) {
                if (outputBuffers.getVersion() <= currentOutputBuffers.getVersion()) {
                    return;
                }
                currentOutputBuffers.checkValidTransition(outputBuffers);
            }

            if (this.outputBuffers.compareAndSet(currentOutputBuffers, outputBuffers)) {
                for (RemoteTask task : getAllTasks()) {
                    task.setOutputBuffers(outputBuffers);
                }
                return;
            }
        }
    }

    // do not synchronize
    // this is used for query info building which should be independent of scheduling work
    public boolean hasTasks()
    {
        return !tasks.isEmpty();
    }

    // do not synchronize
    // this is used for query info building which should be independent of scheduling work
    public List<RemoteTask> getAllTasks()
    {
        return tasks.values().stream()
                .flatMap(Set::stream)
                .collect(toImmutableList());
    }

    public synchronized Optional<RemoteTask> scheduleTask(InternalNode node, int partition, OptionalInt totalPartitions)
    {
        requireNonNull(node, "node is null");

        if (stateMachine.getState().isDone()) {
            return Optional.empty();
        }
        checkState(!splitsScheduled.get(), "scheduleTask can not be called once splits have been scheduled");
        return Optional.of(scheduleTask(node, new TaskId(stateMachine.getStageId(), partition), ImmutableMultimap.of(), totalPartitions));
    }

    public synchronized Set<RemoteTask> scheduleSplits(InternalNode node, Multimap<PlanNodeId, Split> splits, Multimap<PlanNodeId, Lifespan> noMoreSplitsNotification)
    {
        requireNonNull(node, "node is null");
        requireNonNull(splits, "splits is null");

        if (stateMachine.getState().isDone()) {
            return ImmutableSet.of();
        }
        splitsScheduled.set(true);

        checkArgument(stateMachine.getFragment().getPartitionedSources().containsAll(splits.keySet()), "Invalid splits");

        ImmutableSet.Builder<RemoteTask> newTasks = ImmutableSet.builder();
        Collection<RemoteTask> tasks = this.tasks.get(node);
        RemoteTask task;
        if (tasks == null) {
            // The output buffer depends on the task id starting from 0 and being sequential, since each
            // task is assigned a private buffer based on task id.
            TaskId taskId = new TaskId(stateMachine.getStageId(), nextTaskId.getAndIncrement());
            task = scheduleTask(node, taskId, splits, OptionalInt.empty());
            newTasks.add(task);
        }
        else {
            task = tasks.iterator().next();
            task.addSplits(splits);
        }
        if (noMoreSplitsNotification.size() > 1) {
            // The assumption that `noMoreSplitsNotification.size() <= 1` currently holds.
            // If this assumption no longer holds, we should consider calling task.noMoreSplits with multiple entries in one shot.
            // These kind of methods can be expensive since they are grabbing locks and/or sending HTTP requests on change.
            throw new UnsupportedOperationException("This assumption no longer holds: noMoreSplitsNotification.size() < 1");
        }
        for (Entry<PlanNodeId, Lifespan> entry : noMoreSplitsNotification.entries()) {
            task.noMoreSplits(entry.getKey(), entry.getValue());
        }
        return newTasks.build();
    }

    private synchronized RemoteTask scheduleTask(InternalNode node, TaskId taskId, Multimap<PlanNodeId, Split> sourceSplits, OptionalInt totalPartitions)
    {
        checkArgument(!allTasks.contains(taskId), "A task with id %s already exists", taskId);

        ImmutableMultimap.Builder<PlanNodeId, Split> initialSplits = ImmutableMultimap.builder();
        initialSplits.putAll(sourceSplits);

        sourceTasks.forEach((planNodeId, task) -> {
            TaskStatus status = task.getTaskStatus();
            if (status.getState() != TaskState.FINISHED) {
                initialSplits.put(planNodeId, createRemoteSplitFor(taskId, status.getSelf()));
            }
        });

        OutputBuffers outputBuffers = this.outputBuffers.get();
        checkState(outputBuffers != null, "Initial output buffers must be set before a task can be scheduled");

        RemoteTask task = remoteTaskFactory.createRemoteTask(
                stateMachine.getSession(),
                taskId,
                node,
                stateMachine.getFragment(),
                initialSplits.build(),
                totalPartitions,
                outputBuffers,
                nodeTaskMap.createPartitionedSplitCountTracker(node, taskId),
                summarizeTaskInfo);

        completeSources.forEach(task::noMoreSplits);

        allTasks.add(taskId);
        tasks.computeIfAbsent(node, key -> newConcurrentHashSet()).add(task);
        nodeTaskMap.addTask(node, task);

        task.addStateChangeListener(new StageTaskListener());
        task.addFinalTaskInfoListener(this::updateFinalTaskInfo);

        if (!stateMachine.getState().isDone()) {
            task.start();
        }
        else {
            // stage finished while we were scheduling this task
            task.abort();
        }

        return task;
    }

    public Set<InternalNode> getScheduledNodes()
    {
        return ImmutableSet.copyOf(tasks.keySet());
    }

    public void recordGetSplitTime(long start)
    {
        stateMachine.recordGetSplitTime(start);
    }

    private static Split createRemoteSplitFor(TaskId taskId, URI taskLocation)
    {
        // Fetch the results from the buffer assigned to the task based on id
        URI splitLocation = uriBuilderFrom(taskLocation).appendPath("results").appendPath(String.valueOf(taskId.getId())).build();
        return new Split(REMOTE_CONNECTOR_ID, new RemoteSplit(splitLocation), Lifespan.taskWide());
    }

    private synchronized void updateTaskStatus(TaskStatus taskStatus)
    {
        try {
            StageState stageState = getState();
            if (stageState.isDone()) {
                return;
            }

            TaskState taskState = taskStatus.getState();
            if (taskState == TaskState.FAILED) {
                RuntimeException failure = taskStatus.getFailures().stream()
                        .findFirst()
                        .map(this::rewriteTransportFailure)
                        .map(ExecutionFailureInfo::toException)
                        .orElse(new PrestoException(GENERIC_INTERNAL_ERROR, "A task failed for an unknown reason"));
                stateMachine.transitionToFailed(failure);
            }
            else if (taskState == TaskState.ABORTED) {
                // A task should only be in the aborted state if the STAGE is done (ABORTED or FAILED)
                stateMachine.transitionToFailed(new PrestoException(GENERIC_INTERNAL_ERROR, "A task is in the ABORTED state but stage is " + stageState));
            }
            else if (taskState == TaskState.FINISHED) {
                finishedTasks.add(taskStatus.getTaskId());
            }

            if (stageState == StageState.SCHEDULED || stageState == StageState.RUNNING) {
                if (taskState == TaskState.RUNNING) {
                    stateMachine.transitionToRunning();
                }
                if (finishedTasks.containsAll(allTasks)) {
                    stateMachine.transitionToFinished();
                }
            }
        }
        finally {
            // after updating state, check if all tasks have final status information
            checkAllTaskFinal();
        }
    }

    private synchronized void updateFinalTaskInfo(TaskInfo finalTaskInfo)
    {
        tasksWithFinalInfo.add(finalTaskInfo.getTaskStatus().getTaskId());
        checkAllTaskFinal();
    }

    private synchronized void checkAllTaskFinal()
    {
        if (stateMachine.getState().isDone() && tasksWithFinalInfo.containsAll(allTasks)) {
            List<TaskInfo> finalTaskInfos = getAllTasks().stream()
                    .map(RemoteTask::getTaskInfo)
                    .collect(toImmutableList());
            stateMachine.setAllTasksFinal(finalTaskInfos);
        }
    }

    private ExecutionFailureInfo rewriteTransportFailure(ExecutionFailureInfo executionFailureInfo)
    {
        if (executionFailureInfo.getRemoteHost() == null || failureDetector.getState(executionFailureInfo.getRemoteHost()) != GONE) {
            return executionFailureInfo;
        }

        return new ExecutionFailureInfo(
                executionFailureInfo.getType(),
                executionFailureInfo.getMessage(),
                executionFailureInfo.getCause(),
                executionFailureInfo.getSuppressed(),
                executionFailureInfo.getStack(),
                executionFailureInfo.getErrorLocation(),
                REMOTE_HOST_GONE.toErrorCode(),
                executionFailureInfo.getRemoteHost());
    }

    @Override
    public String toString()
    {
        return stateMachine.toString();
    }

    private class StageTaskListener
            implements StateChangeListener<TaskStatus>
    {
        private long previousUserMemory;
        private long previousSystemMemory;
        private long previousRevocableMemory;
        private final Set<Lifespan> completedDriverGroups = new HashSet<>();

        @Override
        public void stateChanged(TaskStatus taskStatus)
        {
            try {
                updateMemoryUsage(taskStatus);
                updateCompletedDriverGroups(taskStatus);
            }
            finally {
                updateTaskStatus(taskStatus);
            }
        }

        private synchronized void updateMemoryUsage(TaskStatus taskStatus)
        {
            long currentUserMemory = taskStatus.getMemoryReservation().toBytes();
            long currentSystemMemory = taskStatus.getSystemMemoryReservation().toBytes();
            long currentRevocableMemory = taskStatus.getRevocableMemoryReservation().toBytes();
            long deltaUserMemoryInBytes = currentUserMemory - previousUserMemory;
            long deltaRevocableMemoryInBytes = currentRevocableMemory - previousRevocableMemory;
            long deltaTotalMemoryInBytes = (currentUserMemory + currentSystemMemory + currentRevocableMemory) - (previousUserMemory + previousSystemMemory + previousRevocableMemory);
            previousUserMemory = currentUserMemory;
            previousSystemMemory = currentSystemMemory;
            previousRevocableMemory = currentRevocableMemory;
            stateMachine.updateMemoryUsage(deltaUserMemoryInBytes, deltaRevocableMemoryInBytes, deltaTotalMemoryInBytes);
        }

        private synchronized void updateCompletedDriverGroups(TaskStatus taskStatus)
        {
            // Sets.difference returns a view.
            // Once we add the difference into `completedDriverGroups`, the view will be empty.
            // `completedLifespansChangeListeners.invoke` happens asynchronously.
            // As a result, calling the listeners before updating `completedDriverGroups` doesn't make a difference.
            // That's why a copy must be made here.
            Set<Lifespan> newlyCompletedDriverGroups = ImmutableSet.copyOf(Sets.difference(taskStatus.getCompletedDriverGroups(), this.completedDriverGroups));
            if (newlyCompletedDriverGroups.isEmpty()) {
                return;
            }
            completedLifespansChangeListeners.invoke(newlyCompletedDriverGroups, executor);
            // newlyCompletedDriverGroups is a view.
            // Making changes to completedDriverGroups will change newlyCompletedDriverGroups.
            completedDriverGroups.addAll(newlyCompletedDriverGroups);
        }
    }

    private static class ListenerManager<T>
    {
        private final List<Consumer<T>> listeners = new ArrayList<>();
        private boolean frozen;

        public synchronized void addListener(Consumer<T> listener)
        {
            checkState(!frozen, "Listeners have been invoked");
            listeners.add(listener);
        }

        public synchronized void invoke(T payload, Executor executor)
        {
            frozen = true;
            for (Consumer<T> listener : listeners) {
                executor.execute(() -> listener.accept(payload));
            }
        }
    }
}
