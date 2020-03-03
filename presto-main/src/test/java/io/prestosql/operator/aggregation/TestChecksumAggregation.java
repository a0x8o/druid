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
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.SqlVarbinary;
import io.prestosql.spi.type.Type;
import org.testng.annotations.Test;

import static io.airlift.slice.Slices.wrappedLongArray;
import static io.prestosql.block.BlockAssertions.createArrayBigintBlock;
import static io.prestosql.block.BlockAssertions.createBooleansBlock;
import static io.prestosql.block.BlockAssertions.createDoublesBlock;
import static io.prestosql.block.BlockAssertions.createLongDecimalsBlock;
import static io.prestosql.block.BlockAssertions.createLongsBlock;
import static io.prestosql.block.BlockAssertions.createShortDecimalsBlock;
import static io.prestosql.block.BlockAssertions.createStringsBlock;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.aggregation.AggregationTestUtils.assertAggregation;
import static io.prestosql.operator.aggregation.ChecksumAggregationFunction.PRIME64;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Arrays.asList;

public class TestChecksumAggregation
{
    private static final Metadata metadata = createTestMetadataManager();

    @Test
    public void testEmpty()
    {
        InternalAggregationFunction booleanAgg = metadata.getAggregateFunctionImplementation(
                new Signature("checksum",
                        AGGREGATE,
                        VARBINARY.getTypeSignature(),
                        BOOLEAN.getTypeSignature()));
        assertAggregation(booleanAgg, null, createBooleansBlock());
    }

    @Test
    public void testBoolean()
    {
        InternalAggregationFunction booleanAgg = metadata.getAggregateFunctionImplementation(
                new Signature("checksum",
                        AGGREGATE,
                        VARBINARY.getTypeSignature(),
                        BOOLEAN.getTypeSignature()));
        Block block = createBooleansBlock(null, null, true, false, false);
        assertAggregation(booleanAgg, expectedChecksum(BOOLEAN, block), block);
    }

    @Test
    public void testLong()
    {
        InternalAggregationFunction longAgg = metadata.getAggregateFunctionImplementation(
                new Signature("checksum",
                        AGGREGATE,
                        VARBINARY.getTypeSignature(),
                        BIGINT.getTypeSignature()));
        Block block = createLongsBlock(null, 1L, 2L, 100L, null, Long.MAX_VALUE, Long.MIN_VALUE);
        assertAggregation(longAgg, expectedChecksum(BIGINT, block), block);
    }

    @Test
    public void testDouble()
    {
        InternalAggregationFunction doubleAgg = metadata.getAggregateFunctionImplementation(
                new Signature("checksum",
                        AGGREGATE,
                        VARBINARY.getTypeSignature(),
                        DOUBLE.getTypeSignature()));
        Block block = createDoublesBlock(null, 2.0, null, 3.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN);
        assertAggregation(doubleAgg, expectedChecksum(DOUBLE, block), block);
    }

    @Test
    public void testString()
    {
        InternalAggregationFunction stringAgg = metadata.getAggregateFunctionImplementation(
                new Signature("checksum",
                        AGGREGATE,
                        VARBINARY.getTypeSignature(),
                        VARCHAR.getTypeSignature()));
        Block block = createStringsBlock("a", "a", null, "b", "c");
        assertAggregation(stringAgg, expectedChecksum(VARCHAR, block), block);
    }

    @Test
    public void testShortDecimal()
    {
        InternalAggregationFunction decimalAgg = metadata.getAggregateFunctionImplementation(new Signature(
                "checksum",
                AGGREGATE,
                VARBINARY.getTypeSignature(),
                createDecimalType(10, 2).getTypeSignature()));
        Block block = createShortDecimalsBlock("11.11", "22.22", null, "33.33", "44.44");
        DecimalType shortDecimalType = createDecimalType(1);
        assertAggregation(decimalAgg, expectedChecksum(shortDecimalType, block), block);
    }

    @Test
    public void testLongDecimal()
    {
        InternalAggregationFunction decimalAgg = metadata.getAggregateFunctionImplementation(new Signature(
                "checksum",
                AGGREGATE,
                VARBINARY.getTypeSignature(),
                createDecimalType(19, 2).getTypeSignature()));
        Block block = createLongDecimalsBlock("11.11", "22.22", null, "33.33", "44.44");
        DecimalType longDecimalType = createDecimalType(19);
        assertAggregation(decimalAgg, expectedChecksum(longDecimalType, block), block);
    }

    @Test
    public void testArray()
    {
        ArrayType arrayType = new ArrayType(BIGINT);
        InternalAggregationFunction stringAgg = metadata.getAggregateFunctionImplementation(new Signature("checksum", AGGREGATE, VARBINARY.getTypeSignature(), arrayType.getTypeSignature()));
        Block block = createArrayBigintBlock(asList(null, asList(1L, 2L), asList(3L, 4L), asList(5L, 6L)));
        assertAggregation(stringAgg, expectedChecksum(arrayType, block), block);
    }

    private static SqlVarbinary expectedChecksum(Type type, Block block)
    {
        long result = 0;
        for (int i = 0; i < block.getPositionCount(); i++) {
            if (block.isNull(i)) {
                result += PRIME64;
            }
            else {
                result += type.hash(block, i) * PRIME64;
            }
        }
        return new SqlVarbinary(wrappedLongArray(result).getBytes());
    }
}
