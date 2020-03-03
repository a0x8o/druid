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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.array.ObjectBigArray;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.Type;
import org.openjdk.jol.info.ClassLayout;

import static com.facebook.presto.type.TypeUtils.expectedValueSize;
import static java.util.Objects.requireNonNull;

public class MultiKeyValuePairs
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(MultiKeyValuePairs.class).instanceSize();
    private static final int EXPECTED_ENTRIES = 10;
    private static final int EXPECTED_ENTRY_SIZE = 16;

    private final BlockBuilder keyBlockBuilder;
    private final Type keyType;

    private final BlockBuilder valueBlockBuilder;
    private final Type valueType;

    public MultiKeyValuePairs(Type keyType, Type valueType)
    {
        this.keyType = requireNonNull(keyType, "keyType is null");
        this.valueType = requireNonNull(valueType, "valueType is null");
        keyBlockBuilder = this.keyType.createBlockBuilder(null, EXPECTED_ENTRIES, expectedValueSize(keyType, EXPECTED_ENTRY_SIZE));
        valueBlockBuilder = this.valueType.createBlockBuilder(null, EXPECTED_ENTRIES, expectedValueSize(valueType, EXPECTED_ENTRY_SIZE));
    }

    public MultiKeyValuePairs(Block serialized, Type keyType, Type valueType)
    {
        this(keyType, valueType);
        deserialize(requireNonNull(serialized, "serialized is null"));
    }

    public Block getKeys()
    {
        return keyBlockBuilder.build();
    }

    public Block getValues()
    {
        return valueBlockBuilder.build();
    }

    private void deserialize(Block block)
    {
        for (int i = 0; i < block.getPositionCount(); i++) {
            Block entryBlock = block.getObject(i, Block.class);
            add(entryBlock, entryBlock, 0, 1);
        }
    }

    public void serialize(BlockBuilder out)
    {
        BlockBuilder arrayBlockBuilder = out.beginBlockEntry();
        for (int i = 0; i < keyBlockBuilder.getPositionCount(); i++) {
            BlockBuilder rowBlockBuilder = arrayBlockBuilder.beginBlockEntry();
            keyType.appendTo(keyBlockBuilder, i, rowBlockBuilder);
            valueType.appendTo(valueBlockBuilder, i, rowBlockBuilder);
            arrayBlockBuilder.closeEntry();
        }
        out.closeEntry();
    }

    /**
     * Serialize as a multimap: map(key, array(value)), each key can be associated with multiple values
     */
    public void toMultimapNativeEncoding(BlockBuilder blockBuilder)
    {
        Block keys = keyBlockBuilder.build();
        Block values = valueBlockBuilder.build();

        // Merge values of the same key into an array
        BlockBuilder distinctKeyBlockBuilder = keyType.createBlockBuilder(null, keys.getPositionCount(), expectedValueSize(keyType, EXPECTED_ENTRY_SIZE));
        ObjectBigArray<BlockBuilder> valueArrayBlockBuilders = new ObjectBigArray<>();
        valueArrayBlockBuilders.ensureCapacity(keys.getPositionCount());
        TypedSet keySet = new TypedSet(keyType, keys.getPositionCount(), MultimapAggregationFunction.NAME);
        for (int keyValueIndex = 0; keyValueIndex < keys.getPositionCount(); keyValueIndex++) {
            if (!keySet.contains(keys, keyValueIndex)) {
                keySet.add(keys, keyValueIndex);
                keyType.appendTo(keys, keyValueIndex, distinctKeyBlockBuilder);
                BlockBuilder valueArrayBuilder = valueType.createBlockBuilder(null, 10, expectedValueSize(valueType, EXPECTED_ENTRY_SIZE));
                valueArrayBlockBuilders.set(keySet.positionOf(keys, keyValueIndex), valueArrayBuilder);
            }
            valueType.appendTo(values, keyValueIndex, valueArrayBlockBuilders.get(keySet.positionOf(keys, keyValueIndex)));
        }

        // Write keys and value arrays into one Block
        Block distinctKeys = distinctKeyBlockBuilder.build();
        Type valueArrayType = new ArrayType(valueType);
        BlockBuilder multimapBlockBuilder = blockBuilder.beginBlockEntry();
        for (int i = 0; i < distinctKeys.getPositionCount(); i++) {
            keyType.appendTo(distinctKeys, i, multimapBlockBuilder);
            valueArrayType.writeObject(multimapBlockBuilder, valueArrayBlockBuilders.get(i).build());
        }
        blockBuilder.closeEntry();
    }

    public long estimatedInMemorySize()
    {
        long size = INSTANCE_SIZE;
        size += keyBlockBuilder.getRetainedSizeInBytes();
        size += valueBlockBuilder.getRetainedSizeInBytes();
        return size;
    }

    public void add(Block key, Block value, int keyPosition, int valuePosition)
    {
        keyType.appendTo(key, keyPosition, keyBlockBuilder);
        if (value.isNull(valuePosition)) {
            valueBlockBuilder.appendNull();
        }
        else {
            valueType.appendTo(value, valuePosition, valueBlockBuilder);
        }
    }
}
