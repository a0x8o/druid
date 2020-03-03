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
package com.facebook.presto.operator;

import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.buffer.PageCodecMarker;
import com.facebook.presto.execution.buffer.SerializedPage;
import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.operator.HttpPageBufferClient.ClientCallback;
import com.facebook.presto.operator.WorkProcessor.ProcessState;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.http.client.HttpClient;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.io.Closeable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.spi.block.PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.Sets.newConcurrentHashSet;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.units.DataSize.Unit.BYTE;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/**
 * {@link ExchangeClient} is the client on receiver side, used in operators requiring data exchange from other tasks,
 * such as {@link ExchangeOperator} and {@link MergeOperator}.
 * For each sender that ExchangeClient receives data from, a {@link HttpPageBufferClient} is used in ExchangeClient to communicate with the sender, i.e.
 *
 * <pre>
 *                    /   HttpPageBufferClient_1  - - - Remote Source 1
 *     ExchangeClient --  HttpPageBufferClient_2  - - - Remote Source 2
 *                    \   ...
 *                     \  HttpPageBufferClient_n  - - - Remote Source n
 * </pre>
 *
 */
@ThreadSafe
public class ExchangeClient
        implements Closeable
{
    private static final SerializedPage NO_MORE_PAGES = new SerializedPage(EMPTY_SLICE, PageCodecMarker.none(), 0, 0);

    private final long bufferCapacity;
    private final DataSize maxResponseSize;
    private final int concurrentRequestMultiplier;
    private final Duration maxErrorDuration;
    private final boolean acknowledgePages;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    @GuardedBy("this")
    private boolean noMoreLocations;

    private final ConcurrentMap<URI, HttpPageBufferClient> allClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, URI> taskIdToLocationMap = new ConcurrentHashMap<>();
    private final Set<TaskId> removedRemoteSourceTaskIds = ConcurrentHashMap.newKeySet();

    @GuardedBy("this")
    private final Deque<HttpPageBufferClient> queuedClients = new LinkedList<>();

    private final Set<HttpPageBufferClient> completedClients = newConcurrentHashSet();
    private final Set<HttpPageBufferClient> removedClients = newConcurrentHashSet();
    private final LinkedBlockingDeque<SerializedPage> pageBuffer = new LinkedBlockingDeque<>();

    @GuardedBy("this")
    private final List<SettableFuture<?>> blockedCallers = new ArrayList<>();

    @GuardedBy("this")
    private long bufferRetainedSizeInBytes;
    @GuardedBy("this")
    private long maxBufferRetainedSizeInBytes;
    @GuardedBy("this")
    private long successfulRequests;
    @GuardedBy("this")
    private final ExponentialMovingAverage responseSizeExponentialMovingAverage;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private final LocalMemoryContext systemMemoryContext;
    private final Executor pageBufferClientCallbackExecutor;

    // ExchangeClientStatus.mergeWith assumes all clients have the same bufferCapacity.
    // Please change that method accordingly when this assumption becomes not true.
    public ExchangeClient(
            DataSize bufferCapacity,
            DataSize maxResponseSize,
            int concurrentRequestMultiplier,
            Duration maxErrorDuration,
            boolean acknowledgePages,
            double responseSizeExponentialMovingAverageDecayingAlpha,
            HttpClient httpClient,
            ScheduledExecutorService scheduler,
            LocalMemoryContext systemMemoryContext,
            Executor pageBufferClientCallbackExecutor)
    {
        checkArgument(responseSizeExponentialMovingAverageDecayingAlpha >= 0.0 && responseSizeExponentialMovingAverageDecayingAlpha <= 1.0, "responseSizeExponentialMovingAverageDecayingAlpha must be between 0 and 1: %s", responseSizeExponentialMovingAverageDecayingAlpha);
        this.bufferCapacity = bufferCapacity.toBytes();
        this.maxResponseSize = maxResponseSize;
        this.concurrentRequestMultiplier = concurrentRequestMultiplier;
        this.maxErrorDuration = maxErrorDuration;
        this.acknowledgePages = acknowledgePages;
        this.httpClient = httpClient;
        this.scheduler = scheduler;
        this.systemMemoryContext = systemMemoryContext;
        this.maxBufferRetainedSizeInBytes = Long.MIN_VALUE;
        this.pageBufferClientCallbackExecutor = requireNonNull(pageBufferClientCallbackExecutor, "pageBufferClientCallbackExecutor is null");
        this.responseSizeExponentialMovingAverage = new ExponentialMovingAverage(responseSizeExponentialMovingAverageDecayingAlpha, DEFAULT_MAX_PAGE_SIZE_IN_BYTES);
    }

    public ExchangeClientStatus getStatus()
    {
        // The stats created by this method is only for diagnostics.
        // It does not guarantee a consistent view between different exchange clients.
        // Guaranteeing a consistent view introduces significant lock contention.
        ImmutableList.Builder<PageBufferClientStatus> pageBufferClientStatusBuilder = ImmutableList.builder();
        for (HttpPageBufferClient client : allClients.values()) {
            pageBufferClientStatusBuilder.add(client.getStatus());
        }
        List<PageBufferClientStatus> pageBufferClientStatus = pageBufferClientStatusBuilder.build();
        synchronized (this) {
            int bufferedPages = pageBuffer.size();
            if (bufferedPages > 0 && pageBuffer.peekLast() == NO_MORE_PAGES) {
                bufferedPages--;
            }
            return new ExchangeClientStatus(bufferRetainedSizeInBytes, maxBufferRetainedSizeInBytes, responseSizeExponentialMovingAverage.get(), successfulRequests, bufferedPages, noMoreLocations, pageBufferClientStatus);
        }
    }

    public synchronized void addLocation(URI location, TaskId remoteSourceTaskId)
    {
        requireNonNull(location, "location is null");

        // Ignore new locations after close
        // NOTE: this MUST happen before checking no more locations is checked
        if (closed.get()) {
            return;
        }

        // ignore duplicate locations
        if (allClients.containsKey(location)) {
            return;
        }

        // already removed
        if (removedRemoteSourceTaskIds.contains(remoteSourceTaskId)) {
            return;
        }

        checkState(!noMoreLocations, "No more locations already set");

        HttpPageBufferClient client = new HttpPageBufferClient(
                httpClient,
                maxErrorDuration,
                acknowledgePages,
                location,
                new ExchangeClientCallback(),
                scheduler,
                pageBufferClientCallbackExecutor);
        allClients.put(location, client);
        checkState(taskIdToLocationMap.put(remoteSourceTaskId, location) == null, "Duplicate remoteSourceTaskId: " + remoteSourceTaskId);
        queuedClients.add(client);

        scheduleRequestIfNecessary();
    }

    public synchronized void removeRemoteSource(TaskId sourceTaskId)
    {
        requireNonNull(sourceTaskId, "sourceTaskId is null");

        // Ignore removeRemoteSource call if exchange client is already closed
        if (closed.get()) {
            return;
        }

        removedRemoteSourceTaskIds.add(sourceTaskId);

        URI location = taskIdToLocationMap.get(sourceTaskId);
        if (location == null) {
            return;
        }

        HttpPageBufferClient client = allClients.get(location);
        if (client == null) {
            return;
        }

        closeQuietly(client);
        removedClients.add(client);
        completedClients.add(client);
    }

    public synchronized void noMoreLocations()
    {
        noMoreLocations = true;
        scheduleRequestIfNecessary();
    }

    public WorkProcessor<SerializedPage> pages()
    {
        return WorkProcessor.create(() -> {
            SerializedPage page = pollPage();
            if (page == null) {
                if (isFinished()) {
                    return ProcessState.finished();
                }

                ListenableFuture<?> blocked = isBlocked();
                if (!blocked.isDone()) {
                    return ProcessState.blocked(blocked);
                }

                return ProcessState.yield();
            }

            return ProcessState.ofResult(page);
        });
    }

    @Nullable
    public SerializedPage pollPage()
    {
        checkState(!Thread.holdsLock(this), "Can not get next page while holding a lock on this");

        throwIfFailed();

        if (closed.get()) {
            return null;
        }

        SerializedPage page = pageBuffer.poll();
        return postProcessPage(page);
    }

    private SerializedPage postProcessPage(SerializedPage page)
    {
        checkState(!Thread.holdsLock(this), "Can not get next page while holding a lock on this");

        if (page == null) {
            return null;
        }

        if (page == NO_MORE_PAGES) {
            // mark client closed; close() will add the end marker
            close();

            notifyBlockedCallers();

            // don't return end of stream marker
            return null;
        }

        synchronized (this) {
            if (!closed.get()) {
                bufferRetainedSizeInBytes -= page.getRetainedSizeInBytes();
                systemMemoryContext.setBytes(bufferRetainedSizeInBytes);
                if (pageBuffer.peek() == NO_MORE_PAGES) {
                    close();
                }
            }
        }
        scheduleRequestIfNecessary();
        return page;
    }

    public boolean isFinished()
    {
        throwIfFailed();
        // For this to works, locations must never be added after is closed is set
        return isClosed() && completedClients.size() == allClients.size();
    }

    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public synchronized void close()
    {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (HttpPageBufferClient client : allClients.values()) {
            closeQuietly(client);
        }
        pageBuffer.clear();
        systemMemoryContext.setBytes(0);
        bufferRetainedSizeInBytes = 0;
        if (pageBuffer.peekLast() != NO_MORE_PAGES) {
            checkState(pageBuffer.add(NO_MORE_PAGES), "Could not add no more pages marker");
        }
        notifyBlockedCallers();
    }

    public synchronized void scheduleRequestIfNecessary()
    {
        if (isFinished() || isFailed()) {
            return;
        }

        // if finished, add the end marker
        if (noMoreLocations && completedClients.size() == allClients.size()) {
            if (pageBuffer.peekLast() != NO_MORE_PAGES) {
                checkState(pageBuffer.add(NO_MORE_PAGES), "Could not add no more pages marker");
            }
            if (pageBuffer.peek() == NO_MORE_PAGES) {
                close();
            }
            notifyBlockedCallers();
            return;
        }

        long neededBytes = bufferCapacity - bufferRetainedSizeInBytes;
        if (neededBytes <= 0) {
            return;
        }
        long averageResponseSize = max(1, responseSizeExponentialMovingAverage.get());
        int clientCount = (int) ((1.0 * neededBytes / averageResponseSize) * concurrentRequestMultiplier);
        clientCount = max(clientCount, 1);

        int pendingClients = allClients.size() - queuedClients.size() - completedClients.size();
        clientCount -= pendingClients;

        for (int i = 0; i < clientCount; ) {
            HttpPageBufferClient client = queuedClients.poll();
            if (client == null) {
                // no more clients available
                return;
            }

            if (removedClients.contains(client)) {
                continue;
            }

            DataSize max = new DataSize(min(averageResponseSize * 2, maxResponseSize.toBytes()), BYTE);
            client.scheduleRequest(max);
            i++;
        }
    }

    public synchronized ListenableFuture<?> isBlocked()
    {
        if (isClosed() || isFailed() || pageBuffer.peek() != null) {
            return Futures.immediateFuture(true);
        }
        SettableFuture<?> future = SettableFuture.create();
        blockedCallers.add(future);
        return future;
    }

    private synchronized boolean addPages(List<SerializedPage> pages)
    {
        if (isClosed() || isFailed()) {
            return false;
        }

        pageBuffer.addAll(pages);

        if (!pages.isEmpty()) {
            // notify all blocked callers
            notifyBlockedCallers();
        }

        long pagesRetainedSizeInBytes = pages.stream()
                .mapToLong(SerializedPage::getRetainedSizeInBytes)
                .sum();

        bufferRetainedSizeInBytes += pagesRetainedSizeInBytes;
        maxBufferRetainedSizeInBytes = max(maxBufferRetainedSizeInBytes, bufferRetainedSizeInBytes);
        systemMemoryContext.setBytes(bufferRetainedSizeInBytes);
        successfulRequests++;

        long responseSize = pages.stream()
                .mapToLong(SerializedPage::getSizeInBytes)
                .sum();
        responseSizeExponentialMovingAverage.update(responseSize);

        return true;
    }

    private synchronized void notifyBlockedCallers()
    {
        List<SettableFuture<?>> callers = ImmutableList.copyOf(blockedCallers);
        blockedCallers.clear();
        for (SettableFuture<?> blockedCaller : callers) {
            // Notify callers in a separate thread to avoid callbacks while holding a lock
            scheduler.execute(() -> blockedCaller.set(null));
        }
    }

    private synchronized void requestComplete(HttpPageBufferClient client)
    {
        if (!queuedClients.contains(client)) {
            queuedClients.add(client);
        }
        scheduleRequestIfNecessary();
    }

    private synchronized void clientFinished(HttpPageBufferClient client)
    {
        requireNonNull(client, "client is null");
        completedClients.add(client);
        scheduleRequestIfNecessary();
    }

    private synchronized void clientFailed(HttpPageBufferClient client, Throwable cause)
    {
        // ignore failure for removed clients
        if (removedClients.contains(client)) {
            return;
        }

        // TODO: properly handle the failed vs closed state
        // it is important not to treat failures as a successful close
        if (!isClosed()) {
            failure.compareAndSet(null, cause);
            notifyBlockedCallers();
        }
    }

    private boolean isFailed()
    {
        return failure.get() != null;
    }

    private void throwIfFailed()
    {
        Throwable t = failure.get();
        if (t != null) {
            throwIfUnchecked(t);
            throw new RuntimeException(t);
        }
    }

    private class ExchangeClientCallback
            implements ClientCallback
    {
        @Override
        public boolean addPages(HttpPageBufferClient client, List<SerializedPage> pages)
        {
            requireNonNull(client, "client is null");
            requireNonNull(pages, "pages is null");
            return ExchangeClient.this.addPages(pages);
        }

        @Override
        public void requestComplete(HttpPageBufferClient client)
        {
            requireNonNull(client, "client is null");
            ExchangeClient.this.requestComplete(client);
        }

        @Override
        public void clientFinished(HttpPageBufferClient client)
        {
            ExchangeClient.this.clientFinished(client);
        }

        @Override
        public void clientFailed(HttpPageBufferClient client, Throwable cause)
        {
            requireNonNull(client, "client is null");
            requireNonNull(cause, "cause is null");

            ExchangeClient.this.clientFailed(client, cause);
        }
    }

    private static void closeQuietly(HttpPageBufferClient client)
    {
        try {
            client.close();
        }
        catch (RuntimeException e) {
            // ignored
        }
    }

    private static class ExponentialMovingAverage
    {
        private final double alpha;
        private double oldValue;

        public ExponentialMovingAverage(double alpha, long initialValue)
        {
            this.alpha = alpha;
            this.oldValue = initialValue;
        }

        public void update(long value)
        {
            double newValue = oldValue + (alpha * (value - oldValue));
            oldValue = newValue;
        }

        public long get()
        {
            return (long) oldValue;
        }
    }
}
