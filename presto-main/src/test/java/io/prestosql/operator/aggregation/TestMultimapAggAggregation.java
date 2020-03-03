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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.primitives.Ints;
import io.prestosql.RowPageBuilder;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.aggregation.groupby.AggregationTestInput;
import io.prestosql.operator.aggregation.groupby.AggregationTestInputBuilder;
import io.prestosql.operator.aggregation.groupby.AggregationTestOutput;
import io.prestosql.operator.aggregation.groupby.GroupByAggregationTestUtils;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.aggregation.AggregationTestUtils.assertAggregation;
import static io.prestosql.operator.aggregation.multimapagg.MultimapAggregationFunction.NAME;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.util.StructuralTestUtil.mapType;
import static org.testng.Assert.assertTrue;

public class TestMultimapAggAggregation
{
    private static final Metadata metadata = createTestMetadataManager();

    @Test
    public void testSingleValueMap()
    {
        testMultimapAgg(DOUBLE, ImmutableList.of(1.0), VARCHAR, ImmutableList.of("a"));
        testMultimapAgg(VARCHAR, ImmutableList.of("a"), BIGINT, ImmutableList.of(1L));
    }

    @Test
    public void testMultiValueMap()
    {
        testMultimapAgg(DOUBLE, ImmutableList.of(1.0, 1.0, 1.0), VARCHAR, ImmutableList.of("a", "b", "c"));
        testMultimapAgg(DOUBLE, ImmutableList.of(1.0, 1.0, 2.0), VARCHAR, ImmutableList.of("a", "b", "c"));
    }

    @Test
    public void testOrderValueMap()
    {
        testMultimapAgg(VARCHAR, ImmutableList.of("a", "a", "a"), BIGINT, ImmutableList.of(1L, 2L, 3L));
        testMultimapAgg(VARCHAR, ImmutableList.of("a", "a", "a"), BIGINT, ImmutableList.of(2L, 1L, 3L));
        testMultimapAgg(VARCHAR, ImmutableList.of("a", "a", "a"), BIGINT, ImmutableList.of(3L, 2L, 1L));
    }

    @Test
    public void testDuplicateValueMap()
    {
        testMultimapAgg(VARCHAR, ImmutableList.of("a", "a", "a"), BIGINT, ImmutableList.of(1L, 1L, 1L));
        testMultimapAgg(VARCHAR, ImmutableList.of("a", "b", "a", "b", "c"), BIGINT, ImmutableList.of(1L, 1L, 1L, 1L, 1L));
    }

    @Test
    public void testNullMap()
    {
        testMultimapAgg(DOUBLE, ImmutableList.<Double>of(), VARCHAR, ImmutableList.<String>of());
    }

    @Test
    public void testDoubleMapMultimap()
    {
        Type mapType = mapType(VARCHAR, BIGINT);
        List<Double> expectedKeys = ImmutableList.of(1.0, 2.0, 3.0);
        List<Map<String, Long>> expectedValues = ImmutableList.of(ImmutableMap.of("a", 1L), ImmutableMap.of("b", 2L, "c", 3L, "d", 4L), ImmutableMap.of("a", 1L));

        testMultimapAgg(DOUBLE, expectedKeys, mapType, expectedValues);
    }

    @Test
    public void testDoubleArrayMultimap()
    {
        Type arrayType = new ArrayType(VARCHAR);
        List<Double> expectedKeys = ImmutableList.of(1.0, 2.0, 3.0);
        List<List<String>> expectedValues = ImmutableList.of(ImmutableList.of("a", "b"), ImmutableList.of("c"), ImmutableList.of("d", "e", "f"));

        testMultimapAgg(DOUBLE, expectedKeys, arrayType, expectedValues);
    }

    @Test
    public void testDoubleRowMap()
    {
        RowType innerRowType = RowType.from(ImmutableList.of(
                RowType.field("f1", BIGINT),
                RowType.field("f2", DOUBLE)));
        testMultimapAgg(DOUBLE, ImmutableList.of(1.0, 2.0, 3.0), innerRowType, ImmutableList.of(ImmutableList.of(1L, 1.0), ImmutableList.of(2L, 2.0), ImmutableList.of(3L, 3.0)));
    }

    @Test
    public void testMultiplePages()
    {
        InternalAggregationFunction aggFunction = getInternalAggregationFunction(BIGINT, BIGINT);
        GroupedAccumulator groupedAccumulator = getGroupedAccumulator(aggFunction);

        testMultimapAggWithGroupBy(aggFunction, groupedAccumulator, 0, BIGINT, ImmutableList.of(1L, 1L), BIGINT, ImmutableList.of(2L, 3L));
    }

    @Test
    public void testMultiplePagesAndGroups()
    {
        InternalAggregationFunction aggFunction = getInternalAggregationFunction(BIGINT, BIGINT);
        GroupedAccumulator groupedAccumulator = getGroupedAccumulator(aggFunction);

        testMultimapAggWithGroupBy(aggFunction, groupedAccumulator, 0, BIGINT, ImmutableList.of(1L, 1L), BIGINT, ImmutableList.of(2L, 3L));
        testMultimapAggWithGroupBy(aggFunction, groupedAccumulator, 300, BIGINT, ImmutableList.of(7L, 7L), BIGINT, ImmutableList.of(8L, 9L));
    }

