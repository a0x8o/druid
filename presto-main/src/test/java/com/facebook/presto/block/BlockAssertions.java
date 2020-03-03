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
package com.facebook.presto.block;

import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.DictionaryBlock;
import com.facebook.presto.spi.block.RunLengthEncodedBlock;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.MapType;
import com.facebook.presto.spi.type.RowType;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.facebook.presto.spi.block.ArrayBlock.fromElementBlock;
import static com.facebook.presto.spi.block.MethodHandleUtil.compose;
import static com.facebook.presto.spi.block.MethodHandleUtil.nativeValueGetter;
import static com.facebook.presto.spi.block.RowBlock.fromFieldBlocks;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.Decimals.MAX_SHORT_PRECISION;
import static com.facebook.presto.spi.type.Decimals.encodeUnscaledValue;
import static com.facebook.presto.spi.type.Decimals.writeBigDecimal;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.testing.TestingConnectorSession.SESSION;
import static com.facebook.presto.testing.TestingEnvironment.TYPE_MANAGER;
import static com.facebook.presto.util.StructuralTestUtil.appendToBlockBuilder;
import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;

public final class BlockAssertions
{
    private static final int ENTRY_SIZE = 4;
    private static final int MAX_STRING_SIZE = 10;

    private BlockAssertions()
    {
    }

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

    public static Block createAllNullsBlock(Type type, int positionCount)
    {
        return new RunLengthEncodedBlock(type.createBlockBuilder(null, 1).appendNull().build(), positionCount);
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

    public static Block createRandomStringBlock(int positionCount, boolean allowNulls, int maxStringLength)
    {
        return createStringsBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : generateRandomStringWithLength(maxStringLength))
                        .collect(Collectors.toList()));
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

    public static <K, V> Block createMapBlock(MapType type, Map<K, V> map)
    {
        BlockBuilder blockBuilder = type.createBlockBuilder(null, map.size());
        for (Map.Entry<K, V> entry : map.entrySet()) {
            BlockBuilder entryBuilder = blockBuilder.beginBlockEntry();
            appendToBlockBuilder(BIGINT, entry.getKey(), entryBuilder);
            appendToBlockBuilder(BIGINT, entry.getValue(), entryBuilder);
            blockBuilder.closeEntry();
        }
        return blockBuilder.build();
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

    public static Block createRandomBooleansBlock(int positionCount, boolean allowNulls)
    {
        return createBooleansBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : ThreadLocalRandom.current().nextBoolean())
                        .collect(Collectors.toList()));
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

