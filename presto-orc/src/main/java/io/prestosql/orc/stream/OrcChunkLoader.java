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
package io.prestosql.orc.stream;

import io.airlift.slice.Slice;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.OrcDataSourceId;
import io.prestosql.orc.OrcDecompressor;

import java.io.IOException;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public interface OrcChunkLoader
{
    static OrcChunkLoader create(
            OrcDataSourceId orcDataSourceId,
            Slice chunk,
            Optional<OrcDecompressor> decompressor,
            AggregatedMemoryContext systemMemoryContext)
    {
        return create(new MemoryOrcDataReader(orcDataSourceId, chunk, chunk.length()), decompressor, systemMemoryContext);
    }

    static OrcChunkLoader create(
            OrcDataReader dataReader,
            Optional<OrcDecompressor> decompressor,
            AggregatedMemoryContext memoryContext)
    {
        requireNonNull(dataReader, "dataReader is null");
        requireNonNull(decompressor, "decompressor is null");
        requireNonNull(memoryContext, "memoryContext is null");

        if (decompressor.isPresent()) {
            return new CompressedOrcChunkLoader(dataReader, decompressor.get(), memoryContext);
        }
        return new UncompressedOrcChunkLoader(dataReader, memoryContext);
    }

    OrcDataSourceId getOrcDataSourceId();

    boolean hasNextChunk();

    Slice nextChunk()
            throws IOException;

    long getLastCheckpoint();

    void seekToCheckpoint(long checkpoint)
            throws IOException;
}
