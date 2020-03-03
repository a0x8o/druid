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
package com.facebook.presto.execution.buffer;

import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.type.TestingTypeManager;
import com.facebook.presto.spiller.SpillCipher;
import io.airlift.compress.Compressor;
import io.airlift.compress.Decompressor;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;

import java.util.Optional;

public class TestingPagesSerdeFactory
        extends PagesSerdeFactory
{
    public TestingPagesSerdeFactory()
    {
        // compression should be enabled in as many tests as possible
        super(new BlockEncodingManager(new TestingTypeManager()), true);
    }

    public static PagesSerde testingPagesSerde()
    {
        return new SynchronizedPagesSerde(
                new BlockEncodingManager(new TestingTypeManager()),
                Optional.of(new Lz4Compressor()),
                Optional.of(new Lz4Decompressor()),
                Optional.empty());
    }

    private static class SynchronizedPagesSerde
            extends PagesSerde
    {
        public SynchronizedPagesSerde(BlockEncodingSerde blockEncodingSerde, Optional<Compressor> compressor, Optional<Decompressor> decompressor, Optional<SpillCipher> spillCipher)
        {
            super(blockEncodingSerde, compressor, decompressor, spillCipher);
        }

        @Override
        public synchronized SerializedPage serialize(Page page)
        {
            return super.serialize(page);
        }

        @Override
        public synchronized Page deserialize(SerializedPage serializedPage)
        {
            return super.deserialize(serializedPage);
        }
    }
}
