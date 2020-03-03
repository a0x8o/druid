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
package com.facebook.presto.orc.stream;

import com.facebook.presto.orc.checkpoint.LongStreamCheckpoint;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.Type;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkPositionIndex;
import static java.lang.Math.toIntExact;

public interface LongInputStream
        extends ValueInputStream<LongStreamCheckpoint>
{
    long next()
            throws IOException;

    default void nextIntVector(int items, int[] vector, int offset)
            throws IOException
    {
        checkPositionIndex(items + offset, vector.length);

        for (int i = offset; i < items + offset; i++) {
            vector[i] = toIntExact(next());
        }
    }

    default void nextIntVector(int items, int[] vector, int vectorOffset, boolean[] isNull)
            throws IOException
    {
        checkPositionIndex(items + vectorOffset, vector.length);
        checkPositionIndex(items, isNull.length);

        for (int i = 0; i < items; i++) {
            if (!isNull[i]) {
                vector[i + vectorOffset] = toIntExact(next());
            }
        }
    }

    default void nextLongVector(int items, long[] vector)
            throws IOException
    {
        checkPositionIndex(items, vector.length);

        for (int i = 0; i < items; i++) {
            vector[i] = next();
        }
    }

    default void nextLongVector(Type type, int items, BlockBuilder builder)
            throws IOException
    {
        for (int i = 0; i < items; i++) {
            type.writeLong(builder, next());
        }
    }

    default long sum(int items)
            throws IOException
    {
        long sum = 0;
        for (int i = 0; i < items; i++) {
            sum += next();
        }
        return sum;
    }
}