    public static Block createRandomShortDecimalsBlock(int positionCount, boolean allowNulls)
    {
        return createShortDecimalsBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : Double.toString(ThreadLocalRandom.current().nextDouble() * ThreadLocalRandom.current().nextInt()))
                        .collect(Collectors.toList()));
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

    public static Block createRandomLongDecimalsBlock(int positionCount, boolean allowNulls)
    {
        checkArgument(positionCount >= 10, "positionCount is less than 10");
        return createLongDecimalsBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : Double.toString(ThreadLocalRandom.current().nextDouble() * ThreadLocalRandom.current().nextInt()))
                        .collect(Collectors.toList()));
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

    public static Block createRandomIntsBlock(int positionCount, boolean allowNulls)
    {
        return createIntsBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : ThreadLocalRandom.current().nextInt())
                        .collect(Collectors.toList()));
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

    public static Block createRandomLongsBlock(int positionCount, boolean allowNulls)
    {
        return createLongsBlock(
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : ThreadLocalRandom.current().nextLong())
                        .collect(Collectors.toList()));
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

    public static Block createRandomSmallintsBlock(int positionCount, boolean allowNulls)
    {
        return createTypedLongsBlock(
                SMALLINT,
                IntStream.range(0, positionCount)
                        .mapToObj(i -> allowNulls && i % 7 == 1 ? null : ThreadLocalRandom.current().nextLong() % Short.MIN_VALUE)
                        .collect(Collectors.toList()));
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

    public static RunLengthEncodedBlock createRLEBlock(String value, int positionCount)
    {
        BlockBuilder blockBuilder = VARCHAR.createBlockBuilder(null, 1);
        VARCHAR.writeSlice(blockBuilder, wrappedBuffer(value.getBytes()));
        return new RunLengthEncodedBlock(blockBuilder.build(), positionCount);
    }

    public static RunLengthEncodedBlock createRleBlockWithRandomValue(Block block, int positionCount)
    {
        checkArgument(block.getPositionCount() > 0, format("block positions %d is less than or equal to 0", block.getPositionCount()));
        return new RunLengthEncodedBlock(block.getRegion(block.getPositionCount() / 2, 1), positionCount);
    }

    public static DictionaryBlock createRandomDictionaryBlock(Block dictionary, int positionCount)
    {
        int[] ids = IntStream.range(0, positionCount).map(i -> ThreadLocalRandom.current().nextInt(dictionary.getPositionCount() / 10)).toArray();
        return new DictionaryBlock(dictionary, ids);
    }

    public static Block createRandomBlockForType(Type type, int positionCount, boolean allowNulls, boolean createView, List<Encoding> wrappings)
    {
        Block block = null;

        if (createView) {
            positionCount *= 2;
        }

        if (type == BOOLEAN) {
            block = createRandomBooleansBlock(positionCount, allowNulls);
        }
        else if (type == BIGINT) {
            block = createRandomLongsBlock(positionCount, allowNulls);
        }
        else if (type == INTEGER || type == REAL) {
            block = createRandomIntsBlock(positionCount, allowNulls);
        }
        else if (type == SMALLINT) {
            block = createRandomSmallintsBlock(positionCount, allowNulls);
        }
        else if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            if (decimalType.isShort()) {
                block = createRandomLongsBlock(positionCount, allowNulls);
            }
            else {
                block = createRandomLongDecimalsBlock(positionCount, allowNulls);
            }
        }
        else if (type == VARCHAR) {
            block = createRandomStringBlock(positionCount, allowNulls, MAX_STRING_SIZE);
        }
        else {
            // Nested types
            // Build isNull and offsets of size positionCount
            boolean[] isNull = new boolean[positionCount];
            int[] offsets = new int[positionCount + 1];
            for (int position = 0; position < positionCount; position++) {
                if (allowNulls && position % 7 == 1) {
                    isNull[position] = true;
                    offsets[position + 1] = offsets[position];
                }
                else {
                    offsets[position + 1] = offsets[position] + (type instanceof RowType ? 1 : ThreadLocalRandom.current().nextInt(ENTRY_SIZE) + 1);
                }
            }

            // Build the nested block of size offsets[positionCount].
            if (type instanceof ArrayType) {
                Block valuesBlock = createRandomBlockForType(((ArrayType) type).getElementType(), offsets[positionCount], allowNulls, createView, wrappings);
                block = fromElementBlock(positionCount, Optional.of(isNull), offsets, valuesBlock);
            }
            else if (type instanceof MapType) {
                MapType mapType = (MapType) type;
                Block keyBlock = createRandomBlockForType(mapType.getKeyType(), offsets[positionCount], false, createView, wrappings);
                Block valueBlock = createRandomBlockForType(mapType.getValueType(), offsets[positionCount], allowNulls, createView, wrappings);

                block = mapType.createBlockFromKeyValue(positionCount, Optional.of(isNull), offsets, keyBlock, valueBlock);
            }
            else if (type instanceof RowType) {
                List<Type> fieldTypes = type.getTypeParameters();
                Block[] fieldBlocks = new Block[fieldTypes.size()];

                for (int i = 0; i < fieldBlocks.length; i++) {
                    fieldBlocks[i] = createRandomBlockForType(fieldTypes.get(i), positionCount, allowNulls, createView, wrappings);
                }

                block = fromFieldBlocks(positionCount, Optional.of(isNull), fieldBlocks);
            }
            else {
                throw new IllegalArgumentException(format("type %s is not supported.", type));
            }
        }

        if (createView) {
            positionCount /= 2;
            int offset = positionCount / 2;
            block = block.getRegion(offset, positionCount);
        }

        if (!wrappings.isEmpty()) {
            block = wrapBlock(block, positionCount, wrappings);
        }

        return block;
    }

    public static Block wrapBlock(Block block, int positionCount, List<Encoding> wrappings)
    {
        checkArgument(!wrappings.isEmpty(), "wrappings is empty");

        Block wrappedBlock = block;

        for (int i = wrappings.size() - 1; i >= 0; i--) {
            switch (wrappings.get(i)) {
                case DICTIONARY:
                    wrappedBlock = createRandomDictionaryBlock(wrappedBlock, positionCount);
                    break;
                case RUN_LENGTH:
                    wrappedBlock = createRleBlockWithRandomValue(wrappedBlock, positionCount);
                    break;
                default:
                    throw new IllegalArgumentException(format("wrappings %s is incorrect", wrappings));
            }
        }
        return wrappedBlock;
    }

    public static MapType createMapType(Type keyType, Type valueType)
    {
        MethodHandle keyNativeEquals = TYPE_MANAGER.resolveOperator(OperatorType.EQUAL, ImmutableList.of(keyType, keyType));
        MethodHandle keyBlockNativeEquals = compose(keyNativeEquals, nativeValueGetter(keyType));
        MethodHandle keyBlockEquals = compose(keyNativeEquals, nativeValueGetter(keyType), nativeValueGetter(keyType));
        MethodHandle keyNativeHashCode = TYPE_MANAGER.resolveOperator(OperatorType.HASH_CODE, ImmutableList.of(keyType));
        MethodHandle keyBlockHashCode = compose(keyNativeHashCode, nativeValueGetter(keyType));
        return new MapType(
                keyType,
                valueType,
                keyBlockNativeEquals,
                keyBlockEquals,
                keyNativeHashCode,
                keyBlockHashCode);
    }

    public enum Encoding
    {
        DICTIONARY,
        RUN_LENGTH
    }

    private static String generateRandomStringWithLength(int length)
    {
        byte[] array = new byte[length];
        ThreadLocalRandom.current().nextBytes(array);
        return new String(array, UTF_8);
    }
}
