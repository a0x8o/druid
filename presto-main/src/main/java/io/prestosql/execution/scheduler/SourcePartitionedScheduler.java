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
package io.prestosql.execution.scheduler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.prestosql.execution.Lifespan;
import io.prestosql.execution.RemoteTask;
import io.prestosql.execution.SqlStageExecution;
import io.prestosql.execution.scheduler.FixedSourcePartitionedScheduler.BucketedSplitPlacementPolicy;
import io.prestosql.metadata.InternalNode;
import io.prestosql.metadata.Split;
import io.prestosql.spi.connector.ConnectorPartitionHandle;
import io.prestosql.split.EmptySplit;
import io.prestosql.split.SplitSource;
import io.prestosql.split.SplitSource.SplitBatch;
import io.prestosql.sql.planner.plan.PlanNodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static io.airlift.concurrent.MoreFutures.addSuccessCallback;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.MoreFutures.whenAnyComplete;
import static io.prestosql.execution.scheduler.ScheduleResult.BlockedReason.MIXED_SPLIT_QUEUES_FULL_AND_WAITING_FOR_SOURCE;
import static io.prestosql.execution.scheduler.ScheduleResult.BlockedReason.NO_ACTIVE_DRIVER_GROUP;
import static io.prestosql.execution.scheduler.ScheduleResult.BlockedReason.SPLIT_QUEUES_FULL;
import static io.prestosql.execution.scheduler.ScheduleResult.BlockedReason.WAITING_FOR_SOURCE;
import static io.prestosql.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static java.util.Objects.requireNonNull;

