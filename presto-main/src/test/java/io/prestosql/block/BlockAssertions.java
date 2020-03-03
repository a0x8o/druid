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
package io.prestosql.block;

import io.airlift.slice.Slice;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.DictionaryBlock;
import io.prestosql.spi.block.RowBlockBuilder;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.Decimals.MAX_SHORT_PRECISION;
import static io.prestosql.spi.type.Decimals.encodeUnscaledValue;
import static io.prestosql.spi.type.Decimals.writeBigDecimal;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static java.lang.Float.floatToRawIntBits;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;

public final class BlockAssertions
{
    private BlockAssertions() {}

    public static Object getOnlyValue(Type type, Block block)
    {
        assertEquals(block.getPositionCount(), 1, "Block positions");
        return type.getObjectValue(SESSION, block, 0);
    }

    public static List<Object> toValues(Type type, Iterable<Block> blocks)
    {
        List<Object> values = new ArrayList<>();
        for (Block block : blocks) {
            for (int position = 0; position < block.getPositionCount(); position++) {
                values.add(type.getObjectValue(SESSION, block, position));
            }
        }
        return Collections.unmodifiableList(values);
    }

    public static List<Object> toValues(Type type, Block block)
    {
        List<Object> values = new ArrayList<>();
        for (int position = 0; position < block.getPositionCount(); position++) {
            values.add(type.getObjectValue(SESSION, block, position));
        }
        return Collections.unmodifiableList(values);
    }

    public static void assertBlockEquals(Type type, Block actual, Block expected)
    {
        assertEquals(actual.getPositionCount(), expected.getPositionCount());
        for (int position = 0; position < actual.getPositionCount(); position++) {
            assertEquals(type.getObjectValue(SESSION, actual, position), type.getObjectValue(SESSION, expected, position));
        }
    }

    public static Block createStringsBlock(String... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createStringsBlock(Arrays.asList(values));
    }

