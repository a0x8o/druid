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
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.memory.context.MemoryTrackingContext;
import io.prestosql.metadata.Split;
import io.prestosql.operator.WorkProcessor.Transformation;
import io.prestosql.operator.WorkProcessor.TransformationState;
import io.prestosql.operator.WorkProcessorAssertion.Transform;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.UpdatablePageSource;
import io.prestosql.sql.planner.plan.PlanNodeId;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.execution.Lifespan.taskWide;
import static io.prestosql.operator.WorkProcessorAssertion.transformationFrom;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.testing.TestingSplit.createLocalSplit;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestWorkProcessorPipelineSourceOperator
{
    private ScheduledExecutorService scheduledExecutor;

    @BeforeClass
    public void setUp()
    {
        scheduledExecutor = newSingleThreadScheduledExecutor();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        scheduledExecutor.shutdownNow();
    }

    @Test(timeOut = 5000)
    public void testWorkProcessorPipelineSourceOperator()
            throws InterruptedException
    {
        Split split = createSplit();

        Page page1 = createPage(1);
        Page page2 = createPage(2);
        Page page3 = createPage(3);
        Page page4 = createPage(4);
        Page page5 = createPage(5);

        Transformation<Split, Page> sourceOperatorPages = transformationFrom(ImmutableList.of(
                Transform.of(Optional.of(split), TransformationState.ofResult(page1, false)),
                Transform.of(Optional.of(split), TransformationState.ofResult(page2, true))));

        SettableFuture<?> firstBlockedFuture = SettableFuture.create();
        Transformation<Page, Page> firstOperatorPages = transformationFrom(ImmutableList.of(
                Transform.of(Optional.of(page1), TransformationState.blocked(firstBlockedFuture)),
                Transform.of(Optional.of(page1), TransformationState.ofResult(page3, true)),
                Transform.of(Optional.of(page2), TransformationState.ofResult(page4, false)),
                Transform.of(Optional.of(page2), TransformationState.finished())),
                (left, right) -> left.getPositionCount() == right.getPositionCount());

        SettableFuture<?> secondBlockedFuture = SettableFuture.create();
        Transformation<Page, Page> secondOperatorPages = transformationFrom(ImmutableList.of(
                Transform.of(Optional.of(page3), TransformationState.ofResult(page5, true)),
                Transform.of(Optional.of(page4), TransformationState.needsMoreData()),
                Transform.of(Optional.empty(), TransformationState.blocked(secondBlockedFuture))),
                (left, right) -> left.getPositionCount() == right.getPositionCount());

        TestWorkProcessorSourceOperatorFactory sourceOperatorFactory = new TestWorkProcessorSourceOperatorFactory(
                1,
                new PlanNodeId("1"),
                sourceOperatorPages);
        TestWorkProcessorOperatorFactory firstOperatorFactory = new TestWorkProcessorOperatorFactory(2, firstOperatorPages);
        TestWorkProcessorOperatorFactory secondOperatorFactory = new TestWorkProcessorOperatorFactory(3, secondOperatorPages);

        SourceOperatorFactory pipelineOperatorFactory = (SourceOperatorFactory) getOnlyElement(WorkProcessorPipelineSourceOperator.convertOperators(
                99,
                ImmutableList.of(sourceOperatorFactory, firstOperatorFactory, secondOperatorFactory)));

        DriverContext driverContext = TestingOperatorContext.create(scheduledExecutor).getDriverContext();
        SourceOperator pipelineOperator = pipelineOperatorFactory.createOperator(driverContext);

        // make sure WorkProcessorOperator memory is accounted for
        sourceOperatorFactory.sourceOperator.memoryTrackingContext.localUserMemoryContext().setBytes(123);
        assertEquals(driverContext.getMemoryUsage(), 123);

        assertNull(pipelineOperator.getOutput());
        assertFalse(pipelineOperator.isBlocked().isDone());
        // blocking on splits should not account for blocked time for any WorkProcessorOperator
        pipelineOperator.getOperatorContext().getNestedOperatorStats().forEach(
                operatorStats -> assertEquals(operatorStats.getBlockedWall().toMillis(), 0));

        pipelineOperator.addSplit(split);
        assertTrue(pipelineOperator.isBlocked().isDone());

        assertNull(pipelineOperator.getOutput());
        assertFalse(pipelineOperator.isBlocked().isDone());

        Thread.sleep(100);
        firstBlockedFuture.set(null);
        assertTrue(pipelineOperator.isBlocked().isDone());

        // blocking of first WorkProcessorOperator should be accounted for in stats
        List<OperatorStats> operatorStats = pipelineOperator.getOperatorContext().getNestedOperatorStats();
        assertEquals(operatorStats.get(0).getBlockedWall().toMillis(), 0);
        assertTrue(operatorStats.get(1).getBlockedWall().toMillis() > 0);
        assertEquals(operatorStats.get(2).getBlockedWall().toMillis(), 0);

        assertEquals(getTestingOperatorInfo(operatorStats.get(1)).count, 2);
        assertEquals(getTestingOperatorInfo(operatorStats.get(2)).count, 2);

        assertEquals(pipelineOperator.getOutput().getPositionCount(), page5.getPositionCount());

        // sourceOperator should yield
        driverContext.getYieldSignal().forceYieldForTesting();
        assertNull(pipelineOperator.getOutput());
        driverContext.getYieldSignal().resetYieldForTesting();

        // firstOperatorPages should finish. This should cause sourceOperator and firstOperatorPages to close.
        // secondOperatorPages should block
        assertNull(pipelineOperator.getOutput());
        assertFalse(pipelineOperator.isBlocked().isDone());
        assertTrue(sourceOperatorFactory.sourceOperator.closed);
        assertTrue(firstOperatorFactory.operator.closed);
        assertFalse(secondOperatorFactory.operator.closed);

        // first operator should return final operator info
        assertEquals(getTestingOperatorInfo(operatorStats.get(1)).count, 3);
        assertEquals(getTestingOperatorInfo(operatorStats.get(2)).count, 2);
        operatorStats = pipelineOperator.getOperatorContext().getNestedOperatorStats();
        assertEquals(getTestingOperatorInfo(operatorStats.get(1)).count, 3);
        assertEquals(getTestingOperatorInfo(operatorStats.get(2)).count, 3);

        // cause early operator finish
        pipelineOperator.finish();

        // operator is still blocked on secondBlockedFuture
        assertFalse(pipelineOperator.isFinished());
        assertTrue(secondOperatorFactory.operator.closed);

        secondBlockedFuture.set(null);
        assertTrue(pipelineOperator.isBlocked().isDone());
        assertNull(pipelineOperator.getOutput());
        assertTrue(pipelineOperator.isFinished());

        // assert number of processed rows is correct
        operatorStats = pipelineOperator.getOperatorContext().getNestedOperatorStats();
        assertEquals(operatorStats.get(0).getOutputPositions(), 3);
        assertEquals(operatorStats.get(1).getInputPositions(), 3);
        assertEquals(operatorStats.get(0).getOutputDataSize().toBytes(), 27);
        assertEquals(operatorStats.get(1).getInputDataSize().toBytes(), 27);

        assertEquals(operatorStats.get(1).getOutputPositions(), 7);
        assertEquals(operatorStats.get(2).getInputPositions(), 7);
        assertEquals(operatorStats.get(1).getOutputDataSize().toBytes(), 63);
        assertEquals(operatorStats.get(2).getInputDataSize().toBytes(), 63);

        assertEquals(operatorStats.get(2).getOutputPositions(), 5);
        assertEquals(operatorStats.get(2).getOutputDataSize().toBytes(), 45);

        // assert source operator input stats are correct
        OperatorStats sourceOperatorStats = operatorStats.get(0);
        assertEquals(sourceOperatorStats.getPhysicalInputDataSize(), new DataSize(1, BYTE));
        assertEquals(sourceOperatorStats.getPhysicalInputPositions(), 2);

        assertEquals(sourceOperatorStats.getInternalNetworkInputDataSize(), new DataSize(3, BYTE));
        assertEquals(sourceOperatorStats.getInternalNetworkInputPositions(), 4);

        assertEquals(sourceOperatorStats.getInputDataSize(), new DataSize(5, BYTE));
        assertEquals(sourceOperatorStats.getInputPositions(), 6);

        assertEquals(sourceOperatorStats.getAddInputWall(), new Duration(7, NANOSECONDS));

        // pipeline operator input stats should match source WorkProcessorOperator stats
        OperatorStats pipelineOperatorStats = pipelineOperator.getOperatorContext().getOperatorStats();
        assertEquals(sourceOperatorStats.getPhysicalInputDataSize(), pipelineOperatorStats.getPhysicalInputDataSize());
        assertEquals(sourceOperatorStats.getPhysicalInputPositions(), pipelineOperatorStats.getPhysicalInputPositions());

        assertEquals(sourceOperatorStats.getInternalNetworkInputDataSize(), pipelineOperatorStats.getInternalNetworkInputDataSize());
        assertEquals(sourceOperatorStats.getInternalNetworkInputPositions(), pipelineOperatorStats.getInternalNetworkInputPositions());

        assertEquals(sourceOperatorStats.getInputDataSize(), pipelineOperatorStats.getInputDataSize());
        assertEquals(sourceOperatorStats.getInputPositions(), pipelineOperatorStats.getInputPositions());

        assertEquals(sourceOperatorStats.getAddInputWall(), pipelineOperatorStats.getAddInputWall());
    }

    private TestOperatorInfo getTestingOperatorInfo(OperatorStats operatorStats)
    {
        return (TestOperatorInfo) operatorStats.getInfo();
    }

    private Split createSplit()
    {
        return new Split(
                new CatalogName("catalog_name"),
                createLocalSplit(),
                taskWide());
    }

    private Page createPage(int pageNumber)
    {
        return getOnlyElement(rowPagesBuilder(BIGINT).addSequencePage(pageNumber, pageNumber).build());
    }

    private class TestWorkProcessorSourceOperatorFactory
            implements WorkProcessorSourceOperatorFactory, SourceOperatorFactory
    {
        final int operatorId;
        final PlanNodeId sourceId;
        final Transformation<Split, Page> transformation;

        TestWorkProcessorSourceOperator sourceOperator;

        TestWorkProcessorSourceOperatorFactory(int operatorId, PlanNodeId sourceId, Transformation<Split, Page> transformation)
        {
            this.operatorId = operatorId;
            this.sourceId = sourceId;
            this.transformation = transformation;
        }

        @Override
        public int getOperatorId()
        {
            return operatorId;
        }

        @Override
        public PlanNodeId getSourceId()
        {
            return sourceId;
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return sourceId;
        }

        @Override
        public String getOperatorType()
        {
            return TestWorkProcessorSourceOperatorFactory.class.getSimpleName();
        }

        @Override
        public WorkProcessorSourceOperator create(Session session, MemoryTrackingContext memoryTrackingContext, DriverYieldSignal yieldSignal, WorkProcessor<Split> splits)
        {
            assertNull(sourceOperator, "source operator already created");
            sourceOperator = new TestWorkProcessorSourceOperator(
                    splits
                            .transform(transformation)
                            .yielding(yieldSignal::isSet),
                    memoryTrackingContext);
            return sourceOperator;
        }

        @Override
        public SourceOperator createOperator(DriverContext driverContext)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void noMoreOperators()
        {
            throw new UnsupportedOperationException();
        }
    }

    private class TestWorkProcessorSourceOperator
            implements WorkProcessorSourceOperator
    {
        final WorkProcessor<Page> pages;

        boolean closed;
        MemoryTrackingContext memoryTrackingContext;

        TestWorkProcessorSourceOperator(WorkProcessor<Page> pages, MemoryTrackingContext memoryTrackingContext)
        {
            this.pages = pages;
            this.memoryTrackingContext = memoryTrackingContext;
        }

        @Override
        public Supplier<Optional<UpdatablePageSource>> getUpdatablePageSourceSupplier()
        {
            return Optional::empty;
        }

        @Override
        public DataSize getPhysicalInputDataSize()
        {
            return new DataSize(1, BYTE);
        }

        @Override
        public long getPhysicalInputPositions()
        {
            return 2;
        }

        @Override
        public DataSize getInternalNetworkInputDataSize()
        {
            return new DataSize(3, BYTE);
        }

        @Override
        public long getInternalNetworkPositions()
        {
            return 4;
        }

        @Override
        public DataSize getInputDataSize()
        {
            return new DataSize(5, BYTE);
        }

        @Override
        public long getInputPositions()
        {
            return 6;
        }

        @Override
        public Duration getReadTime()
        {
            return new Duration(7, NANOSECONDS);
        }

        @Override
        public WorkProcessor<Page> getOutputPages()
        {
            return pages;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private class TestWorkProcessorOperatorFactory
            implements WorkProcessorOperatorFactory, OperatorFactory
    {
        final int operatorId;
        final Transformation<Page, Page> transformation;

        TestWorkProcessorOperator operator;

        TestWorkProcessorOperatorFactory(int operatorId, Transformation<Page, Page> transformation)
        {
            this.operatorId = operatorId;
            this.transformation = transformation;
        }

        @Override
        public int getOperatorId()
        {
            return operatorId;
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return new PlanNodeId("test-operator");
        }

        @Override
        public String getOperatorType()
        {
            return TestWorkProcessorOperatorFactory.class.getSimpleName();
        }

        @Override
        public WorkProcessorOperator create(Session session, MemoryTrackingContext memoryTrackingContext, DriverYieldSignal yieldSignal, WorkProcessor<Page> sourcePages)
        {
            assertNull(operator, "source operator already created");
            operator = new TestWorkProcessorOperator(sourcePages.transform(transformation));
            return operator;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void noMoreOperators()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public OperatorFactory duplicate()
        {
            throw new UnsupportedOperationException();
        }
    }

    private class TestWorkProcessorOperator
            implements WorkProcessorOperator
    {
        final WorkProcessor<Page> pages;
        final TestOperatorInfo operatorInfo = new TestOperatorInfo();

        boolean closed;

        TestWorkProcessorOperator(WorkProcessor<Page> pages)
        {
            this.pages = pages;
        }

        @Override
        public Optional<OperatorInfo> getOperatorInfo()
        {
            operatorInfo.count++;
            return Optional.of(operatorInfo);
        }

        @Override
        public WorkProcessor<Page> getOutputPages()
        {
            return pages;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private class TestOperatorInfo
            implements OperatorInfo
    {
        int count;
    }
}
