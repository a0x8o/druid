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
import io.airlift.units.DataSize;
import io.prestosql.SequencePageBuilder;
import io.prestosql.block.BlockAssertions;
import io.prestosql.connector.CatalogName;
import io.prestosql.execution.Lifespan;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.Split;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.operator.index.PageRecordSet;
import io.prestosql.operator.project.CursorProcessor;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.operator.project.TestPageProcessor.LazyPagePageProjection;
import io.prestosql.operator.project.TestPageProcessor.SelectAllFilter;
import io.prestosql.operator.scalar.AbstractTestFunctions;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LazyBlock;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.FixedPageSource;
import io.prestosql.spi.connector.RecordPageSource;
import io.prestosql.sql.gen.ExpressionCompiler;
import io.prestosql.sql.gen.PageFunctionCompiler;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.sql.relational.RowExpression;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.TestingSplit;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.Unit.KILOBYTE;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.block.BlockAssertions.toValues;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.metadata.Signature.internalScalarFunction;
import static io.prestosql.operator.OperatorAssertion.toMaterializedResult;
import static io.prestosql.operator.PageAssertions.assertPageEquals;
import static io.prestosql.operator.project.PageProcessor.MAX_BATCH_SIZE;
import static io.prestosql.spi.function.OperatorType.EQUAL;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.relational.Expressions.call;
import static io.prestosql.sql.relational.Expressions.constant;
import static io.prestosql.sql.relational.Expressions.field;
import static io.prestosql.testing.TestingHandles.TEST_TABLE_HANDLE;
import static io.prestosql.testing.TestingTaskContext.createTaskContext;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestScanFilterAndProjectOperator
        extends AbstractTestFunctions
{
    private final Metadata metadata = createTestMetadataManager();
    private final ExpressionCompiler expressionCompiler = new ExpressionCompiler(metadata, new PageFunctionCompiler(metadata, 0));
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    public TestScanFilterAndProjectOperator()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @Test
    public void testPageSource()
    {
        final Page input = SequencePageBuilder.createSequencePage(ImmutableList.of(VARCHAR), 10_000, 0);
        DriverContext driverContext = newDriverContext();

        List<RowExpression> projections = ImmutableList.of(field(0, VARCHAR));
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.empty(), projections, "key");
        Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(Optional.empty(), projections);

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new FixedPageSource(ImmutableList.of(input)),
                cursorProcessor,
                pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(VARCHAR),
                new DataSize(0, BYTE),
                0);

        SourceOperator operator = factory.createOperator(driverContext);
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        MaterializedResult expected = toMaterializedResult(driverContext.getSession(), ImmutableList.of(VARCHAR), ImmutableList.of(input));
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(VARCHAR), toPages(operator));

        assertEquals(actual.getRowCount(), expected.getRowCount());
        assertEquals(actual, expected);
    }

    @Test
    public void testPageSourceMergeOutput()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(100, 0)
                .addSequencePage(100, 0)
                .addSequencePage(100, 0)
                .addSequencePage(100, 0)
                .build();

        RowExpression filter = call(
                Signature.internalOperator(EQUAL, BOOLEAN.getTypeSignature(), ImmutableList.of(BIGINT.getTypeSignature(), BIGINT.getTypeSignature())),
                BOOLEAN,
                field(0, BIGINT),
                constant(10L, BIGINT));
        List<RowExpression> projections = ImmutableList.of(field(0, BIGINT));
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.of(filter), projections, "key");
        Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(Optional.of(filter), projections);

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new FixedPageSource(input),
                cursorProcessor,
                pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(BIGINT),
                new DataSize(64, KILOBYTE),
                2);

        SourceOperator operator = factory.createOperator(newDriverContext());
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        List<Page> actual = toPages(operator);
        assertEquals(actual.size(), 1);

        List<Page> expected = rowPagesBuilder(BIGINT)
                .row(10L)
                .row(10L)
                .row(10L)
                .row(10L)
                .build();

        assertPageEquals(ImmutableList.of(BIGINT), actual.get(0), expected.get(0));
    }

    @Test
    public void testPageSourceLazyLoad()
    {
        Block inputBlock = BlockAssertions.createLongSequenceBlock(0, 100);
        // If column 1 is loaded, test will fail
        Page input = new Page(100, inputBlock, new LazyBlock(100, lazyBlock -> {
            throw new AssertionError("Lazy block should not be loaded");
        }));
        DriverContext driverContext = newDriverContext();

        List<RowExpression> projections = ImmutableList.of(field(0, VARCHAR));
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.empty(), projections, "key");
        PageProcessor pageProcessor = new PageProcessor(Optional.of(new SelectAllFilter()), ImmutableList.of(new LazyPagePageProjection()));

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new SinglePagePageSource(input),
                cursorProcessor,
                () -> pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(BIGINT),
                new DataSize(0, BYTE),
                0);

        SourceOperator operator = factory.createOperator(driverContext);
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        MaterializedResult expected = toMaterializedResult(driverContext.getSession(), ImmutableList.of(BIGINT), ImmutableList.of(new Page(inputBlock)));
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(BIGINT), toPages(operator));

        assertEquals(actual.getRowCount(), expected.getRowCount());
        assertEquals(actual, expected);
    }

    @Test
    public void testRecordCursorSource()
    {
        final Page input = SequencePageBuilder.createSequencePage(ImmutableList.of(VARCHAR), 10_000, 0);
        DriverContext driverContext = newDriverContext();

        List<RowExpression> projections = ImmutableList.of(field(0, VARCHAR));
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.empty(), projections, "key");
        Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(Optional.empty(), projections);

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new RecordPageSource(new PageRecordSet(ImmutableList.of(VARCHAR), input)),
                cursorProcessor,
                pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(VARCHAR),
                new DataSize(0, BYTE),
                0);

        SourceOperator operator = factory.createOperator(driverContext);
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        MaterializedResult expected = toMaterializedResult(driverContext.getSession(), ImmutableList.of(VARCHAR), ImmutableList.of(input));
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(VARCHAR), toPages(operator));

        assertEquals(actual.getRowCount(), expected.getRowCount());
        assertEquals(actual, expected);
    }

    @Test
    public void testPageYield()
    {
        int totalRows = 1000;
        Page input = SequencePageBuilder.createSequencePage(ImmutableList.of(BIGINT), totalRows, 1);
        DriverContext driverContext = newDriverContext();

        // 20 columns; each column is associated with a function that will force yield per projection
        int totalColumns = 20;
        ImmutableList.Builder<SqlScalarFunction> functions = ImmutableList.builder();
        for (int i = 0; i < totalColumns; i++) {
            functions.add(new GenericLongFunction("page_col" + i, value -> {
                driverContext.getYieldSignal().forceYieldForTesting();
                return value;
            }));
        }
        Metadata metadata = functionAssertions.getMetadata();
        metadata.addFunctions(functions.build());

        // match each column with a projection
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(metadata, new PageFunctionCompiler(metadata, 0));
        ImmutableList.Builder<RowExpression> projections = ImmutableList.builder();
        for (int i = 0; i < totalColumns; i++) {
            projections.add(call(internalScalarFunction("generic_long_page_col" + i, BIGINT.getTypeSignature(), ImmutableList.of(BIGINT.getTypeSignature())), BIGINT, field(0, BIGINT)));
        }
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.empty(), projections.build(), "key");
        Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(Optional.empty(), projections.build(), MAX_BATCH_SIZE);

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new FixedPageSource(ImmutableList.of(input)),
                cursorProcessor,
                pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(BIGINT),
                new DataSize(0, BYTE),
                0);

        SourceOperator operator = factory.createOperator(driverContext);
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        // In the below loop we yield for every cell: 20 X 1000 times
        // Currently we don't check for the yield signal in the generated projection loop, we only check for the yield signal
        // in the PageProcessor.PositionsPageProcessorIterator::computeNext() method. Therefore, after 20 calls we will have
        // exactly 20 blocks (one for each column) and the PageProcessor will be able to create a Page out of it.
        for (int i = 1; i <= totalRows * totalColumns; i++) {
            driverContext.getYieldSignal().setWithDelay(SECONDS.toNanos(1000), driverContext.getYieldExecutor());
            Page page = operator.getOutput();
            if (i == totalColumns) {
                assertNotNull(page);
                assertEquals(page.getPositionCount(), totalRows);
                assertEquals(page.getChannelCount(), totalColumns);
                for (int j = 0; j < totalColumns; j++) {
                    assertEquals(toValues(BIGINT, page.getBlock(j)), toValues(BIGINT, input.getBlock(0)));
                }
            }
            else {
                assertNull(page);
            }
            driverContext.getYieldSignal().reset();
        }
    }

    @Test
    public void testRecordCursorYield()
    {
        // create a generic long function that yields for projection on every row
        // verify we will yield #row times totally

        // create a table with 15 rows
        int length = 15;
        Page input = SequencePageBuilder.createSequencePage(ImmutableList.of(BIGINT), length, 0);
        DriverContext driverContext = newDriverContext();

        // set up generic long function with a callback to force yield
        Metadata metadata = functionAssertions.getMetadata();
        metadata.addFunctions(ImmutableList.of(new GenericLongFunction("record_cursor", value -> {
            driverContext.getYieldSignal().forceYieldForTesting();
            return value;
        })));
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(metadata, new PageFunctionCompiler(metadata, 0));

        List<RowExpression> projections = ImmutableList.of(call(
                internalScalarFunction("generic_long_record_cursor", BIGINT.getTypeSignature(), ImmutableList.of(BIGINT.getTypeSignature())),
                BIGINT,
                field(0, BIGINT)));
        Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(Optional.empty(), projections, "key");
        Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(Optional.empty(), projections);

        ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory factory = new ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory(
                0,
                new PlanNodeId("test"),
                new PlanNodeId("0"),
                (session, split, table, columns, dynamicFilter) -> new RecordPageSource(new PageRecordSet(ImmutableList.of(BIGINT), input)),
                cursorProcessor,
                pageProcessor,
                TEST_TABLE_HANDLE,
                ImmutableList.of(),
                null,
                ImmutableList.of(BIGINT),
                new DataSize(0, BYTE),
                0);

        SourceOperator operator = factory.createOperator(driverContext);
        operator.addSplit(new Split(new CatalogName("test"), TestingSplit.createLocalSplit(), Lifespan.taskWide()));
        operator.noMoreSplits();

        // start driver; get null value due to yield for the first 15 times
        for (int i = 0; i < length; i++) {
            driverContext.getYieldSignal().setWithDelay(SECONDS.toNanos(1000), driverContext.getYieldExecutor());
            assertNull(operator.getOutput());
            driverContext.getYieldSignal().reset();
        }

        // the 16th yield is not going to prevent the operator from producing a page
        driverContext.getYieldSignal().setWithDelay(SECONDS.toNanos(1000), driverContext.getYieldExecutor());
        Page output = operator.getOutput();
        driverContext.getYieldSignal().reset();
        assertNotNull(output);
        assertEquals(toValues(BIGINT, output.getBlock(0)), toValues(BIGINT, input.getBlock(0)));
    }

    private static List<Page> toPages(Operator operator)
    {
        ImmutableList.Builder<Page> outputPages = ImmutableList.builder();

        // read output until input is needed or operator is finished
        int nullPages = 0;
        while (!operator.isFinished()) {
            Page outputPage = operator.getOutput();
            if (outputPage == null) {
                // break infinite loop due to null pages
                assertTrue(nullPages < 1_000_000, "Too many null pages; infinite loop?");
                nullPages++;
            }
            else {
                outputPages.add(outputPage);
                nullPages = 0;
            }
        }

        return outputPages.build();
    }

    private DriverContext newDriverContext()
    {
        return createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
    }

    public static class SinglePagePageSource
            implements ConnectorPageSource
    {
        private Page page;

        public SinglePagePageSource(Page page)
        {
            this.page = page;
        }

        @Override
        public void close()
        {
            page = null;
        }

        @Override
        public long getCompletedBytes()
        {
            return 0;
        }

        @Override
        public long getReadTimeNanos()
        {
            return 0;
        }

        @Override
        public long getSystemMemoryUsage()
        {
            return 0;
        }

        @Override
        public boolean isFinished()
        {
            return page == null;
        }

        @Override
        public Page getNextPage()
        {
            Page page = this.page;
            this.page = null;
            return page;
        }
    }
}
