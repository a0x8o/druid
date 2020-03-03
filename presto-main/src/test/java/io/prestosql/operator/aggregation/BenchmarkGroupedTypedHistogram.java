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
package io.prestosql.operator.aggregation;

import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.GroupByIdBlock;
import io.prestosql.operator.aggregation.groupby.GroupByAggregationTestUtils;
import io.prestosql.operator.aggregation.histogram.HistogramGroupImplementation;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.MapType;
import io.prestosql.sql.analyzer.FeaturesConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.testng.internal.collections.Ints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.prestosql.block.BlockAssertions.createStringsBlock;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.aggregation.histogram.Histogram.NAME;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.util.StructuralTestUtil.mapType;

@OutputTimeUnit(TimeUnit.SECONDS)
//@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Warmup(iterations = 7)
@Measurement(iterations = 20)
public class BenchmarkGroupedTypedHistogram
{
    @State(Scope.Thread)
    public static class Data
    {
        @Param("10000") // larger groups => worse perf for NEW as it's more costly to track a group than with LEGACY. Tweak based on what you want to measure
        private int numGroups;
        @Param("5000") // makes sure legacy impl isn't doing trivial work
        private int rowCount;
        //        @Param({"0.0", "0.1", ".25", "0.50", ".75", "1.0"})
        @Param("0.1") // somewhat arbitrary guess, we don't know this
        private float distinctFraction;
        //        @Param({"1", "5", "50"})
        @Param("32") // size of entries--we have no idea here, could be 8 long (common in anecdotal) or longer strings
        private int rowSize;
        @Param("0.5f") // found slight benefit over 0.75, the canonical starting point
        private float mainFillRatio;
        @Param("0.5f") // found slight benefit over 0.75, the canonical starting point
        private float valueStoreFillRatio;
        // these must be manually set in each class now; the mechanism to change and test was removed; the enum was kept in case we want to revisit. Retesting showed linear was superior
        //        //        @Param({"LINEAR", "SUM_OF_COUNT", "SUM_OF_SQUARE"})
//        @Param({"LINEAR"}) // found to be best, by about 10-15%
//        private ProbeType mainProbeTyepe;
//        //        @Param({"LINEAR", "SUM_OF_COUNT", "SUM_OF_SQUARE"})
//        @Param({"LINEAR"}) // found to best
//        private ProbeType valueStoreProbeType;
//        //        @Param({"NEW"})
        @Param({"NEW", "LEGACY"})
        private HistogramGroupImplementation histogramGroupImplementation;

        private final Random random = new Random();
        private Page[] pages;
        private GroupByIdBlock[] groupByIdBlocks;
        private GroupedAccumulator groupedAccumulator;

        @Setup
        public void setUp()
        {
            pages = new Page[numGroups];
            groupByIdBlocks = new GroupByIdBlock[numGroups];

            for (int j = 0; j < numGroups; j++) {
                List<String> valueList = new ArrayList<>();

                for (int i = 0; i < rowCount; i++) {
                    // makes sure rows don't exceed rowSize
                    String str = String.valueOf(i % 10);
                    String item = IntStream.range(0, rowSize).mapToObj(x -> str).collect(Collectors.joining());
                    boolean distinctValue = random.nextDouble() < distinctFraction;

                    if (distinctValue) {
                        // produce a unique value for the histogram
                        valueList.add(j + "-" + item);
                    }
                    else {
                        valueList.add(item);
                    }
                }

                Block block = createStringsBlock(valueList);
                Page page = new Page(block);
                GroupByIdBlock groupByIdBlock = AggregationTestUtils.createGroupByIdBlock(j, page.getPositionCount());

                pages[j] = page;
                groupByIdBlocks[j] = groupByIdBlock;
            }

            InternalAggregationFunction aggregationFunction =
                    getInternalAggregationFunctionVarChar(histogramGroupImplementation);
            groupedAccumulator = createGroupedAccumulator(aggregationFunction);
        }

        private GroupedAccumulator createGroupedAccumulator(InternalAggregationFunction function)
        {
            int[] args = GroupByAggregationTestUtils.createArgs(function);

            return function.bind(Ints.asList(args), Optional.empty())
                    .createGroupedAccumulator();
        }
    }

    @Benchmark
    public GroupedAccumulator testSharedGroupWithLargeBlocksRunner(Data data)
    {
        GroupedAccumulator groupedAccumulator = data.groupedAccumulator;

        for (int i = 0; i < data.numGroups; i++) {
            GroupByIdBlock groupByIdBlock = data.groupByIdBlocks[i];
            Page page = data.pages[i];
            groupedAccumulator.addInput(groupByIdBlock, page);
        }

        return groupedAccumulator;
    }

    private static InternalAggregationFunction getInternalAggregationFunctionVarChar(HistogramGroupImplementation groupMode)
    {
        MapType mapType = mapType(VARCHAR, BIGINT);
        Metadata metadata = getMetadata(groupMode);

        return metadata.getAggregateFunctionImplementation(new Signature(
                NAME,
                AGGREGATE,
                mapType.getTypeSignature(),
                VARCHAR.getTypeSignature()));
    }

    private static Metadata getMetadata(HistogramGroupImplementation groupMode)
    {
        return createTestMetadataManager(new FeaturesConfig()
                .setHistogramGroupImplementation(groupMode));
    }

    public static void main(String[] args)
            throws RunnerException
    {
        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkGroupedTypedHistogram.class.getSimpleName() + ".*")
                .addProfiler(GCProfiler.class)
                .build();

        new Runner(options).run();
    }

    public enum ProbeType
    {
        LINEAR, SUM_OF_COUNT, SUM_OF_SQUARE
    }
}
