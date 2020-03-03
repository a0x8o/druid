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
package com.facebook.presto.spi.block;

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.airlift.slice.Slices;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.util.function.BiConsumer;

import static com.facebook.presto.spi.block.BlockUtil.MAX_ARRAY_SIZE;
import static com.facebook.presto.spi.block.BlockUtil.calculateBlockResetSize;
import static com.facebook.presto.spi.block.BlockUtil.checkArrayRange;
import static com.facebook.presto.spi.block.BlockUtil.checkValidPosition;
import static com.facebook.presto.spi.block.BlockUtil.checkValidRegion;
import static io.airlift.slice.SizeOf.SIZE_OF_BYTE;
import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.airlift.slice.SizeOf.SIZE_OF_SHORT;

public class FixedWidthBlockBuilder
        extends AbstractFixedWidthBlock
        implements BlockBuilder
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(FixedWidthBlockBuilder.class).instanceSize();

    @Nullable
    private BlockBuilderStatus blockBuilderStatus;

    private boolean initialized;
    private int initialEntryCount;

    private SliceOutput sliceOutput = new DynamicSliceOutput(0);
    private SliceOutput valueIsNull = new DynamicSliceOutput(0);
    private boolean hasNullValue;
    private int positionCount;

    private int currentEntrySize;

    public FixedWidthBlockBuilder(int fixedSize, @Nullable BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        super(fixedSize);

        this.blockBuilderStatus = blockBuilderStatus;

        initialEntryCount = expectedEntries;
    }

    public FixedWidthBlockBuilder(int fixedSize, int positionCount)
    {
        super(fixedSize);

        initialized = true;
        Slice slice = Slices.allocate(fixedSize * positionCount);

        this.blockBuilderStatus = null;
        this.sliceOutput = slice.getOutput();

        this.valueIsNull = Slices.allocate(positionCount).getOutput();
    }

    @Override
    protected Slice getRawSlice()
    {
        return sliceOutput.getUnderlyingSlice();
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getSizeInBytes()
    {
        return sliceOutput.size() + (long) valueIsNull.size();
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        long size = INSTANCE_SIZE + sliceOutput.getRetainedSize() + valueIsNull.getRetainedSize();
        if (blockBuilderStatus != null) {
            size += BlockBuilderStatus.INSTANCE_SIZE;
        }
        return size;
    }

    @Override
    public void retainedBytesForEachPart(BiConsumer<Object, Long> consumer)
    {
        consumer.accept(sliceOutput, sliceOutput.getRetainedSize());
        consumer.accept(valueIsNull, valueIsNull.getRetainedSize());
        consumer.accept(this, (long) INSTANCE_SIZE);
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        SliceOutput newSlice = Slices.allocate(length * fixedSize).getOutput();
        SliceOutput newValueIsNull = null;
        if (hasNullValue) {
            newValueIsNull = Slices.allocate(length).getOutput();
        }

        for (int i = offset; i < offset + length; ++i) {
            int position = positions[i];
            checkValidPosition(position, positionCount);
            if (hasNullValue) {
                newValueIsNull.appendByte(valueIsNull.getUnderlyingSlice().getByte(position));
            }
            newSlice.writeBytes(getRawSlice(), position * fixedSize, fixedSize);
        }
        return new FixedWidthBlock(fixedSize, length, newSlice.slice(), newValueIsNull == null ? null : newValueIsNull.slice());
    }

    @Override
    public BlockBuilder writeByte(int value)
    {
        checkCapacity();
        sliceOutput.writeByte(value);
        currentEntrySize += SIZE_OF_BYTE;
        return this;
    }

    @Override
    public BlockBuilder writeShort(int value)
    {
        checkCapacity();
        sliceOutput.writeShort(value);
        currentEntrySize += SIZE_OF_SHORT;
        return this;
    }

    @Override
    public BlockBuilder writeInt(int value)
    {
        checkCapacity();
        sliceOutput.writeInt(value);
        currentEntrySize += SIZE_OF_INT;
        return this;
    }

    @Override
    public BlockBuilder writeLong(long value)
    {
        checkCapacity();
        sliceOutput.writeLong(value);
        currentEntrySize += SIZE_OF_LONG;
        return this;
    }

    @Override
    public BlockBuilder writeBytes(Slice source, int sourceIndex, int length)
    {
        if (length != fixedSize) {
            throw new IllegalStateException("Expected entry size to be exactly " + fixedSize + " but was " + currentEntrySize);
        }
        checkCapacity();
        sliceOutput.writeBytes(source, sourceIndex, length);
        currentEntrySize += length;
        return this;
    }

    @Override
    public BlockBuilder closeEntry()
    {
        if (currentEntrySize != fixedSize) {
            throw new IllegalStateException("Expected entry size to be exactly " + fixedSize + " but was " + currentEntrySize);
        }

        entryAdded(false);
        currentEntrySize = 0;
        return this;
    }

    @Override
    public BlockBuilder appendNull()
    {
        if (currentEntrySize > 0) {
            throw new IllegalStateException("Current entry must be closed before a null can be written");
        }

        checkCapacity();

        hasNullValue = true;

        // fixed width is always written regardless of null flag
        sliceOutput.writeZero(fixedSize);

        entryAdded(true);

        return this;
    }

    private void entryAdded(boolean isNull)
    {
        checkCapacity();
        valueIsNull.appendByte(isNull ? 1 : 0);

        positionCount++;
        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(Byte.BYTES + fixedSize);
        }
    }

    private void checkCapacity()
    {
        // this code is structured this way so the expensive checks happen
        // on the uncommon path for JVM inlining decision
        if (!initialized) {
            initializeCapacity();
        }
    }

    private void initializeCapacity()
    {
        if (positionCount != 0 || currentEntrySize != 0) {
            throw new IllegalStateException(getClass().getSimpleName() + " was used before initialization");
        }

        int initialSliceOutputSize = (int) Math.min((long) fixedSize * initialEntryCount, MAX_ARRAY_SIZE);
        sliceOutput = new DynamicSliceOutput(initialSliceOutputSize);
        valueIsNull = new DynamicSliceOutput(initialEntryCount);

        initialized = true;
    }

    @Override
    public boolean mayHaveNull()
    {
        return hasNullValue;
    }

    @Override
    protected boolean isEntryNull(int position)
    {
        return valueIsNull.getUnderlyingSlice().getByte(position) != 0;
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        int positionCount = getPositionCount();
        checkValidRegion(positionCount, positionOffset, length);

        Slice newSlice = sliceOutput.slice().slice(positionOffset * fixedSize, length * fixedSize);
        Slice newValueIsNull = null;
        if (hasNullValue) {
            newValueIsNull = valueIsNull.slice().slice(positionOffset, length);
        }
        return new FixedWidthBlock(fixedSize, length, newSlice, newValueIsNull);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        int positionCount = getPositionCount();
        checkValidRegion(positionCount, positionOffset, length);

        Slice newSlice = Slices.copyOf(sliceOutput.getUnderlyingSlice(), positionOffset * fixedSize, length * fixedSize);
        Slice newValueIsNull = null;
        if (hasNullValue) {
            newValueIsNull = Slices.copyOf(valueIsNull.getUnderlyingSlice(), positionOffset, length);
        }
        return new FixedWidthBlock(fixedSize, length, newSlice, newValueIsNull);
    }

    @Override
    public Block build()
    {
        if (currentEntrySize > 0) {
            throw new IllegalStateException("Current entry must be closed before the block can be built");
        }
        return new FixedWidthBlock(fixedSize, positionCount, sliceOutput.slice(), hasNullValue ? valueIsNull.slice() : null);
    }

    @Override
    public BlockBuilder newBlockBuilderLike(BlockBuilderStatus blockBuilderStatus)
    {
        return new FixedWidthBlockBuilder(fixedSize, blockBuilderStatus, calculateBlockResetSize(positionCount));
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("FixedWidthBlockBuilder{");
        sb.append("positionCount=").append(positionCount);
        sb.append(", fixedSize=").append(fixedSize);
        sb.append(", size=").append(sliceOutput.size());
        sb.append('}');
        return sb.toString();
    }
}
