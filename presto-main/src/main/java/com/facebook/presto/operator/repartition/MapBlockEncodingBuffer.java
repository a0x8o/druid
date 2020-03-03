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
package com.facebook.presto.operator.repartition;

import com.facebook.presto.spi.block.ColumnarMap;
import com.facebook.presto.spi.type.TypeSerde;
import com.google.common.annotations.VisibleForTesting;
import io.airlift.slice.SliceOutput;
import org.openjdk.jol.info.ClassLayout;

import java.util.Optional;

import static com.facebook.presto.array.Arrays.ExpansionFactor.LARGE;
import static com.facebook.presto.array.Arrays.ExpansionOption.PRESERVE;
import static com.facebook.presto.array.Arrays.ensureCapacity;
import static com.facebook.presto.operator.MoreByteArrays.setInts;
import static com.facebook.presto.operator.UncheckedByteArrays.setIntUnchecked;
import static io.airlift.slice.SizeOf.SIZE_OF_BYTE;
import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static io.airlift.slice.SizeOf.sizeOf;
import static java.util.Objects.requireNonNull;
import static sun.misc.Unsafe.ARRAY_INT_INDEX_SCALE;

public class MapBlockEncodingBuffer
        extends AbstractBlockEncodingBuffer
{
    @VisibleForTesting
    static final int POSITION_SIZE = SIZE_OF_INT + SIZE_OF_BYTE;

    private static final String NAME = "MAP";
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(MapBlockEncodingBuffer.class).instanceSize();

    private static final int HASH_MULTIPLIER = 2;

    // The buffer for the hashtables for all incoming blocks so far
    private byte[] hashTablesBuffer;

    // The address that the next hashtable entry will be written to.
    private int hashTableBufferIndex;

    // If any of the incoming blocks do not contain the hashtable, noHashTables is set to true.
    private boolean noHashTables;

    // The buffer for the offsets for all incoming blocks so far
    private byte[] offsetsBuffer;

    // The address that the next offset value will be written to.
    private int offsetsBufferIndex;

    // This array holds the condensed offsets for each position for the incoming block.
    private int[] offsets;

    // The last offset in the offsets buffer
    private int lastOffset;

    // This array holds the offsets into its nested key and value blocks for each row in the MapBlock.
    private int[] offsetsCopy;

    // The current incoming MapBlock is converted into ColumnarMap
    private ColumnarMap columnarMap;

    // The AbstractBlockEncodingBuffer for the nested key and value Block of the MapBlock
    private final BlockEncodingBuffer keyBuffers;
    private final BlockEncodingBuffer valueBuffers;

    public MapBlockEncodingBuffer(DecodedBlockNode decodedBlockNode)
    {
        keyBuffers = createBlockEncodingBuffers(decodedBlockNode.getChildren().get(0));
        valueBuffers = createBlockEncodingBuffers(decodedBlockNode.getChildren().get(1));
    }

    @Override
    public void accumulateSerializedRowSizes(int[] serializedRowSizes)
    {
        for (int i = 0; i < positionCount; i++) {
            serializedRowSizes[i] += POSITION_SIZE;
        }

        offsetsCopy = ensureCapacity(offsetsCopy, positionCount + 1);

        System.arraycopy(offsets, 0, offsetsCopy, 0, positionCount + 1);
        ((AbstractBlockEncodingBuffer) keyBuffers).accumulateSerializedRowSizes(offsetsCopy, positionCount, serializedRowSizes);

        System.arraycopy(offsets, 0, offsetsCopy, 0, positionCount + 1);
        ((AbstractBlockEncodingBuffer) valueBuffers).accumulateSerializedRowSizes(offsetsCopy, positionCount, serializedRowSizes);
    }

    @Override
    public void setNextBatch(int positionsOffset, int batchSize)
    {
        this.positionsOffset = positionsOffset;
        this.batchSize = batchSize;

        if (this.positionCount == 0) {
            return;
        }

        int beginOffset = offsets[positionsOffset];
        int endOffset = offsets[positionsOffset + batchSize];

        keyBuffers.setNextBatch(beginOffset, endOffset - beginOffset);
        valueBuffers.setNextBatch(beginOffset, endOffset - beginOffset);
    }

    @Override
    public void appendDataInBatch()
    {
        if (batchSize == 0) {
            return;
        }

        appendNulls();
        appendOffsets();
        appendHashTables();

        keyBuffers.appendDataInBatch();
        valueBuffers.appendDataInBatch();

        bufferedPositionCount += batchSize;
    }

    @Override
    public void serializeTo(SliceOutput output)
    {
        writeLengthPrefixedString(output, NAME);

        TypeSerde.writeType(output, columnarMap.getKeyType());

        keyBuffers.serializeTo(output);
        valueBuffers.serializeTo(output);

        // Hash tables
        if (hashTableBufferIndex == 0) {
            output.appendInt(-1);
        }
        else {
            output.appendInt(lastOffset * HASH_MULTIPLIER); // Hash table length
            output.appendBytes(hashTablesBuffer, 0, hashTableBufferIndex);
        }

        output.writeInt(bufferedPositionCount);

        // offsets
        output.appendInt(0);
        if (offsetsBufferIndex > 0) {
            output.appendBytes(offsetsBuffer, 0, offsetsBufferIndex);
        }

        serializeNullsTo(output);
    }

    @Override
    public void resetBuffers()
    {
        bufferedPositionCount = 0;
        offsetsBufferIndex = 0;
        lastOffset = 0;
        hashTableBufferIndex = 0;
        noHashTables = false;
        resetNullsBuffer();

        keyBuffers.resetBuffers();
        valueBuffers.resetBuffers();
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        // columnarMap is counted as part of DecodedBlockNode in OptimizedPartitionedOutputOperator and won't be counted here.
        // This is because the same columnarMap would be hold in all partitions/AbstractBlockEncodingBuffer and thus counting it here would be counting it multiple times.
        return INSTANCE_SIZE +
                getPositionsRetainedSizeInBytes() +
                sizeOf(offsets) +
                sizeOf(offsetsBuffer) +
                sizeOf(offsetsCopy) +
                getNullsBufferRetainedSizeInBytes() +
                keyBuffers.getRetainedSizeInBytes() +
                valueBuffers.getRetainedSizeInBytes() +
                sizeOf(hashTablesBuffer);
    }

    @Override
    public long getSerializedSizeInBytes()
    {
        return NAME.length() + SIZE_OF_INT +            // length prefixed encoding name
                columnarMap.getKeyType().getTypeSignature().toString().length() + SIZE_OF_INT + // length prefixed type string
                keyBuffers.getSerializedSizeInBytes() +    // nested key block
                valueBuffers.getSerializedSizeInBytes() +  // nested value block
                SIZE_OF_INT +                           // hash tables size
                hashTableBufferIndex +                  // hash tables
                SIZE_OF_INT +                           // positionCount
                offsetsBufferIndex + SIZE_OF_INT +      // offsets buffer.
                getNullsBufferSerializedSizeInBytes();  // nulls
    }

    @Override
    protected void setupDecodedBlockAndMapPositions(DecodedBlockNode decodedBlockNode)
    {
        requireNonNull(decodedBlockNode, "decodedBlockNode is null");

        decodedBlockNode = mapPositionsToNestedBlock(decodedBlockNode);
        columnarMap = (ColumnarMap) decodedBlockNode.getDecodedBlock();
        decodedBlock = columnarMap.getNullCheckBlock();

        populateNestedPositions();

        ((AbstractBlockEncodingBuffer) keyBuffers).setupDecodedBlockAndMapPositions(decodedBlockNode.getChildren().get(0));
        ((AbstractBlockEncodingBuffer) valueBuffers).setupDecodedBlockAndMapPositions(decodedBlockNode.getChildren().get(1));
    }

    @Override
    protected void accumulateSerializedRowSizes(int[] positionOffsets, int positionCount, int[] serializedRowSizes)
    {
        // If all positions for the MapBlock to be copied are null, the number of positions to copy for its
        // nested key and value blocks could be 0. In such case we don't need to proceed.
        if (this.positionCount == 0) {
            return;
        }

        int lastOffset = positionOffsets[0];
        for (int i = 0; i < positionCount; i++) {
            int offset = positionOffsets[i + 1];
            serializedRowSizes[i] += POSITION_SIZE * (offset - lastOffset);
            lastOffset = offset;
            positionOffsets[i + 1] = offsets[offset];
        }

        // positionOffsets might be modified by the next level. Save it for the valueBuffers first.
        offsetsCopy = ensureCapacity(offsetsCopy, positionCount + 1);
        System.arraycopy(positionOffsets, 0, offsetsCopy, 0, positionCount + 1);

        ((AbstractBlockEncodingBuffer) keyBuffers).accumulateSerializedRowSizes(positionOffsets, positionCount, serializedRowSizes);
        ((AbstractBlockEncodingBuffer) valueBuffers).accumulateSerializedRowSizes(offsetsCopy, positionCount, serializedRowSizes);
    }

    private void populateNestedPositions()
    {
        // Reset nested level positions before checking positionCount. Failing to do so may result in elementsBuffers having stale values when positionCount is 0.
        ((AbstractBlockEncodingBuffer) keyBuffers).resetPositions();
        ((AbstractBlockEncodingBuffer) valueBuffers).resetPositions();

        if (positionCount == 0) {
            return;
        }

        offsets = ensureCapacity(offsets, positionCount + 1);

        int[] positions = getPositions();

        for (int i = 0; i < positionCount; i++) {
            int position = positions[i];
            int beginOffset = columnarMap.getOffset(position);
            int endOffset = columnarMap.getOffset(position + 1);
            int currentRowSize = endOffset - beginOffset;

            offsets[i + 1] = offsets[i] + currentRowSize;

            if (currentRowSize > 0) {
                ((AbstractBlockEncodingBuffer) keyBuffers).appendPositionRange(beginOffset, currentRowSize);
                ((AbstractBlockEncodingBuffer) valueBuffers).appendPositionRange(beginOffset, currentRowSize);
            }
        }
    }

    private void appendOffsets()
    {
        offsetsBuffer = ensureCapacity(offsetsBuffer, offsetsBufferIndex + batchSize * ARRAY_INT_INDEX_SCALE, LARGE, PRESERVE);

        int baseOffset = lastOffset - offsets[positionsOffset];
        for (int i = positionsOffset; i < positionsOffset + batchSize; i++) {
            offsetsBufferIndex = setIntUnchecked(offsetsBuffer, offsetsBufferIndex, offsets[i + 1] + baseOffset);
        }
        lastOffset = offsets[positionsOffset + batchSize] + baseOffset;
    }

    private void appendHashTables()
    {
        // MergingPageOutput may build hash tables for some of the small blocks. But if there're some blocks
        // without hash tables, it means hash tables are not needed so far. In this case we don't send the hash tables.
        if (noHashTables) {
            return;
        }

        Optional<int[]> hashTables = columnarMap.getHashTables();
        if (!hashTables.isPresent()) {
            noHashTables = true;
            hashTableBufferIndex = 0;
            return;
        }

        int hashTablesSize = (offsets[positionsOffset + batchSize] - offsets[positionsOffset]) * HASH_MULTIPLIER;
        hashTablesBuffer = ensureCapacity(hashTablesBuffer, hashTableBufferIndex + hashTablesSize * ARRAY_INT_INDEX_SCALE, LARGE, PRESERVE);

        int[] positions = getPositions();

        for (int i = positionsOffset; i < positionsOffset + batchSize; i++) {
            int position = positions[i];

            int beginOffset = columnarMap.getAbsoluteOffset(position);
            int endOffset = columnarMap.getAbsoluteOffset(position + 1);

            hashTableBufferIndex = setInts(
                    hashTablesBuffer,
                    hashTableBufferIndex,
                    hashTables.get(),
                    beginOffset * HASH_MULTIPLIER,
                    (endOffset - beginOffset) * HASH_MULTIPLIER);
        }
    }
}
