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
package com.facebook.presto.block;

import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.SliceOutput;
import io.airlift.slice.Slices;

import javax.inject.Inject;

import java.io.IOException;
import java.util.Base64;

import static com.facebook.presto.block.BlockSerdeUtil.readBlock;
import static com.facebook.presto.block.BlockSerdeUtil.writeBlock;
import static java.util.Objects.requireNonNull;

public final class BlockJsonSerde
{
    private BlockJsonSerde() {}

    public static class Serializer
            extends JsonSerializer<Block>
    {
        private final BlockEncodingSerde blockEncodingSerde;

        @Inject
        public Serializer(BlockEncodingSerde blockEncodingSerde)
        {
            this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        }

        @Override
        public void serialize(Block block, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
                throws IOException
        {
            SliceOutput output = new DynamicSliceOutput(64);
            writeBlock(blockEncodingSerde, output, block);
            String encoded = Base64.getEncoder().encodeToString(output.slice().getBytes());
            jsonGenerator.writeString(encoded);
        }
    }

    public static class Deserializer
            extends JsonDeserializer<Block>
    {
        private final BlockEncodingSerde blockEncodingSerde;

        @Inject
        public Deserializer(BlockEncodingSerde blockEncodingSerde)
        {
            this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        }

        @Override
        public Block deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                throws IOException
        {
            byte[] decoded = Base64.getDecoder().decode(jsonParser.getText());
            return readBlock(blockEncodingSerde, Slices.wrappedBuffer(decoded));
        }
    }
}
