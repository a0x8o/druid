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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.Session;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.memory.context.MemoryTrackingContext;
import io.prestosql.operator.SetBuilderOperator.SetSupplier;
import io.prestosql.operator.WorkProcessor.TransformationState;
import io.prestosql.operator.WorkProcessorOperatorAdapter.AdapterWorkProcessorOperator;
import io.prestosql.operator.WorkProcessorOperatorAdapter.AdapterWorkProcessorOperatorFactory;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.PlanNodeId;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.concurrent.MoreFutures.checkSuccess;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.prestosql.operator.WorkProcessor.TransformationState.blocked;
import static io.prestosql.operator.WorkProcessor.TransformationState.finished;
import static io.prestosql.operator.WorkProcessor.TransformationState.ofResult;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static java.util.Objects.requireNonNull;

public class HashSemiJoinOperator
        implements AdapterWorkProcessorOperator
{
    public static class HashSemiJoinOperatorFactory
            implements OperatorFactory, AdapterWorkProcessorOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final SetSupplier setSupplier;
        private final List<Type> probeTypes;
        private final int probeJoinChannel;
        private final Optional<Integer> probeJoinHashChannel;
        private boolean closed;

        public HashSemiJoinOperatorFactory(int operatorId, PlanNodeId planNodeId, SetSupplier setSupplier, List<? extends Type> probeTypes, int probeJoinChannel, Optional<Integer> probeJoinHashChannel)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.setSupplier = setSupplier;
            this.probeTypes = ImmutableList.copyOf(probeTypes);
            checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");
            this.probeJoinChannel = probeJoinChannel;
            this.probeJoinHashChannel = probeJoinHashChannel;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, HashSemiJoinOperator.class.getSimpleName());
            return new WorkProcessorOperatorAdapter(operatorContext, this);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new HashSemiJoinOperatorFactory(operatorId, planNodeId, setSupplier, probeTypes, probeJoinChannel, probeJoinHashChannel);
        }

        @Override
        public AdapterWorkProcessorOperator create(Session session, MemoryTrackingContext memoryTrackingContext, DriverYieldSignal yieldSignal)
        {
            return new HashSemiJoinOperator(Optional.empty(), setSupplier, probeJoinChannel, probeJoinHashChannel, memoryTrackingContext);
        }

        @Override
        public int getOperatorId()
        {
            return operatorId;
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return planNodeId;
        }

        @Override
        public String getOperatorType()
        {
            return HashSemiJoinOperator.class.getSimpleName();
        }

        @Override
        public WorkProcessorOperator create(Session session, MemoryTrackingContext memoryTrackingContext, DriverYieldSignal yieldSignal, WorkProcessor<Page> sourcePages)
        {
            return new HashSemiJoinOperator(Optional.of(sourcePages), setSupplier, probeJoinChannel, probeJoinHashChannel, memoryTrackingContext);
        }
    }

    private final WorkProcessor<Page> pages;
    private final PageBuffer pageBuffer = new PageBuffer();

    public HashSemiJoinOperator(
            Optional<WorkProcessor<Page>> sourcePages,
            SetSupplier channelSetFuture,
            int probeJoinChannel,
            Optional<Integer> probeHashChannel,
            MemoryTrackingContext memoryTrackingContext)
    {
        pages = sourcePages.orElse(pageBuffer.pages())
                .transform(new SemiJoinPages(
                        channelSetFuture,
                        probeJoinChannel,
                        probeHashChannel,
                        requireNonNull(memoryTrackingContext, "memoryTrackingContext is null").aggregateUserMemoryContext()));
    }

    @Override
    public void finish()
    {
        pageBuffer.finish();
    }

    @Override
    public boolean needsInput()
    {
        return pageBuffer.isEmpty() && !pageBuffer.isFinished();
    }

    @Override
    public void addInput(Page page)
    {
        pageBuffer.add(page);
    }

    @Override
    public WorkProcessor<Page> getOutputPages()
    {
        return pages;
    }

    @Override
    public void close()
            throws Exception
    {
    }

    private class SemiJoinPages
            implements WorkProcessor.Transformation<Page, Page>
    {
        private final int probeJoinChannel;
        private final ListenableFuture<ChannelSet> channelSetFuture;
        private final Optional<Integer> probeHashChannel;
        private final LocalMemoryContext localMemoryContext;

        @Nullable
        private ChannelSet channelSet;

        public SemiJoinPages(SetSupplier channelSetFuture, int probeJoinChannel, Optional<Integer> probeHashChannel, AggregatedMemoryContext aggregatedMemoryContext)
        {
            checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");

            this.channelSetFuture = requireNonNull(channelSetFuture, "hashProvider is null").getChannelSet();
            this.probeJoinChannel = probeJoinChannel;
            this.probeHashChannel = requireNonNull(probeHashChannel, "hashChannel is null");
            this.localMemoryContext = requireNonNull(aggregatedMemoryContext, "aggregatedMemoryContext is null").newLocalMemoryContext(SemiJoinPages.class.getSimpleName());
        }

        @Override
        public TransformationState<Page> process(Page inputPage)
        {
            if (inputPage == null) {
                return finished();
            }

            if (channelSet == null) {
                if (!channelSetFuture.isDone()) {
                    // This will materialize page but it shouldn't matter for the first page
                    localMemoryContext.setBytes(inputPage.getSizeInBytes());
                    return blocked(channelSetFuture);
                }
                checkSuccess(channelSetFuture, "ChannelSet building failed");
                channelSet = getFutureValue(channelSetFuture);
                localMemoryContext.setBytes(0);
            }

            // create the block builder for the new boolean column
            // we know the exact size required for the block
            BlockBuilder blockBuilder = BOOLEAN.createFixedSizeBlockBuilder(inputPage.getPositionCount());

            Page probeJoinPage = new Page(inputPage.getBlock(probeJoinChannel));
            Optional<Block> hashBlock = probeHashChannel.map(inputPage::getBlock);

            // update hashing strategy to use probe cursor
            for (int position = 0; position < inputPage.getPositionCount(); position++) {
                if (probeJoinPage.getBlock(0).isNull(position)) {
                    if (channelSet.isEmpty()) {
                        BOOLEAN.writeBoolean(blockBuilder, false);
                    }
                    else {
                        blockBuilder.appendNull();
                    }
                }
                else {
                    boolean contains;
                    if (hashBlock.isPresent()) {
                        long rawHash = BIGINT.getLong(hashBlock.get(), position);
                        contains = channelSet.contains(position, probeJoinPage, rawHash);
                    }
                    else {
                        contains = channelSet.contains(position, probeJoinPage);
                    }
                    if (!contains && channelSet.containsNull()) {
                        blockBuilder.appendNull();
                    }
                    else {
                        BOOLEAN.writeBoolean(blockBuilder, contains);
                    }
                }
            }
            // add the new boolean column to the page
            return ofResult(inputPage.appendColumn(blockBuilder.build()));
        }
    }
}
