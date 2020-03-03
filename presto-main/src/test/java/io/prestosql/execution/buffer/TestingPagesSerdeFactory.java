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
package io.prestosql.execution.buffer;

import io.airlift.compress.Compressor;
import io.airlift.compress.Decompressor;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.BlockEncodingSerde;
import io.prestosql.spiller.SpillCipher;

import java.util.Optional;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;

public class TestingPagesSerdeFactory
        extends PagesSerdeFactory
{
    public TestingPagesSerdeFactory()
    {
        // compression should be enabled in as many tests as possible
        super(createTestMetadataManager().getBlockEncodingSerde(), true);
    }

    public static PagesSerde testingPagesSerde()
    {
        return new SynchronizedPagesSerde(
                createTestMetadataManager().getBlockEncodingSerde(),
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
