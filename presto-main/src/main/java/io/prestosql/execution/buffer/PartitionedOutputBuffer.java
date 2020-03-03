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
package io.prestosql.execution.buffer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.prestosql.execution.StateMachine;
import io.prestosql.execution.StateMachine.StateChangeListener;
import io.prestosql.execution.buffer.OutputBuffers.OutputBufferId;
import io.prestosql.memory.context.LocalMemoryContext;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.execution.buffer.BufferState.FAILED;
import static io.prestosql.execution.buffer.BufferState.FINISHED;
import static io.prestosql.execution.buffer.BufferState.FLUSHING;
import static io.prestosql.execution.buffer.BufferState.NO_MORE_BUFFERS;
import static io.prestosql.execution.buffer.BufferState.NO_MORE_PAGES;
import static io.prestosql.execution.buffer.BufferState.OPEN;
import static io.prestosql.execution.buffer.OutputBuffers.BufferType.PARTITIONED;
import static java.util.Objects.requireNonNull;

public class PartitionedOutputBuffer
        implements OutputBuffer
{
    private final StateMachine<BufferState> state;
    private final OutputBuffers outputBuffers;
    private final OutputBufferMemoryManager memoryManager;

    private final List<ClientBuffer> partitions;

    private final AtomicLong totalPagesAdded = new AtomicLong();
    private final AtomicLong totalRowsAdded = new AtomicLong();

    public PartitionedOutputBuffer(
            String taskInstanceId,
            StateMachine<BufferState> state,
            OutputBuffers outputBuffers,
            DataSize maxBufferSize,
            Supplier<LocalMemoryContext> systemMemoryContextSupplier,
            Executor notificationExecutor)
    {
        this.state = requireNonNull(state, "state is null");

        requireNonNull(outputBuffers, "outputBuffers is null");
        checkArgument(outputBuffers.getType() == PARTITIONED, "Expected a PARTITIONED output buffer descriptor");
        checkArgument(outputBuffers.isNoMoreBufferIds(), "Expected a final output buffer descriptor");
        this.outputBuffers = outputBuffers;

        this.memoryManager = new OutputBufferMemoryManager(
                requireNonNull(maxBufferSize, "maxBufferSize is null").toBytes(),
                requireNonNull(systemMemoryContextSupplier, "systemMemoryContextSupplier is null"),
                requireNonNull(notificationExecutor, "notificationExecutor is null"));

        ImmutableList.Builder<ClientBuffer> partitions = ImmutableList.builder();
        for (OutputBufferId bufferId : outputBuffers.getBuffers().keySet()) {
            ClientBuffer partition = new ClientBuffer(taskInstanceId, bufferId);
            partitions.add(partition);
        }
        this.partitions = partitions.build();

        state.compareAndSet(OPEN, NO_MORE_BUFFERS);
        state.compareAndSet(NO_MORE_PAGES, FLUSHING);
        checkFlushComplete();
    }

    @Override
    public void addStateChangeListener(StateChangeListener<BufferState> stateChangeListener)
    {
        state.addStateChangeListener(stateChangeListener);
    }

    @Override
    public boolean isFinished()
    {
        return state.get() == FINISHED;
    }

    @Override
    public double getUtilization()
    {
        return memoryManager.getUtilization();
    }

    @Override
    public boolean isOverutilized()
    {
        return memoryManager.isOverutilized();
    }

    @Override
    public OutputBufferInfo getInfo()
    {
        //
        // NOTE: this code must be lock free so we do not hang for state machine updates
        //

        // always get the state first before any other stats
        BufferState state = this.state.get();

        int totalBufferedPages = 0;
        ImmutableList.Builder<BufferInfo> infos = ImmutableList.builder();
        for (ClientBuffer partition : partitions) {
            BufferInfo bufferInfo = partition.getInfo();
            infos.add(bufferInfo);

            PageBufferInfo pageBufferInfo = bufferInfo.getPageBufferInfo();
            totalBufferedPages += pageBufferInfo.getBufferedPages();
        }

        return new OutputBufferInfo(
                "PARTITIONED",
                state,
                state.canAddBuffers(),
                state.canAddPages(),
                memoryManager.getBufferedBytes(),
                totalBufferedPages,
                totalRowsAdded.get(),
                totalPagesAdded.get(),
                infos.build());
    }

    @Override
    public void setOutputBuffers(OutputBuffers newOutputBuffers)
    {
        requireNonNull(newOutputBuffers, "newOutputBuffers is null");

        // ignore buffers added after query finishes, which can happen when a query is canceled
        // also ignore old versions, which is normal
        if (state.get().isTerminal() || outputBuffers.getVersion() >= newOutputBuffers.getVersion()) {
            return;
        }

        // no more buffers can be added but verify this is valid state change
        outputBuffers.checkValidTransition(newOutputBuffers);
    }

    @Override
    public ListenableFuture<?> isFull()
    {
        return memoryManager.getBufferBlockedFuture();
    }

    @Override
    public void enqueue(List<SerializedPage> pages)
    {
        checkState(partitions.size() == 1, "Expected exactly one partition");
        enqueue(0, pages);
    }

    @Override
    public void enqueue(int partitionNumber, List<SerializedPage> pages)
    {
        requireNonNull(pages, "pages is null");

        // ignore pages after "no more pages" is set
        // this can happen with a limit query
        if (!state.get().canAddPages()) {
            return;
        }

        // reserve memory
        long bytesAdded = pages.stream().mapToLong(SerializedPage::getRetainedSizeInBytes).sum();
        memoryManager.updateMemoryUsage(bytesAdded);

        // update stats
        long rowCount = pages.stream().mapToLong(SerializedPage::getPositionCount).sum();
        totalRowsAdded.addAndGet(rowCount);
        totalPagesAdded.addAndGet(pages.size());

        // create page reference counts with an initial single reference
        List<SerializedPageReference> serializedPageReferences = pages.stream()
                .map(bufferedPage -> new SerializedPageReference(bufferedPage, 1, () -> memoryManager.updateMemoryUsage(-bufferedPage.getRetainedSizeInBytes())))
                .collect(toImmutableList());

        // add pages to the buffer (this will increase the reference count by one)
        partitions.get(partitionNumber).enqueuePages(serializedPageReferences);

        // drop the initial reference
        serializedPageReferences.forEach(SerializedPageReference::dereferencePage);
    }

    @Override
    public ListenableFuture<BufferResult> get(OutputBufferId outputBufferId, long startingSequenceId, DataSize maxSize)
    {
        requireNonNull(outputBufferId, "outputBufferId is null");
        checkArgument(maxSize.toBytes() > 0, "maxSize must be at least 1 byte");

        return partitions.get(outputBufferId.getId()).getPages(startingSequenceId, maxSize);
    }

    @Override
    public void acknowledge(OutputBufferId outputBufferId, long sequenceId)
    {
        requireNonNull(outputBufferId, "bufferId is null");

        partitions.get(outputBufferId.getId()).acknowledgePages(sequenceId);
    }

    @Override
    public void abort(OutputBufferId bufferId)
    {
        requireNonNull(bufferId, "bufferId is null");

        partitions.get(bufferId.getId()).destroy();

        checkFlushComplete();
    }

    @Override
    public void setNoMorePages()
    {
        state.compareAndSet(OPEN, NO_MORE_PAGES);
        state.compareAndSet(NO_MORE_BUFFERS, FLUSHING);
        memoryManager.setNoBlockOnFull();

        partitions.forEach(ClientBuffer::setNoMorePages);

        checkFlushComplete();
    }

    @Override
    public void destroy()
    {
        // ignore destroy if the buffer already in a terminal state.
        if (state.setIf(FINISHED, oldState -> !oldState.isTerminal())) {
            partitions.forEach(ClientBuffer::destroy);
            memoryManager.setNoBlockOnFull();
            forceFreeMemory();
        }
    }

    @Override
    public void fail()
    {
        // ignore fail if the buffer already in a terminal state.
        if (state.setIf(FAILED, oldState -> !oldState.isTerminal())) {
            memoryManager.setNoBlockOnFull();
            forceFreeMemory();
            // DO NOT destroy buffers or set no more pages.  The coordinator manages the teardown of failed queries.
        }
    }

    @Override
    public long getPeakMemoryUsage()
    {
        return memoryManager.getPeakMemoryUsage();
    }

    @VisibleForTesting
    void forceFreeMemory()
    {
        memoryManager.close();
    }

    private void checkFlushComplete()
    {
        if (state.get() != FLUSHING && state.get() != NO_MORE_BUFFERS) {
            return;
        }

        if (partitions.stream().allMatch(ClientBuffer::isDestroyed)) {
            destroy();
        }
    }

    @VisibleForTesting
    OutputBufferMemoryManager getMemoryManager()
    {
        return memoryManager;
    }
}
