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
import io.prestosql.RowPagesBuilder;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.StreamingAggregationOperator.StreamingAggregationOperatorFactory;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.spi.Page;
import io.prestosql.sql.gen.JoinCompiler;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.testing.MaterializedResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.OperatorAssertion.assertOperatorEquals;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static io.prestosql.testing.TestingTaskContext.createTaskContext;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;

@Test(singleThreaded = true)
public class TestStreamingAggregationOperator
{
    private static final Metadata metadata = createTestMetadataManager();

    private static final InternalAggregationFunction LONG_SUM = metadata.getAggregateFunctionImplementation(
            new Signature("sum", AGGREGATE, BIGINT.getTypeSignature(), BIGINT.getTypeSignature()));
    private static final InternalAggregationFunction COUNT = metadata.getAggregateFunctionImplementation(
            new Signature("count", AGGREGATE, BIGINT.getTypeSignature()));

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private DriverContext driverContext;
    private StreamingAggregationOperatorFactory operatorFactory;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));

        driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();

        operatorFactory = new StreamingAggregationOperatorFactory(
                0,
                new PlanNodeId("test"),
                ImmutableList.of(BOOLEAN, VARCHAR, BIGINT),
                ImmutableList.of(VARCHAR),
                ImmutableList.of(1),
                AggregationNode.Step.SINGLE,
                ImmutableList.of(COUNT.bind(ImmutableList.of(0), Optional.empty()),
                        LONG_SUM.bind(ImmutableList.of(2), Optional.empty())),
                new JoinCompiler(createTestMetadataManager()));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void test()
    {
        RowPagesBuilder rowPagesBuilder = RowPagesBuilder.rowPagesBuilder(BOOLEAN, VARCHAR, BIGINT);
        List<Page> input = rowPagesBuilder
                .addSequencePage(3, 0, 0, 1)
                .row(true, "3", 4)
                .row(false, "3", 5)
                .pageBreak()
                .row(true, "3", 6)
                .row(false, "4", 7)
                .row(true, "4", 8)
                .row(false, "4", 9)
                .row(true, "4", 10)
                .pageBreak()
                .row(false, "5", 11)
                .row(true, "5", 12)
                .row(false, "5", 13)
                .row(true, "5", 14)
                .row(false, "5", 15)
                .pageBreak()
                .addSequencePage(3, 0, 6, 16)
                .build();

        MaterializedResult expected = resultBuilder(driverContext.getSession(), VARCHAR, BIGINT, BIGINT)
                .row("0", 1L, 1L)
                .row("1", 1L, 2L)
                .row("2", 1L, 3L)
                .row("3", 3L, 15L)
                .row("4", 4L, 34L)
                .row("5", 5L, 65L)
                .row("6", 1L, 16L)
                .row("7", 1L, 17L)
                .row("8", 1L, 18L)
                .build();

        assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }

    @Test
    public void testEmptyInput()
    {
        RowPagesBuilder rowPagesBuilder = RowPagesBuilder.rowPagesBuilder(BOOLEAN, VARCHAR, BIGINT);
        List<Page> input = rowPagesBuilder.build();

        MaterializedResult expected = resultBuilder(driverContext.getSession(), VARCHAR, BIGINT, BIGINT).build();
        assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }

    @Test
    public void testSinglePage()
    {
        RowPagesBuilder rowPagesBuilder = RowPagesBuilder.rowPagesBuilder(BOOLEAN, VARCHAR, BIGINT);
        List<Page> input = rowPagesBuilder
                .row(false, "a", 5)
                .build();

        MaterializedResult expected = resultBuilder(driverContext.getSession(), VARCHAR, BIGINT, BIGINT)
                .row("a", 1L, 5L)
                .build();
        assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }

    @Test
    public void testUniqueGroupingValues()
    {
        RowPagesBuilder rowPagesBuilder = RowPagesBuilder.rowPagesBuilder(BOOLEAN, VARCHAR, BIGINT);
        List<Page> input = rowPagesBuilder
                .addSequencePage(10, 0, 0, 0)
                .addSequencePage(10, 0, 10, 10)
                .build();

        MaterializedResult.Builder builder = resultBuilder(driverContext.getSession(), VARCHAR, BIGINT, BIGINT);
        for (int i = 0; i < 20; i++) {
            builder.row(format("%s", i), 1L, Long.valueOf(i));
        }

        assertOperatorEquals(operatorFactory, driverContext, input, builder.build());
    }

    @Test
    public void testSingleGroupingValue()
    {
        RowPagesBuilder rowPagesBuilder = RowPagesBuilder.rowPagesBuilder(BOOLEAN, VARCHAR, BIGINT);
        List<Page> input = rowPagesBuilder
                .row(true, "a", 1)
                .row(false, "a", 2)
                .row(true, "a", 3)
                .row(false, "a", 4)
                .row(true, "a", 5)
                .pageBreak()
                .row(false, "a", 6)
                .row(true, "a", 7)
                .row(false, "a", 8)
                .pageBreak()
                .pageBreak()
                .row(true, "a", 9)
                .row(false, "a", 10)
                .build();

        MaterializedResult expected = resultBuilder(driverContext.getSession(), VARCHAR, BIGINT, BIGINT)
                .row("a", 10L, 55L)
                .build();
        assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }
}
