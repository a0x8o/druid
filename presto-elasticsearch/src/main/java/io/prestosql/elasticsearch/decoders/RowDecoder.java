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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.prestosql.elasticsearch.ElasticsearchPageSource.getField;
import static io.prestosql.spi.StandardErrorCode.TYPE_MISMATCH;

public class RowDecoder
        implements Decoder
{
    private final List<String> fieldNames;
    private final List<Decoder> decoders;

    public RowDecoder(List<String> fieldNames, List<Decoder> decoders)
    {
        this.fieldNames = fieldNames;
        this.decoders = decoders;
    }

    @Override
    public void decode(SearchHit hit, Supplier<Object> getter, BlockBuilder output)
    {
        Object data = getter.get();

        if (data == null) {
            output.appendNull();
        }
        else if (data instanceof Map) {
            BlockBuilder row = output.beginBlockEntry();
            for (int i = 0; i < decoders.size(); i++) {
                String field = fieldNames.get(i);
                decoders.get(i).decode(hit, () -> getField((Map<String, Object>) data, field), row);
            }
            output.closeEntry();
        }
        else {
            throw new PrestoException(TYPE_MISMATCH, "Expected object for ROW field");
        }
    }
}
