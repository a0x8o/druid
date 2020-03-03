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
package io.prestosql.elasticsearch.decoders;

import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.BlockBuilder;
import org.elasticsearch.search.SearchHit;

import java.util.function.Supplier;

import static io.prestosql.spi.StandardErrorCode.TYPE_MISMATCH;
import static io.prestosql.spi.type.SmallintType.SMALLINT;

public class SmallintDecoder
        implements Decoder
{
    @Override
    public void decode(SearchHit hit, Supplier<Object> getter, BlockBuilder output)
    {
        Object value = getter.get();
        if (value == null) {
            output.appendNull();
        }
        else if (value instanceof Number) {
            long decoded = ((Number) value).longValue();

            if (decoded < Short.MIN_VALUE || decoded > Short.MAX_VALUE) {
                throw new PrestoException(TYPE_MISMATCH, "Value out of range for SMALLINT field");
            }

            SMALLINT.writeLong(output, decoded);
        }
        else {
            throw new PrestoException(TYPE_MISMATCH, "Expected a numeric value for SMALLINT field");
        }
    }
}