    public static Block createStringsBlock(Iterable<String> values)
    {
        BlockBuilder builder = VARCHAR.createBlockBuilder(null, 100);

        for (String value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                VARCHAR.writeString(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createSlicesBlock(Slice... values)
    {
        requireNonNull(values, "varargs 'values' is null");
        return createSlicesBlock(Arrays.asList(values));
    }

    public static Block createSlicesBlock(Iterable<Slice> values)
    {
        BlockBuilder builder = VARBINARY.createBlockBuilder(null, 100);

        for (Slice value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                VARBINARY.writeSlice(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createStringSequenceBlock(int start, int end)
    {
        BlockBuilder builder = VARCHAR.createBlockBuilder(null, 100);

        for (int i = start; i < end; i++) {
            VARCHAR.writeString(builder, String.valueOf(i));
        }

        return builder.build();
    }

    public static Block createStringDictionaryBlock(int start, int length)
    {
        checkArgument(length > 5, "block must have more than 5 entries");

        int dictionarySize = length / 5;
        BlockBuilder builder = VARCHAR.createBlockBuilder(null, dictionarySize);
        for (int i = start; i < start + dictionarySize; i++) {
            VARCHAR.writeString(builder, String.valueOf(i));
        }
        int[] ids = new int[length];
        for (int i = 0; i < length; i++) {
            ids[i] = i % dictionarySize;
        }
        return new DictionaryBlock(builder.build(), ids);
    }

    public static Block createStringArraysBlock(Iterable<? extends Iterable<String>> values)
    {
        ArrayType arrayType = new ArrayType(VARCHAR);
        BlockBuilder builder = arrayType.createBlockBuilder(null, 100);

        for (Iterable<String> value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                arrayType.writeObject(builder, createStringsBlock(value));
            }
        }

        return builder.build();
    }

    public static Block createBooleansBlock(Boolean... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createBooleansBlock(Arrays.asList(values));
    }

    public static Block createBooleansBlock(Boolean value, int count)
    {
        return createBooleansBlock(Collections.nCopies(count, value));
    }

    public static Block createBooleansBlock(Iterable<Boolean> values)
    {
        BlockBuilder builder = BOOLEAN.createBlockBuilder(null, 100);

        for (Boolean value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                BOOLEAN.writeBoolean(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createShortDecimalsBlock(String... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createShortDecimalsBlock(Arrays.asList(values));
    }

    public static Block createShortDecimalsBlock(Iterable<String> values)
    {
        DecimalType shortDecimalType = DecimalType.createDecimalType(1);
        BlockBuilder builder = shortDecimalType.createBlockBuilder(null, 100);

        for (String value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                shortDecimalType.writeLong(builder, new BigDecimal(value).unscaledValue().longValue());
            }
        }

        return builder.build();
    }

    public static Block createLongDecimalsBlock(String... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createLongDecimalsBlock(Arrays.asList(values));
    }

    public static Block createLongDecimalsBlock(Iterable<String> values)
    {
        DecimalType longDecimalType = DecimalType.createDecimalType(MAX_SHORT_PRECISION + 1);
        BlockBuilder builder = longDecimalType.createBlockBuilder(null, 100);

        for (String value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                writeBigDecimal(longDecimalType, builder, new BigDecimal(value));
            }
        }

        return builder.build();
    }

    public static Block createIntsBlock(Integer... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createIntsBlock(Arrays.asList(values));
    }

    public static Block createIntsBlock(Iterable<Integer> values)
    {
        BlockBuilder builder = INTEGER.createBlockBuilder(null, 100);

        for (Integer value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                INTEGER.writeLong(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createRowBlock(List<Type> fieldTypes, Object[]... rows)
    {
        BlockBuilder rowBlockBuilder = new RowBlockBuilder(fieldTypes, null, 1);
        for (Object[] row : rows) {
            if (row == null) {
                rowBlockBuilder.appendNull();
                continue;
            }
            BlockBuilder singleRowBlockWriter = rowBlockBuilder.beginBlockEntry();
            for (Object fieldValue : row) {
                if (fieldValue == null) {
                    singleRowBlockWriter.appendNull();
                    continue;
                }

                if (fieldValue instanceof String) {
                    VARCHAR.writeSlice(singleRowBlockWriter, utf8Slice((String) fieldValue));
                }
                else if (fieldValue instanceof Slice) {
                    VARBINARY.writeSlice(singleRowBlockWriter, (Slice) fieldValue);
                }
                else if (fieldValue instanceof Double) {
                    DOUBLE.writeDouble(singleRowBlockWriter, ((Double) fieldValue).doubleValue());
                }
                else if (fieldValue instanceof Long) {
                    BIGINT.writeLong(singleRowBlockWriter, ((Long) fieldValue).longValue());
                }
                else if (fieldValue instanceof Boolean) {
                    BOOLEAN.writeBoolean(singleRowBlockWriter, ((Boolean) fieldValue).booleanValue());
                }
                else if (fieldValue instanceof Block) {
                    singleRowBlockWriter.appendStructure((Block) fieldValue);
                }
                else if (fieldValue instanceof Integer) {
                    INTEGER.writeLong(singleRowBlockWriter, ((Integer) fieldValue).intValue());
                }
                else {
                    throw new IllegalArgumentException();
                }
            }
            rowBlockBuilder.closeEntry();
        }

        return rowBlockBuilder.build();
    }

    public static Block createEmptyLongsBlock()
    {
        return BIGINT.createFixedSizeBlockBuilder(0).build();
    }

    // This method makes it easy to create blocks without having to add an L to every value
    public static Block createLongsBlock(int... values)
    {
        BlockBuilder builder = BIGINT.createBlockBuilder(null, 100);

        for (int value : values) {
            BIGINT.writeLong(builder, (long) value);
        }

        return builder.build();
    }

    public static Block createLongsBlock(Long... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createLongsBlock(Arrays.asList(values));
    }

    public static Block createLongsBlock(Iterable<Long> values)
    {
        return createTypedLongsBlock(BIGINT, values);
    }

    public static Block createTypedLongsBlock(Type type, Iterable<Long> values)
    {
        BlockBuilder builder = type.createBlockBuilder(null, 100);

        for (Long value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                type.writeLong(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createLongSequenceBlock(int start, int end)
    {
        BlockBuilder builder = BIGINT.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            BIGINT.writeLong(builder, i);
        }

        return builder.build();
    }

    public static Block createLongDictionaryBlock(int start, int length)
    {
        checkArgument(length > 5, "block must have more than 5 entries");

        int dictionarySize = length / 5;
        BlockBuilder builder = BIGINT.createBlockBuilder(null, dictionarySize);
        for (int i = start; i < start + dictionarySize; i++) {
            BIGINT.writeLong(builder, i);
        }
        int[] ids = new int[length];
        for (int i = 0; i < length; i++) {
            ids[i] = i % dictionarySize;
        }
        return new DictionaryBlock(builder.build(), ids);
    }

    public static Block createLongRepeatBlock(int value, int length)
    {
        BlockBuilder builder = BIGINT.createFixedSizeBlockBuilder(length);
        for (int i = 0; i < length; i++) {
            BIGINT.writeLong(builder, value);
        }
        return builder.build();
    }

    public static Block createDoubleRepeatBlock(double value, int length)
    {
        BlockBuilder builder = DOUBLE.createFixedSizeBlockBuilder(length);
        for (int i = 0; i < length; i++) {
            DOUBLE.writeDouble(builder, value);
        }
        return builder.build();
    }

    public static Block createTimestampsWithTimezoneBlock(Long... values)
    {
        BlockBuilder builder = TIMESTAMP_WITH_TIME_ZONE.createFixedSizeBlockBuilder(values.length);
        for (long value : values) {
            TIMESTAMP_WITH_TIME_ZONE.writeLong(builder, value);
        }
        return builder.build();
    }

    public static Block createBooleanSequenceBlock(int start, int end)
    {
        BlockBuilder builder = BOOLEAN.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            BOOLEAN.writeBoolean(builder, i % 2 == 0);
        }

        return builder.build();
    }

    public static Block createBlockOfReals(Float... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createBlockOfReals(Arrays.asList(values));
    }

    private static Block createBlockOfReals(Iterable<Float> values)
    {
        BlockBuilder builder = REAL.createBlockBuilder(null, 100);
        for (Float value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                REAL.writeLong(builder, floatToRawIntBits(value));
            }
        }
        return builder.build();
    }

    public static Block createSequenceBlockOfReal(int start, int end)
    {
        BlockBuilder builder = REAL.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            REAL.writeLong(builder, floatToRawIntBits((float) i));
        }

        return builder.build();
    }

    public static Block createDoublesBlock(Double... values)
    {
        requireNonNull(values, "varargs 'values' is null");

        return createDoublesBlock(Arrays.asList(values));
    }

    public static Block createDoublesBlock(Iterable<Double> values)
    {
        BlockBuilder builder = DOUBLE.createBlockBuilder(null, 100);

        for (Double value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                DOUBLE.writeDouble(builder, value);
            }
        }

        return builder.build();
    }

    public static Block createDoubleSequenceBlock(int start, int end)
    {
        BlockBuilder builder = DOUBLE.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            DOUBLE.writeDouble(builder, (double) i);
        }

        return builder.build();
    }

    public static Block createArrayBigintBlock(Iterable<? extends Iterable<Long>> values)
    {
        ArrayType arrayType = new ArrayType(BIGINT);
        BlockBuilder builder = arrayType.createBlockBuilder(null, 100);

        for (Iterable<Long> value : values) {
            if (value == null) {
                builder.appendNull();
            }
            else {
                arrayType.writeObject(builder, createLongsBlock(value));
            }
        }

        return builder.build();
    }

    public static Block createDateSequenceBlock(int start, int end)
    {
        BlockBuilder builder = DATE.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            DATE.writeLong(builder, i);
        }

        return builder.build();
    }

    public static Block createTimestampSequenceBlock(int start, int end)
    {
        BlockBuilder builder = TIMESTAMP.createFixedSizeBlockBuilder(end - start);

        for (int i = start; i < end; i++) {
            TIMESTAMP.writeLong(builder, i);
        }

        return builder.build();
    }

    public static Block createShortDecimalSequenceBlock(int start, int end, DecimalType type)
    {
        BlockBuilder builder = type.createFixedSizeBlockBuilder(end - start);
        long base = BigInteger.TEN.pow(type.getScale()).longValue();

        for (int i = start; i < end; ++i) {
            type.writeLong(builder, base * i);
        }

        return builder.build();
    }

    public static Block createLongDecimalSequenceBlock(int start, int end, DecimalType type)
    {
        BlockBuilder builder = type.createFixedSizeBlockBuilder(end - start);
        BigInteger base = BigInteger.TEN.pow(type.getScale());

        for (int i = start; i < end; ++i) {
            type.writeSlice(builder, encodeUnscaledValue(BigInteger.valueOf(i).multiply(base)));
        }

        return builder.build();
    }

    public static RunLengthEncodedBlock createRLEBlock(double value, int positionCount)
    {
        BlockBuilder blockBuilder = DOUBLE.createBlockBuilder(null, 1);
        DOUBLE.writeDouble(blockBuilder, value);
        return new RunLengthEncodedBlock(blockBuilder.build(), positionCount);
    }

    public static RunLengthEncodedBlock createRLEBlock(long value, int positionCount)
    {
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, 1);
        BIGINT.writeLong(blockBuilder, value);
        return new RunLengthEncodedBlock(blockBuilder.build(), positionCount);
    }
}
