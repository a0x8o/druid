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

import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;

import static io.prestosql.spi.type.DateTimeEncoding.unpackMillisUtc;

public final class TimeWithTimeZoneType
        extends AbstractLongType
{
    public static final TimeWithTimeZoneType TIME_WITH_TIME_ZONE = new TimeWithTimeZoneType();

    private TimeWithTimeZoneType()
    {
        super(new TypeSignature(StandardTypes.TIME_WITH_TIME_ZONE));
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return new SqlTimeWithTimeZone(block.getLong(position, 0));
    }

    @Override
    public boolean equalTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        long leftValue = unpackMillisUtc(leftBlock.getLong(leftPosition, 0));
        long rightValue = unpackMillisUtc(rightBlock.getLong(rightPosition, 0));
        return leftValue == rightValue;
    }

    public long hash(Block block, int position)
    {
        return AbstractLongType.hash(unpackMillisUtc(block.getLong(position, 0)));
    }

    @Override
    public int compareTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        long leftValue = unpackMillisUtc(leftBlock.getLong(leftPosition, 0));
        long rightValue = unpackMillisUtc(rightBlock.getLong(rightPosition, 0));
        return Long.compare(leftValue, rightValue);
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object other)
    {
        return other == TIME_WITH_TIME_ZONE;
    }

    @Override
    public int hashCode()
    {
        return getClass().hashCode();
    }
}
