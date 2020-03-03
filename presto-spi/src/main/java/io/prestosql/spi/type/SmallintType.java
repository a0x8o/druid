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
package io.prestosql.spi.type;

import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.BlockBuilderStatus;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.block.ShortArrayBlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;

import java.util.Optional;

import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.lang.Long.rotateLeft;
import static java.lang.String.format;

public final class SmallintType
        extends AbstractType
        implements FixedWidthType
{
    public static final SmallintType SMALLINT = new SmallintType();

    private SmallintType()
    {
        super(new TypeSignature(StandardTypes.SMALLINT), long.class);
    }

    @Override
    public int getFixedSize()
    {
        return Short.BYTES;
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries, int expectedBytesPerEntry)
    {
        int maxBlockSizeInBytes;
        if (blockBuilderStatus == null) {
            maxBlockSizeInBytes = PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
        }
        else {
            maxBlockSizeInBytes = blockBuilderStatus.getMaxPageSizeInBytes();
        }
        return new ShortArrayBlockBuilder(
                blockBuilderStatus,
                Math.min(expectedEntries, maxBlockSizeInBytes / Short.BYTES));
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        return createBlockBuilder(blockBuilderStatus, expectedEntries, Short.BYTES);
    }

    @Override
    public BlockBuilder createFixedSizeBlockBuilder(int positionCount)
    {
        return new ShortArrayBlockBuilder(null, positionCount);
    }

    @Override
    public boolean isComparable()
    {
        return true;
    }

    @Override
    public boolean isOrderable()
    {
        return true;
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return block.getShort(position, 0);
    }

    @Override
    public boolean equalTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        int leftValue = leftBlock.getShort(leftPosition, 0);
        int rightValue = rightBlock.getShort(rightPosition, 0);
        return leftValue == rightValue;
    }

    @Override
    public long hash(Block block, int position)
    {
        return hash(block.getShort(position, 0));
    }

    @Override
    public int compareTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        // WARNING: the correctness of InCodeGenerator is dependent on the implementation of this
        // function being the equivalence of internal long representation.
        short leftValue = leftBlock.getShort(leftPosition, 0);
        short rightValue = rightBlock.getShort(rightPosition, 0);
        return Short.compare(leftValue, rightValue);
    }

    @Override
    public Optional<Range> getRange()
    {
        return Optional.of(new Range((long) Short.MIN_VALUE, (long) Short.MAX_VALUE));
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            blockBuilder.writeShort(block.getShort(position, 0)).closeEntry();
        }
    }

    @Override
    public long getLong(Block block, int position)
    {
        return (long) block.getShort(position, 0);
    }

    @Override
    public void writeLong(BlockBuilder blockBuilder, long value)
    {
        if (value > Short.MAX_VALUE) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, format("Value %d exceeds MAX_SHORT", value));
        }
        if (value < Short.MIN_VALUE) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, format("Value %d is less than MIN_SHORT", value));
        }

        blockBuilder.writeShort((int) value).closeEntry();
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object other)
    {
        return other == SMALLINT;
    }

    @Override
    public int hashCode()
    {
        return getClass().hashCode();
    }

    public static long hash(short value)
    {
        // xxhash64 mix
        return rotateLeft(value * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B185EBCA87L;
    }
}