    @Test
    public void testManyValues()
    {
        InternalAggregationFunction aggFunction = getInternalAggregationFunction(BIGINT, BIGINT);
        GroupedAccumulator groupedAccumulator = getGroupedAccumulator(aggFunction);

        int numGroups = 30000;
        int numKeys = 10;
        int numValueArraySize = 2;
        Random random = new Random();

        for (int group = 0; group < numGroups; group++) {
            ImmutableList.Builder<Long> keyBuilder = ImmutableList.builder();
            ImmutableList.Builder<Long> valueBuilder = ImmutableList.builder();
            for (int i = 0; i < numKeys; i++) {
                long key = random.nextLong();
                for (int j = 0; j < numValueArraySize; j++) {
                    long value = random.nextLong();
                    keyBuilder.add(key);
                    valueBuilder.add(value);
                }
            }
            testMultimapAggWithGroupBy(aggFunction, groupedAccumulator, group, BIGINT, keyBuilder.build(), BIGINT, valueBuilder.build());
        }
    }

    @Test
    public void testEmptyStateOutputIsNull()
    {
        InternalAggregationFunction aggregationFunction = getInternalAggregationFunction(BIGINT, BIGINT);
        GroupedAccumulator groupedAccumulator = aggregationFunction.bind(Ints.asList(), Optional.empty()).createGroupedAccumulator();
        BlockBuilder blockBuilder = groupedAccumulator.getFinalType().createBlockBuilder(null, 1);
        groupedAccumulator.evaluateFinal(0, blockBuilder);
        assertTrue(blockBuilder.isNull(0));
    }

    private static <K, V> void testMultimapAgg(Type keyType, List<K> expectedKeys, Type valueType, List<V> expectedValues)
    {
        checkState(expectedKeys.size() == expectedValues.size(), "expectedKeys and expectedValues should have equal size");
        InternalAggregationFunction aggFunc = getInternalAggregationFunction(keyType, valueType);
        testMultimapAgg(aggFunc, keyType, expectedKeys, valueType, expectedValues);
    }

    private static InternalAggregationFunction getInternalAggregationFunction(Type keyType, Type valueType)
    {
        MapType mapType = mapType(keyType, new ArrayType(valueType));
        Signature signature = new Signature(NAME, AGGREGATE, mapType.getTypeSignature(), keyType.getTypeSignature(), valueType.getTypeSignature());
        return metadata.getAggregateFunctionImplementation(signature);
    }

    private static <K, V> void testMultimapAgg(InternalAggregationFunction aggFunc, Type keyType, List<K> expectedKeys, Type valueType, List<V> expectedValues)
    {
        Map<K, List<V>> map = new HashMap<>();
        for (int i = 0; i < expectedKeys.size(); i++) {
            if (!map.containsKey(expectedKeys.get(i))) {
                map.put(expectedKeys.get(i), new ArrayList<>());
            }
            map.get(expectedKeys.get(i)).add(expectedValues.get(i));
        }

        RowPageBuilder builder = RowPageBuilder.rowPageBuilder(keyType, valueType);
        for (int i = 0; i < expectedKeys.size(); i++) {
            builder.row(expectedKeys.get(i), expectedValues.get(i));
        }

        assertAggregation(aggFunc, map.isEmpty() ? null : map, builder.build());
    }

    private static <K, V> void testMultimapAggWithGroupBy(
            InternalAggregationFunction aggregationFunction,
            GroupedAccumulator groupedAccumulator,
            int groupId,
            Type keyType,
            List<K> expectedKeys,
            Type valueType,
            List<V> expectedValues)
    {
        RowPageBuilder pageBuilder = RowPageBuilder.rowPageBuilder(keyType, valueType);
        ImmutableMultimap.Builder<K, V> outputBuilder = ImmutableMultimap.builder();
        for (int i = 0; i < expectedValues.size(); i++) {
            pageBuilder.row(expectedKeys.get(i), expectedValues.get(i));
            outputBuilder.put(expectedKeys.get(i), expectedValues.get(i));
        }
        Page page = pageBuilder.build();

        AggregationTestInput input = new AggregationTestInputBuilder(
                new Block[] {page.getBlock(0), page.getBlock(1)},
                aggregationFunction).build();

        AggregationTestOutput testOutput = new AggregationTestOutput(outputBuilder.build().asMap());
        input.runPagesOnAccumulatorWithAssertion(groupId, groupedAccumulator, testOutput);
    }

    private GroupedAccumulator getGroupedAccumulator(InternalAggregationFunction aggFunction)
    {
        return aggFunction.bind(Ints.asList(GroupByAggregationTestUtils.createArgs(aggFunction)), Optional.empty()).createGroupedAccumulator();
    }
}
