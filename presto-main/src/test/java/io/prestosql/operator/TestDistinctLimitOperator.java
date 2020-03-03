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
import com.google.common.primitives.Ints;
import io.prestosql.RowPagesBuilder;
import io.prestosql.spi.Page;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.testing.MaterializedResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.testing.Assertions.assertGreaterThan;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.GroupByHashYieldAssertion.createPagesWithDistinctHashKeys;
import static io.prestosql.operator.GroupByHashYieldAssertion.finishOperatorWithYieldingGroupByHash;
import static io.prestosql.operator.OperatorAssertion.assertOperatorEquals;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static io.prestosql.testing.TestingTaskContext.createTaskContext;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestDistinctLimitOperator
{
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private DriverContext driverContext;
    private JoinCompiler joinCompiler;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
        driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
        joinCompiler = new JoinCompiler(createTestMetadataManager());
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @DataProvider
    public Object[][] dataType()
    {
        return new Object[][] {{VARCHAR}, {BIGINT}};
    }

    @DataProvider(name = "hashEnabledValues")
    public static Object[][] hashEnabledValuesProvider()
    {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "hashEnabledValues")
    public void testDistinctLimit(boolean hashEnabled)
    {
        RowPagesBuilder rowPagesBuilder = rowPagesBuilder(hashEnabled, Ints.asList(0), BIGINT);
        List<Page> input = rowPagesBuilder
                .addSequencePage(3, 1)
                .addSequencePage(5, 2)
                .build();

        OperatorFactory operatorFactory = new DistinctLimitOperator.DistinctLimitOperatorFactory(0, new PlanNodeId("test"), rowPagesBuilder.getTypes(), Ints.asList(0), 5, rowPagesBuilder.getHashChannel(), joinCompiler);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .row(1L)
                .row(2L)
                .row(3L)
                .row(4L)
                .row(5L)
                .build();

        assertOperatorEquals(operatorFactory, driverContext, input, expected, hashEnabled, ImmutableList.of(1));
    }

    @Test(dataProvider = "hashEnabledValues")
    public void testDistinctLimitWithPageAlignment(boolean hashEnabled)
    {
        RowPagesBuilder rowPagesBuilder = rowPagesBuilder(hashEnabled, Ints.asList(0), BIGINT);
        List<Page> input = rowPagesBuilder
                .addSequencePage(3, 1)
                .addSequencePage(3, 2)
                .build();

        OperatorFactory operatorFactory = new DistinctLimitOperator.DistinctLimitOperatorFactory(0, new PlanNodeId("test"), rowPagesBuilder.getTypes(), Ints.asList(0), 3, rowPagesBuilder.getHashChannel(), joinCompiler);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .row(1L)
                .row(2L)
                .row(3L)
                .build();

        assertOperatorEquals(operatorFactory, driverContext, input, expected, hashEnabled, ImmutableList.of(1));
    }

    @Test(dataProvider = "hashEnabledValues")
    public void testDistinctLimitValuesLessThanLimit(boolean hashEnabled)
    {
        RowPagesBuilder rowPagesBuilder = rowPagesBuilder(hashEnabled, Ints.asList(0), BIGINT);
        List<Page> input = rowPagesBuilder
                .addSequencePage(3, 1)
                .addSequencePage(3, 2)
                .build();

        OperatorFactory operatorFactory = new DistinctLimitOperator.DistinctLimitOperatorFactory(0, new PlanNodeId("test"), rowPagesBuilder.getTypes(), Ints.asList(0), 5, rowPagesBuilder.getHashChannel(), joinCompiler);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .row(1L)
                .row(2L)
                .row(3L)
                .row(4L)
                .build();

        assertOperatorEquals(operatorFactory, driverContext, input, expected, hashEnabled, ImmutableList.of(1));
    }

    @Test(dataProvider = "dataType")
    public void testMemoryReservationYield(Type type)
    {
        List<Page> input = createPagesWithDistinctHashKeys(type, 6_000, 600);

        OperatorFactory operatorFactory = new DistinctLimitOperator.DistinctLimitOperatorFactory(
                0,
                new PlanNodeId("test"),
                ImmutableList.of(type, BIGINT),
                ImmutableList.of(0),
                Integer.MAX_VALUE,
                Optional.of(1),
                joinCompiler);

        GroupByHashYieldAssertion.GroupByHashYieldResult result = finishOperatorWithYieldingGroupByHash(input, type, operatorFactory, operator -> ((DistinctLimitOperator) operator).getCapacity(), 1_400_000);
        assertGreaterThan(result.getYieldCount(), 5);
        assertGreaterThan(result.getMaxReservedBytes(), 20L << 20);
        assertEquals(result.getOutput().stream().mapToInt(Page::getPositionCount).sum(), 6_000 * 600);
    }
}
