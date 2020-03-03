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
package com.facebook.presto.orc;

import com.facebook.presto.memory.context.AggregatedMemoryContext;
import com.facebook.presto.orc.metadata.MetadataReader;
import com.facebook.presto.orc.metadata.OrcType;
import com.facebook.presto.orc.metadata.PostScript.HiveWriterVersion;
import com.facebook.presto.orc.metadata.StripeInformation;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.metadata.statistics.StripeStatistics;
import com.facebook.presto.orc.reader.BatchStreamReader;
import com.facebook.presto.orc.reader.BatchStreamReaders;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import com.google.common.annotations.VisibleForTesting;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OrcBatchRecordReader
        extends AbstractOrcRecordReader<BatchStreamReader>
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(OrcBatchRecordReader.class).instanceSize();

    private final Map<Integer, Type> includedColumns;

    public OrcBatchRecordReader(
            Map<Integer, Type> includedColumns,
            OrcPredicate predicate,
            long numberOfRows,
            List<StripeInformation> fileStripes,
            List<ColumnStatistics> fileStats,
            List<StripeStatistics> stripeStats,
            OrcDataSource orcDataSource,
            long splitOffset,
            long splitLength,
            List<OrcType> types,
            Optional<OrcDecompressor> decompressor,
            int rowsInRowGroup,
            DateTimeZone hiveStorageTimeZone,
            HiveWriterVersion hiveWriterVersion,
            MetadataReader metadataReader,
            DataSize maxMergeDistance,
            DataSize tinyStripeThreshold,
            DataSize maxBlockSize,
            Map<String, Slice> userMetadata,
            AggregatedMemoryContext systemMemoryUsage,
            Optional<OrcWriteValidation> writeValidation,
            int initialBatchSize)
    {
        super(includedColumns,
                // The streamReadersSystemMemoryContext covers the StreamReader local buffer sizes, plus leaf node StreamReaders'
                // instance sizes who use local buffers. SliceDirectStreamReader's instance size is not counted, because it
                // doesn't have a local buffer. All non-leaf level StreamReaders' (e.g. MapStreamReader, LongStreamReader,
                // ListStreamReader and StructStreamReader) instance sizes were not counted, because calling setBytes() in
                // their constructors is confusing.
                createStreamReaders(orcDataSource, types, hiveStorageTimeZone, includedColumns),
                predicate,
                numberOfRows,
                fileStripes,
                fileStats,
                stripeStats,
                orcDataSource,
                splitOffset,
                splitLength,
                types,
                decompressor,
                rowsInRowGroup,
                hiveStorageTimeZone,
                hiveWriterVersion,
                metadataReader,
                maxMergeDistance,
                tinyStripeThreshold,
                maxBlockSize,
                userMetadata,
                systemMemoryUsage,
                writeValidation,
                initialBatchSize);

        this.includedColumns = includedColumns;
    }

    public int nextBatch()
            throws IOException
    {
        int batchSize = prepareNextBatch();
        if (batchSize < 0) {
            return batchSize;
        }

        for (BatchStreamReader column : getStreamReaders()) {
            if (column != null) {
                column.prepareNextRead(batchSize);
            }
        }
        batchRead(batchSize);

        validateWritePageChecksum(batchSize);
        return batchSize;
    }

    public Block readBlock(Type type, int columnIndex)
            throws IOException
    {
        Block block = getStreamReaders()[columnIndex].readBlock(type);
        updateMaxCombinedBytesPerRow(columnIndex, block);
        return block;
    }

    /**
     * @return The total size of memory retained by this OrcRecordReader
     */
    @VisibleForTesting
    @Override
    protected long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + super.getRetainedSizeInBytes();
    }

    private void validateWritePageChecksum(int batchSize)
            throws IOException
    {
        if (shouldValidateWritePageChecksum()) {
            Block[] blocks = new Block[getStreamReaders().length];
            for (int columnIndex = 0; columnIndex < getStreamReaders().length; columnIndex++) {
                blocks[columnIndex] = readBlock(includedColumns.get(columnIndex), columnIndex);
            }
            Page page = new Page(batchSize, blocks);
            validateWritePageChecksum(page);
        }
    }

    private static BatchStreamReader[] createStreamReaders(
            OrcDataSource orcDataSource,
            List<OrcType> types,
            DateTimeZone hiveStorageTimeZone,
            Map<Integer, Type> includedColumns)
    {
        List<StreamDescriptor> streamDescriptors = createStreamDescriptor("", "", 0, types, orcDataSource).getNestedStreams();

        OrcType rowType = types.get(0);
        BatchStreamReader[] streamReaders = new BatchStreamReader[rowType.getFieldCount()];
        for (int columnId = 0; columnId < rowType.getFieldCount(); columnId++) {
            if (includedColumns.containsKey(columnId)) {
                StreamDescriptor streamDescriptor = streamDescriptors.get(columnId);
                streamReaders[columnId] = BatchStreamReaders.createStreamReader(streamDescriptor, hiveStorageTimeZone);
            }
        }
        return streamReaders;
    }
}
