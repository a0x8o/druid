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

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;

import java.util.Objects;

import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.type.Chars.compareChars;
import static io.prestosql.spi.type.Chars.padSpaces;
import static java.lang.String.format;
import static java.util.Collections.singletonList;

public final class CharType
        extends AbstractVariableWidthType
{
    public static final int MAX_LENGTH = 65_536;

    private final int length;

    public static CharType createCharType(long length)
    {
        return new CharType(length);
    }

    private CharType(long length)
    {
        super(
                new TypeSignature(
                        StandardTypes.CHAR,
                        singletonList(TypeSignatureParameter.numericParameter(length))),
                Slice.class);

        if (length < 0 || length > MAX_LENGTH) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("CHAR length scale must be in range [0, %s]", MAX_LENGTH));
        }
        this.length = (int) length;
    }

    public int getLength()
    {
        return length;
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

        Slice slice = block.getSlice(position, 0, block.getSliceLength(position));
        return padSpaces(slice, length).toStringUtf8();
    }

    @Override
    public boolean equalTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        int leftLength = leftBlock.getSliceLength(leftPosition);
        int rightLength = rightBlock.getSliceLength(rightPosition);
        if (leftLength != rightLength) {
            return false;
        }
        return leftBlock.equals(leftPosition, 0, rightBlock, rightPosition, 0, leftLength);
    }

    @Override
    public long hash(Block block, int position)
    {
        return block.hash(position, 0, block.getSliceLength(position));
    }

    @Override
    public int compareTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        Slice leftSlice = leftBlock.getSlice(leftPosition, 0, leftBlock.getSliceLength(leftPosition));
        Slice rightSlice = rightBlock.getSlice(rightPosition, 0, rightBlock.getSliceLength(rightPosition));

        return compareChars(leftSlice, rightSlice);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            block.writeBytesTo(position, 0, block.getSliceLength(position), blockBuilder);
            blockBuilder.closeEntry();
        }
    }

    @Override
    public Slice getSlice(Block block, int position)
    {
        return block.getSlice(position, 0, block.getSliceLength(position));
    }

    public void writeString(BlockBuilder blockBuilder, String value)
    {
        writeSlice(blockBuilder, Slices.utf8Slice(value));
    }

    @Override
    public void writeSlice(BlockBuilder blockBuilder, Slice value)
    {
        writeSlice(blockBuilder, value, 0, value.length());
    }

    @Override
    public void writeSlice(BlockBuilder blockBuilder, Slice value, int offset, int length)
    {
        if (length > 0 && value.getByte(offset + length - 1) == ' ') {
            throw new IllegalArgumentException("Slice representing Char should not have trailing spaces");
        }
        blockBuilder.writeBytes(value, offset, length).closeEntry();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CharType other = (CharType) o;

        return Objects.equals(this.length, other.length);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(length);
    }

    @Override
    public String getDisplayName()
    {
        return getTypeSignature().toString();
    }

    @Override
    public String toString()
    {
        return getDisplayName();
    }
}
