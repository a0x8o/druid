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
import io.airlift.slice.SliceUtf8;
import io.airlift.slice.Slices;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;

import java.util.Objects;
import java.util.Optional;

import static java.lang.Character.MAX_CODE_POINT;
import static java.util.Collections.singletonList;

public final class VarcharType
        extends AbstractVariableWidthType
{
    public static final int UNBOUNDED_LENGTH = Integer.MAX_VALUE;
    public static final int MAX_LENGTH = Integer.MAX_VALUE - 1;
    public static final VarcharType VARCHAR = new VarcharType(UNBOUNDED_LENGTH);

    public static VarcharType createUnboundedVarcharType()
    {
        return VARCHAR;
    }

    public static VarcharType createVarcharType(int length)
    {
        if (length > MAX_LENGTH || length < 0) {
            // Use createUnboundedVarcharType for unbounded VARCHAR.
            throw new IllegalArgumentException("Invalid VARCHAR length " + length);
        }
        return new VarcharType(length);
    }

    public static TypeSignature getParametrizedVarcharSignature(String param)
    {
        return new TypeSignature(StandardTypes.VARCHAR, TypeSignatureParameter.typeVariable(param));
    }

    private final int length;

    private VarcharType(int length)
    {
        super(
                new TypeSignature(
                        StandardTypes.VARCHAR,
                        singletonList(TypeSignatureParameter.numericParameter((long) length))),
                Slice.class);

        if (length < 0) {
            throw new IllegalArgumentException("Invalid VARCHAR length " + length);
        }
        this.length = length;
    }

    public Optional<Integer> getLength()
    {
        if (isUnbounded()) {
            return Optional.empty();
        }
        return Optional.of(length);
    }

    public int getBoundedLength()
    {
        if (isUnbounded()) {
            throw new IllegalStateException("Cannot get size of unbounded VARCHAR.");
        }
        return length;
    }

    public boolean isUnbounded()
    {
        return length == UNBOUNDED_LENGTH;
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

        return block.getSlice(position, 0, block.getSliceLength(position)).toStringUtf8();
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
        int leftLength = leftBlock.getSliceLength(leftPosition);
        int rightLength = rightBlock.getSliceLength(rightPosition);
        return leftBlock.compareTo(leftPosition, 0, leftLength, rightBlock, rightPosition, 0, rightLength);
    }

    @Override
    public Optional<Range> getRange()
    {
        if (length > 100) {
            // The max/min values may be materialized in the plan, so we don't want them to be too large.
            // Range comparison against large values are usually nonsensical, too, so no need to support them
            // beyond a certain size. They specific choice above is arbitrary and can be adjusted if needed.
            return Optional.empty();
        }

        int codePointSize = SliceUtf8.lengthOfCodePoint(MAX_CODE_POINT);

        Slice max = Slices.allocate(codePointSize * length);
        int position = 0;
        for (int i = 0; i < length; i++) {
            position += SliceUtf8.setCodePointAt(MAX_CODE_POINT, max, position);
        }

        return Optional.of(new Range(Slices.EMPTY_SLICE, max));
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

        VarcharType other = (VarcharType) o;

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
        if (length == UNBOUNDED_LENGTH) {
            return getBaseName();
        }

        return getTypeSignature().toString();
    }

    @Override
    public String toString()
    {
        return getDisplayName();
    }
}
