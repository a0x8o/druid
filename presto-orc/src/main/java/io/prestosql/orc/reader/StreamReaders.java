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
package io.prestosql.orc.reader;

import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.StreamDescriptor;
import io.prestosql.spi.type.Type;

public final class StreamReaders
{
    private StreamReaders() {}

    public static StreamReader createStreamReader(Type type, StreamDescriptor streamDescriptor, AggregatedMemoryContext systemMemoryContext)
            throws OrcCorruptionException
    {
        switch (streamDescriptor.getStreamType()) {
            case BOOLEAN:
                return new BooleanStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case BYTE:
                return new ByteStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case SHORT:
            case INT:
            case LONG:
            case DATE:
                return new LongStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case FLOAT:
                return new FloatStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case DOUBLE:
                return new DoubleStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case BINARY:
            case STRING:
            case VARCHAR:
            case CHAR:
                return new SliceStreamReader(type, streamDescriptor, systemMemoryContext);
            case TIMESTAMP:
                return new TimestampStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case LIST:
                return new ListStreamReader(type, streamDescriptor, systemMemoryContext);
            case STRUCT:
                return new StructStreamReader(type, streamDescriptor, systemMemoryContext);
            case MAP:
                return new MapStreamReader(type, streamDescriptor, systemMemoryContext);
            case DECIMAL:
                return new DecimalStreamReader(type, streamDescriptor, systemMemoryContext.newLocalMemoryContext(StreamReaders.class.getSimpleName()));
            case UNION:
            default:
                throw new IllegalArgumentException("Unsupported type: " + streamDescriptor.getStreamType());
        }
    }
}