public class SourcePartitionedScheduler
        implements SourceScheduler
{
    private enum State
    {
        /**
         * No splits have been added to pendingSplits set.
         */
        INITIALIZED,

        /**
         * At least one split has been added to pendingSplits set.
         */
        SPLITS_ADDED,

        /**
         * All splits from underlying SplitSource have been discovered.
         * No more splits will be added to the pendingSplits set.
         */
        NO_MORE_SPLITS,

        /**
         * All splits have been provided to caller of this scheduler.
         * Cleanup operations are done (e.g., drainCompletedLifespans has drained all driver groups).
         */
        FINISHED
    }

    private final SqlStageExecution stage;
    private final SplitSource splitSource;
    private final SplitPlacementPolicy splitPlacementPolicy;
    private final int splitBatchSize;
    private final PlanNodeId partitionedNode;
    private final boolean groupedExecution;

    private final Map<Lifespan, ScheduleGroup> scheduleGroups = new HashMap<>();
    private boolean noMoreScheduleGroups;
    private State state = State.INITIALIZED;

    private SettableFuture<?> whenFinishedOrNewLifespanAdded = SettableFuture.create();

    private SourcePartitionedScheduler(
            SqlStageExecution stage,
            PlanNodeId partitionedNode,
            SplitSource splitSource,
            SplitPlacementPolicy splitPlacementPolicy,
            int splitBatchSize,
            boolean groupedExecution)
    {
        this.stage = requireNonNull(stage, "stage is null");
        this.partitionedNode = requireNonNull(partitionedNode, "partitionedNode is null");
        this.splitSource = requireNonNull(splitSource, "splitSource is null");
        this.splitPlacementPolicy = requireNonNull(splitPlacementPolicy, "splitPlacementPolicy is null");

        checkArgument(splitBatchSize > 0, "splitBatchSize must be at least one");
        this.splitBatchSize = splitBatchSize;
        this.groupedExecution = groupedExecution;
    }

    public PlanNodeId getPlanNodeId()
    {
        return partitionedNode;
    }

    /**
     * Obtains an instance of {@code SourcePartitionedScheduler} suitable for use as a
     * stage scheduler.
     * <p>
     * This returns an ungrouped {@code SourcePartitionedScheduler} that requires
     * minimal management from the caller, which is ideal for use as a stage scheduler.
     */
    public static StageScheduler newSourcePartitionedSchedulerAsStageScheduler(
            SqlStageExecution stage,
            PlanNodeId partitionedNode,
            SplitSource splitSource,
            SplitPlacementPolicy splitPlacementPolicy,
            int splitBatchSize)
    {
        SourcePartitionedScheduler sourcePartitionedScheduler = new SourcePartitionedScheduler(stage, partitionedNode, splitSource, splitPlacementPolicy, splitBatchSize, false);
        sourcePartitionedScheduler.startLifespan(Lifespan.taskWide(), NOT_PARTITIONED);
        sourcePartitionedScheduler.noMoreLifespans();

        return new StageScheduler()
        {
            @Override
            public ScheduleResult schedule()
            {
                ScheduleResult scheduleResult = sourcePartitionedScheduler.schedule();
                sourcePartitionedScheduler.drainCompletedLifespans();
                return scheduleResult;
            }

            @Override
            public void close()
            {
                sourcePartitionedScheduler.close();
            }
        };
    }

    /**
     * Obtains a {@code SourceScheduler} suitable for use in FixedSourcePartitionedScheduler.
     * <p>
     * This returns a {@code SourceScheduler} that can be used for a pipeline
     * that is either ungrouped or grouped. However, the caller is responsible initializing
     * the driver groups in this scheduler accordingly.
     * <p>
     * Besides, the caller is required to poll {@link #drainCompletedLifespans()}
     * in addition to {@link #schedule()} on the returned object. Otherwise, lifecycle
     * transitioning of the object will not work properly.
     */
    public static SourceScheduler newSourcePartitionedSchedulerAsSourceScheduler(
            SqlStageExecution stage,
            PlanNodeId partitionedNode,
            SplitSource splitSource,
            SplitPlacementPolicy splitPlacementPolicy,
            int splitBatchSize,
            boolean groupedExecution)
    {
        return new SourcePartitionedScheduler(stage, partitionedNode, splitSource, splitPlacementPolicy, splitBatchSize, groupedExecution);
    }

    @Override
    public synchronized void startLifespan(Lifespan lifespan, ConnectorPartitionHandle partitionHandle)
    {
        checkState(state == State.INITIALIZED || state == State.SPLITS_ADDED);
        scheduleGroups.put(lifespan, new ScheduleGroup(partitionHandle));
        whenFinishedOrNewLifespanAdded.set(null);
        whenFinishedOrNewLifespanAdded = SettableFuture.create();
    }

    @Override
    public synchronized void noMoreLifespans()
    {
        checkState(state == State.INITIALIZED || state == State.SPLITS_ADDED);
        noMoreScheduleGroups = true;
        // The listener is waiting for "new lifespan added" because new lifespans would bring new works to scheduler.
        // "No more lifespans" would be of interest to such listeners because it signals that is not going to happen anymore,
        // and the listener should stop waiting.
        whenFinishedOrNewLifespanAdded.set(null);
        whenFinishedOrNewLifespanAdded = SettableFuture.create();
    }

    @Override
    public synchronized ScheduleResult schedule()
    {
        dropListenersFromWhenFinishedOrNewLifespansAdded();

        int overallSplitAssignmentCount = 0;
        ImmutableSet.Builder<RemoteTask> overallNewTasks = ImmutableSet.builder();
        List<ListenableFuture<?>> overallBlockedFutures = new ArrayList<>();
        boolean anyBlockedOnPlacements = false;
        boolean anyBlockedOnNextSplitBatch = false;
        boolean anyNotBlocked = false;

        for (Entry<Lifespan, ScheduleGroup> entry : scheduleGroups.entrySet()) {
            Lifespan lifespan = entry.getKey();
            ScheduleGroup scheduleGroup = entry.getValue();
            Set<Split> pendingSplits = scheduleGroup.pendingSplits;

            if (scheduleGroup.state == ScheduleGroupState.NO_MORE_SPLITS || scheduleGroup.state == ScheduleGroupState.DONE) {
                verify(scheduleGroup.nextSplitBatchFuture == null);
            }
            else if (pendingSplits.isEmpty()) {
                // try to get the next batch
                if (scheduleGroup.nextSplitBatchFuture == null) {
                    scheduleGroup.nextSplitBatchFuture = splitSource.getNextBatch(scheduleGroup.partitionHandle, lifespan, splitBatchSize - pendingSplits.size());

                    long start = System.nanoTime();
                    addSuccessCallback(scheduleGroup.nextSplitBatchFuture, () -> stage.recordGetSplitTime(start));
                }

                if (scheduleGroup.nextSplitBatchFuture.isDone()) {
                    SplitBatch nextSplits = getFutureValue(scheduleGroup.nextSplitBatchFuture);
                    scheduleGroup.nextSplitBatchFuture = null;
                    pendingSplits.addAll(nextSplits.getSplits());
                    if (nextSplits.isLastBatch()) {
                        if (scheduleGroup.state == ScheduleGroupState.INITIALIZED && pendingSplits.isEmpty()) {
                            // Add an empty split in case no splits have been produced for the source.
                            // For source operators, they never take input, but they may produce output.
                            // This is well handled by Presto execution engine.
                            // However, there are certain non-source operators that may produce output without any input,
                            // for example, 1) an AggregationOperator, 2) a HashAggregationOperator where one of the grouping sets is ().
                            // Scheduling an empty split kicks off necessary driver instantiation to make this work.
                            pendingSplits.add(new Split(
                                    splitSource.getCatalogName(),
                                    new EmptySplit(splitSource.getCatalogName()),
                                    lifespan));
                        }
                        scheduleGroup.state = ScheduleGroupState.NO_MORE_SPLITS;
                    }
                }
                else {
                    overallBlockedFutures.add(scheduleGroup.nextSplitBatchFuture);
                    anyBlockedOnNextSplitBatch = true;
                    continue;
                }
            }

            Multimap<InternalNode, Split> splitAssignment = ImmutableMultimap.of();
            if (!pendingSplits.isEmpty()) {
                if (!scheduleGroup.placementFuture.isDone()) {
                    anyBlockedOnPlacements = true;
                    continue;
                }

                if (scheduleGroup.state == ScheduleGroupState.INITIALIZED) {
                    scheduleGroup.state = ScheduleGroupState.SPLITS_ADDED;
                }
                if (state == State.INITIALIZED) {
                    state = State.SPLITS_ADDED;
                }

                // calculate placements for splits
                SplitPlacementResult splitPlacementResult = splitPlacementPolicy.computeAssignments(pendingSplits);
                splitAssignment = splitPlacementResult.getAssignments();

                // remove splits with successful placements
                splitAssignment.values().forEach(pendingSplits::remove); // AbstractSet.removeAll performs terribly here.
                overallSplitAssignmentCount += splitAssignment.size();

                // if not completed placed, mark scheduleGroup as blocked on placement
                if (!pendingSplits.isEmpty()) {
                    scheduleGroup.placementFuture = splitPlacementResult.getBlocked();
                    overallBlockedFutures.add(scheduleGroup.placementFuture);
                    anyBlockedOnPlacements = true;
                }
            }

            // if no new splits will be assigned, update state and attach completion event
            Multimap<InternalNode, Lifespan> noMoreSplitsNotification = ImmutableMultimap.of();
            if (pendingSplits.isEmpty() && scheduleGroup.state == ScheduleGroupState.NO_MORE_SPLITS) {
                scheduleGroup.state = ScheduleGroupState.DONE;
                if (!lifespan.isTaskWide()) {
                    InternalNode node = ((BucketedSplitPlacementPolicy) splitPlacementPolicy).getNodeForBucket(lifespan.getId());
                    noMoreSplitsNotification = ImmutableMultimap.of(node, lifespan);
                }
            }

            // assign the splits with successful placements
            overallNewTasks.addAll(assignSplits(splitAssignment, noMoreSplitsNotification));

            // Assert that "placement future is not done" implies "pendingSplits is not empty".
            // The other way around is not true. One obvious reason is (un)lucky timing, where the placement is unblocked between `computeAssignments` and this line.
            // However, there are other reasons that could lead to this.
            // Note that `computeAssignments` is quite broken:
            // 1. It always returns a completed future when there are no tasks, regardless of whether all nodes are blocked.
            // 2. The returned future will only be completed when a node with an assigned task becomes unblocked. Other nodes don't trigger future completion.
            // As a result, to avoid busy loops caused by 1, we check pendingSplits.isEmpty() instead of placementFuture.isDone() here.
            if (scheduleGroup.nextSplitBatchFuture == null && scheduleGroup.pendingSplits.isEmpty() && scheduleGroup.state != ScheduleGroupState.DONE) {
                anyNotBlocked = true;
            }
        }

        // * `splitSource.isFinished` invocation may fail after `splitSource.close` has been invoked.
        //   If state is NO_MORE_SPLITS/FINISHED, splitSource.isFinished has previously returned true, and splitSource is closed now.
        // * Even if `splitSource.isFinished()` return true, it is not necessarily safe to tear down the split source.
        //   * If anyBlockedOnNextSplitBatch is true, it means we have not checked out the recently completed nextSplitBatch futures,
        //     which may contain recently published splits. We must not ignore those.
        //   * If any scheduleGroup is still in DISCOVERING_SPLITS state, it means it hasn't realized that there will be no more splits.
        //     Next time it invokes getNextBatch, it will realize that. However, the invocation will fail we tear down splitSource now.
        if ((state == State.NO_MORE_SPLITS || state == State.FINISHED) || (noMoreScheduleGroups && scheduleGroups.isEmpty() && splitSource.isFinished())) {
            switch (state) {
                case INITIALIZED:
                    // We have not scheduled a single split so far.
                    // But this shouldn't be possible. See usage of EmptySplit in this method.
                    throw new IllegalStateException("At least 1 split should have been scheduled for this plan node");
                case SPLITS_ADDED:
                    state = State.NO_MORE_SPLITS;
                    splitSource.close();
                    // fall through
                case NO_MORE_SPLITS:
                    state = State.FINISHED;
                    whenFinishedOrNewLifespanAdded.set(null);
                    // fall through
                case FINISHED:
                    return new ScheduleResult(
                            true,
                            overallNewTasks.build(),
                            overallSplitAssignmentCount);
                default:
                    throw new IllegalStateException("Unknown state");
            }
        }

        if (anyNotBlocked) {
            return new ScheduleResult(false, overallNewTasks.build(), overallSplitAssignmentCount);
        }

        if (anyBlockedOnPlacements || groupedExecution) {
            // In a broadcast join, output buffers of the tasks in build source stage have to
            // hold onto all data produced before probe side task scheduling finishes,
            // even if the data is acknowledged by all known consumers. This is because
            // new consumers may be added until the probe side task scheduling finishes.
            //
            // As a result, the following line is necessary to prevent deadlock
            // due to neither build nor probe can make any progress.
            // The build side blocks due to a full output buffer.
            // In the meantime the probe side split cannot be consumed since
            // builder side hash table construction has not finished.
            overallNewTasks.addAll(finalizeTaskCreationIfNecessary());
        }

        ScheduleResult.BlockedReason blockedReason;
        if (anyBlockedOnNextSplitBatch) {
            blockedReason = anyBlockedOnPlacements ? MIXED_SPLIT_QUEUES_FULL_AND_WAITING_FOR_SOURCE : WAITING_FOR_SOURCE;
        }
        else {
            blockedReason = anyBlockedOnPlacements ? SPLIT_QUEUES_FULL : NO_ACTIVE_DRIVER_GROUP;
        }

        overallBlockedFutures.add(whenFinishedOrNewLifespanAdded);
        return new ScheduleResult(
                false,
                overallNewTasks.build(),
                nonCancellationPropagating(whenAnyComplete(overallBlockedFutures)),
                blockedReason,
                overallSplitAssignmentCount);
    }

    private synchronized void dropListenersFromWhenFinishedOrNewLifespansAdded()
    {
        // whenFinishedOrNewLifespanAdded may remain in a not-done state for an extended period of time.
        // As a result, over time, it can retain a huge number of listener objects.

        // Whenever schedule is called, holding onto the previous listener is not useful anymore.
        // Therefore, we drop those listeners here by recreating the future.

        // Note: The following implementation is thread-safe because whenFinishedOrNewLifespanAdded can only be completed
        // while holding the monitor of this.

        if (whenFinishedOrNewLifespanAdded.isDone()) {
            return;
        }

        whenFinishedOrNewLifespanAdded.cancel(true);
        whenFinishedOrNewLifespanAdded = SettableFuture.create();
    }

    @Override
    public void close()
    {
        splitSource.close();
    }

    @Override
    public synchronized List<Lifespan> drainCompletedLifespans()
    {
        if (scheduleGroups.isEmpty()) {
            // Invoking splitSource.isFinished would fail if it was already closed, which is possible if scheduleGroups is empty.
            return ImmutableList.of();
        }

        ImmutableList.Builder<Lifespan> result = ImmutableList.builder();
        Iterator<Entry<Lifespan, ScheduleGroup>> entryIterator = scheduleGroups.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Entry<Lifespan, ScheduleGroup> entry = entryIterator.next();
            if (entry.getValue().state == ScheduleGroupState.DONE) {
                result.add(entry.getKey());
                entryIterator.remove();
            }
        }

        if (scheduleGroups.isEmpty() && splitSource.isFinished()) {
            // Wake up blocked caller so that it will invoke schedule() right away.
            // Once schedule is invoked, state will be transitioned to FINISHED.
            whenFinishedOrNewLifespanAdded.set(null);
            whenFinishedOrNewLifespanAdded = SettableFuture.create();
        }

        return result.build();
    }

    private Set<RemoteTask> assignSplits(Multimap<InternalNode, Split> splitAssignment, Multimap<InternalNode, Lifespan> noMoreSplitsNotification)
    {
        ImmutableSet.Builder<RemoteTask> newTasks = ImmutableSet.builder();

        ImmutableSet<InternalNode> nodes = ImmutableSet.<InternalNode>builder()
                .addAll(splitAssignment.keySet())
                .addAll(noMoreSplitsNotification.keySet())
                .build();
        for (InternalNode node : nodes) {
            // source partitioned tasks can only receive broadcast data; otherwise it would have a different distribution
            ImmutableMultimap<PlanNodeId, Split> splits = ImmutableMultimap.<PlanNodeId, Split>builder()
                    .putAll(partitionedNode, splitAssignment.get(node))
                    .build();
            ImmutableMultimap.Builder<PlanNodeId, Lifespan> noMoreSplits = ImmutableMultimap.builder();
            if (noMoreSplitsNotification.containsKey(node)) {
                noMoreSplits.putAll(partitionedNode, noMoreSplitsNotification.get(node));
            }
            newTasks.addAll(stage.scheduleSplits(
                    node,
                    splits,
                    noMoreSplits.build()));
        }
        return newTasks.build();
    }

    private Set<RemoteTask> finalizeTaskCreationIfNecessary()
    {
        // only lock down tasks if there is a sub stage that could block waiting for this stage to create all tasks
        if (stage.getFragment().isLeaf()) {
            return ImmutableSet.of();
        }

        splitPlacementPolicy.lockDownNodes();

        Set<InternalNode> scheduledNodes = stage.getScheduledNodes();
        Set<RemoteTask> newTasks = splitPlacementPolicy.allNodes().stream()
                .filter(node -> !scheduledNodes.contains(node))
                .flatMap(node -> stage.scheduleSplits(node, ImmutableMultimap.of(), ImmutableMultimap.of()).stream())
                .collect(toImmutableSet());

        // notify listeners that we have scheduled all tasks so they can set no more buffers or exchange splits
        stage.transitionToSchedulingSplits();

        return newTasks;
    }

    private static class ScheduleGroup
    {
        public final ConnectorPartitionHandle partitionHandle;
        public ListenableFuture<SplitBatch> nextSplitBatchFuture;
        public ListenableFuture<?> placementFuture = Futures.immediateFuture(null);
        public final Set<Split> pendingSplits = new HashSet<>();
        public ScheduleGroupState state = ScheduleGroupState.INITIALIZED;

        public ScheduleGroup(ConnectorPartitionHandle partitionHandle)
        {
            this.partitionHandle = requireNonNull(partitionHandle, "partitionHandle is null");
        }
    }

    private enum ScheduleGroupState
    {
        /**
         * No splits have been added to pendingSplits set.
         */
        INITIALIZED,

        /**
         * At least one split has been added to pendingSplits set.
         */
        SPLITS_ADDED,

        /**
         * All splits from underlying SplitSource has been discovered.
         * No more splits will be added to the pendingSplits set.
         */
        NO_MORE_SPLITS,

        /**
         * All splits has been provided to caller of this scheduler.
         * Cleanup operations (e.g. inform caller of noMoreSplits) are done.
         */
        DONE
    }
}
