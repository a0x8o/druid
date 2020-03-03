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
package com.facebook.presto.raptor.storage;

import com.facebook.presto.memory.context.AggregatedMemoryContext;
import com.facebook.presto.orc.OrcBatchRecordReader;
import com.facebook.presto.orc.OrcDataSink;
import com.facebook.presto.orc.OrcDataSource;
import com.facebook.presto.orc.OrcPredicate;
import com.facebook.presto.orc.OrcReader;
import com.facebook.presto.orc.OrcWriterStats;
import com.facebook.presto.orc.TupleDomainOrcPredicate;
import com.facebook.presto.orc.TupleDomainOrcPredicate.ColumnReference;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.OrcType;
import com.facebook.presto.raptor.RaptorColumnHandle;
import com.facebook.presto.raptor.RaptorConnectorId;
import com.facebook.presto.raptor.backup.BackupManager;
import com.facebook.presto.raptor.backup.BackupStore;
import com.facebook.presto.raptor.metadata.ColumnInfo;
import com.facebook.presto.raptor.metadata.ColumnStats;
import com.facebook.presto.raptor.metadata.ShardDelta;
import com.facebook.presto.raptor.metadata.ShardInfo;
import com.facebook.presto.raptor.metadata.ShardRecorder;
import com.facebook.presto.raptor.storage.StorageManagerConfig.OrcOptimizedWriterStage;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.NodeManager;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.NamedTypeSignature;
import com.facebook.presto.spi.type.RowFieldName;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.spi.type.TypeSignatureParameter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.joda.time.DateTimeZone;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.facebook.presto.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static com.facebook.presto.orc.OrcEncoding.ORC;
import static com.facebook.presto.orc.OrcReader.INITIAL_BATCH_SIZE;
import static com.facebook.presto.raptor.RaptorColumnHandle.isBucketNumberColumn;
import static com.facebook.presto.raptor.RaptorColumnHandle.isHiddenColumn;
import static com.facebook.presto.raptor.RaptorColumnHandle.isShardRowIdColumn;
import static com.facebook.presto.raptor.RaptorColumnHandle.isShardUuidColumn;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_ERROR;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_LOCAL_DISK_FULL;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_RECOVERY_ERROR;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_RECOVERY_TIMEOUT;
import static com.facebook.presto.raptor.filesystem.FileSystemUtil.xxhash64;
import static com.facebook.presto.raptor.storage.OrcPageSource.BUCKET_NUMBER_COLUMN;
import static com.facebook.presto.raptor.storage.OrcPageSource.NULL_COLUMN;
import static com.facebook.presto.raptor.storage.OrcPageSource.ROWID_COLUMN;
import static com.facebook.presto.raptor.storage.OrcPageSource.SHARD_UUID_COLUMN;
import static com.facebook.presto.raptor.storage.ShardStats.computeColumnStats;
import static com.facebook.presto.raptor.storage.StorageManagerConfig.OrcOptimizedWriterStage.ENABLED_AND_VALIDATED;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.CharType.createCharType;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static io.airlift.concurrent.MoreFutures.allAsList;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.airlift.units.DataSize.Unit.PETABYTE;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toList;
import static org.joda.time.DateTimeZone.UTC;

public class OrcStorageManager
        implements StorageManager
{
    // Raptor does not store time-related data types as they are in ORC.
    // They will be casted to BIGINT or INTEGER to avoid timezone conversion.
    // This is due to the historical reason of using the legacy ORC read/writer that does not support timestamp types.
    // In order to be consistent, we still enforce the conversion.
    // The following DEFAULT_STORAGE_TIMEZONE is not used by the optimized ORC read/writer given we never read/write timestamp types.
    public static final DateTimeZone DEFAULT_STORAGE_TIMEZONE = UTC;
    // TODO: do not limit the max size of blocks to read for now; enable the limit when the Hive connector is ready
    public static final DataSize HUGE_MAX_READ_BLOCK_SIZE = new DataSize(1, PETABYTE);
    private static final JsonCodec<ShardDelta> SHARD_DELTA_CODEC = jsonCodec(ShardDelta.class);

    private static final long MAX_ROWS = 1_000_000_000;
    private static final JsonCodec<OrcFileMetadata> METADATA_CODEC = jsonCodec(OrcFileMetadata.class);

    private final String nodeId;
    private final StorageService storageService;
    private final Optional<BackupStore> backupStore;
    private final ReaderAttributes defaultReaderAttributes;
    private final BackupManager backupManager;
    private final ShardRecoveryManager recoveryManager;
    private final ShardRecorder shardRecorder;
    private final Duration recoveryTimeout;
    private final long maxShardRows;
    private final DataSize maxShardSize;
    private final DataSize minAvailableSpace;
    private final CompressionKind compression;
    private final OrcOptimizedWriterStage orcOptimizedWriterStage;
    private final TypeManager typeManager;
    private final ExecutorService deletionExecutor;
    private final ExecutorService commitExecutor;
    private final OrcDataEnvironment orcDataEnvironment;
    private final FileSystem fileSystem;
    private final OrcFileRewriter fileRewriter;
    private final OrcWriterStats stats = new OrcWriterStats();

    @Inject
    public OrcStorageManager(
            NodeManager nodeManager,
            StorageService storageService,
            Optional<BackupStore> backupStore,
            ReaderAttributes readerAttributes,
            StorageManagerConfig config,
            RaptorConnectorId connectorId,
            BackupManager backgroundBackupManager,
            ShardRecoveryManager recoveryManager,
            ShardRecorder shardRecorder,
            TypeManager typeManager,
            OrcDataEnvironment orcDataEnvironment)
    {
        this(nodeManager.getCurrentNode().getNodeIdentifier(),
                storageService,
                backupStore,
                readerAttributes,
                backgroundBackupManager,
                recoveryManager,
                shardRecorder,
                typeManager,
                orcDataEnvironment,
                connectorId.toString(),
                config.getDeletionThreads(),
                config.getShardRecoveryTimeout(),
                config.getMaxShardRows(),
                config.getMaxShardSize(),
                config.getMinAvailableSpace(),
                config.getOrcCompressionKind(),
                config.getOrcOptimizedWriterStage());
    }

    public OrcStorageManager(
            String nodeId,
            StorageService storageService,
            Optional<BackupStore> backupStore,
            ReaderAttributes readerAttributes,
            BackupManager backgroundBackupManager,
            ShardRecoveryManager recoveryManager,
            ShardRecorder shardRecorder,
            TypeManager typeManager,
            OrcDataEnvironment orcDataEnvironment,
            String connectorId,
            int deletionThreads,
            Duration shardRecoveryTimeout,
            long maxShardRows,
            DataSize maxShardSize,
            DataSize minAvailableSpace,
            CompressionKind compression,
            OrcOptimizedWriterStage orcOptimizedWriterStage)
    {
        this.nodeId = requireNonNull(nodeId, "nodeId is null");
        this.storageService = requireNonNull(storageService, "storageService is null");
        this.backupStore = requireNonNull(backupStore, "backupStore is null");
        this.defaultReaderAttributes = requireNonNull(readerAttributes, "readerAttributes is null");

        backupManager = requireNonNull(backgroundBackupManager, "backgroundBackupManager is null");
        this.recoveryManager = requireNonNull(recoveryManager, "recoveryManager is null");
        this.recoveryTimeout = requireNonNull(shardRecoveryTimeout, "shardRecoveryTimeout is null");

        checkArgument(maxShardRows > 0, "maxShardRows must be > 0");
        this.maxShardRows = min(maxShardRows, MAX_ROWS);
        this.maxShardSize = requireNonNull(maxShardSize, "maxShardSize is null");
        this.minAvailableSpace = requireNonNull(minAvailableSpace, "minAvailableSpace is null");
        this.shardRecorder = requireNonNull(shardRecorder, "shardRecorder is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.deletionExecutor = newFixedThreadPool(deletionThreads, daemonThreadsNamed("raptor-delete-" + connectorId + "-%s"));
        this.commitExecutor = newCachedThreadPool(daemonThreadsNamed("raptor-commit-" + connectorId + "-%s"));
        this.compression = requireNonNull(compression, "compression is null");
        this.orcOptimizedWriterStage = requireNonNull(orcOptimizedWriterStage, "orcOptimizedWriterStage is null");
        this.orcDataEnvironment = requireNonNull(orcDataEnvironment, "orcDataEnvironment is null");
        this.fileSystem = requireNonNull(orcDataEnvironment.getFileSystem(), "fileSystem is null");
        this.fileRewriter = new OrcFileRewriter(readerAttributes, orcOptimizedWriterStage.equals(ENABLED_AND_VALIDATED), stats, typeManager, orcDataEnvironment, compression);
    }

    @PreDestroy
    public void shutdown()
    {
        deletionExecutor.shutdownNow();
        commitExecutor.shutdown();
    }

    @Override
    public ConnectorPageSource getPageSource(
            UUID shardUuid,
            OptionalInt bucketNumber,
            List<Long> columnIds,
            List<Type> columnTypes,
            TupleDomain<RaptorColumnHandle> effectivePredicate,
            ReaderAttributes readerAttributes,
            OptionalLong transactionId,
            Optional<Map<String, Type>> allColumnTypes)
    {
        OrcDataSource dataSource = openShard(shardUuid, readerAttributes);

        AggregatedMemoryContext systemMemoryUsage = newSimpleAggregatedMemoryContext();

        try {
            OrcReader reader = new OrcReader(dataSource, ORC, readerAttributes.getMaxMergeDistance(), readerAttributes.getTinyStripeThreshold(), HUGE_MAX_READ_BLOCK_SIZE);

            Map<Long, Integer> indexMap = columnIdIndex(reader.getColumnNames());
            ImmutableMap.Builder<Integer, Type> includedColumns = ImmutableMap.builder();
            ImmutableList.Builder<Integer> columnIndexes = ImmutableList.builder();
            for (int i = 0; i < columnIds.size(); i++) {
                long columnId = columnIds.get(i);
                if (isHiddenColumn(columnId)) {
                    columnIndexes.add(toSpecialIndex(columnId));
                    continue;
                }

                Integer index = indexMap.get(columnId);
                if (index == null) {
                    columnIndexes.add(NULL_COLUMN);
                }
                else {
                    columnIndexes.add(index);
                    includedColumns.put(index, columnTypes.get(i));
                }
            }

            OrcPredicate predicate = getPredicate(effectivePredicate, indexMap);

            OrcBatchRecordReader recordReader = reader.createBatchRecordReader(includedColumns.build(), predicate, DEFAULT_STORAGE_TIMEZONE, systemMemoryUsage, INITIAL_BATCH_SIZE);

            Optional<ShardRewriter> shardRewriter = Optional.empty();
            if (transactionId.isPresent()) {
                checkState(allColumnTypes.isPresent());
                shardRewriter = Optional.of(createShardRewriter(transactionId.getAsLong(), bucketNumber, shardUuid, allColumnTypes.get()));
            }

            return new OrcPageSource(shardRewriter, recordReader, dataSource, columnIds, columnTypes, columnIndexes.build(), shardUuid, bucketNumber, systemMemoryUsage);
        }
        catch (IOException | RuntimeException e) {
            closeQuietly(dataSource);
            throw new PrestoException(RAPTOR_ERROR, "Failed to create page source for shard " + shardUuid, e);
        }
        catch (Throwable t) {
            closeQuietly(dataSource);
            throw t;
        }
    }

    private static int toSpecialIndex(long columnId)
    {
        if (isShardRowIdColumn(columnId)) {
            return ROWID_COLUMN;
        }
        if (isShardUuidColumn(columnId)) {
            return SHARD_UUID_COLUMN;
        }
        if (isBucketNumberColumn(columnId)) {
            return BUCKET_NUMBER_COLUMN;
        }
        throw new PrestoException(RAPTOR_ERROR, "Invalid column ID: " + columnId);
    }

    @Override
    public StoragePageSink createStoragePageSink(long transactionId, OptionalInt bucketNumber, List<Long> columnIds, List<Type> columnTypes, boolean checkSpace)
    {
        if (checkSpace && storageService.getAvailableBytes() < minAvailableSpace.toBytes()) {
            throw new PrestoException(RAPTOR_LOCAL_DISK_FULL, "Local disk is full on node " + nodeId);
        }
        return new OrcStoragePageSink(transactionId, columnIds, columnTypes, bucketNumber);
    }

    private ShardRewriter createShardRewriter(long transactionId, OptionalInt bucketNumber, UUID shardUuid, Map<String, Type> columns)
    {
        return rowsToDelete -> {
            if (rowsToDelete.isEmpty()) {
                return completedFuture(ImmutableList.of());
            }
            return supplyAsync(() -> rewriteShard(transactionId, bucketNumber, shardUuid, columns, rowsToDelete), deletionExecutor);
        };
    }

    private void writeShard(UUID shardUuid)
    {
        if (backupStore.isPresent() && !backupStore.get().shardExists(shardUuid)) {
            throw new PrestoException(RAPTOR_ERROR, "Backup does not exist after write");
        }

        Path stagingFile = storageService.getStagingFile(shardUuid);
        Path storageFile = storageService.getStorageFile(shardUuid);

        storageService.createParents(storageFile);

        try {
            fileSystem.rename(stagingFile, storageFile);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to move shard file", e);
        }
    }

    @VisibleForTesting
    OrcDataSource openShard(UUID shardUuid, ReaderAttributes readerAttributes)
    {
        Path file = storageService.getStorageFile(shardUuid);

        boolean exists;
        try {
            exists = fileSystem.exists(file);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Error locating file " + file, e.getCause());
        }

        if (!exists && backupStore.isPresent()) {
            try {
                Future<?> future = recoveryManager.recoverShard(shardUuid);
                future.get(recoveryTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            catch (ExecutionException e) {
                if (e.getCause() != null) {
                    throwIfInstanceOf(e.getCause(), PrestoException.class);
                }
                throw new PrestoException(RAPTOR_RECOVERY_ERROR, "Error recovering shard " + shardUuid, e.getCause());
            }
            catch (TimeoutException e) {
                throw new PrestoException(RAPTOR_RECOVERY_TIMEOUT, "Shard is being recovered from backup. Please retry in a few minutes: " + shardUuid);
            }
        }

        try {
            return orcDataEnvironment.createOrcDataSource(file, readerAttributes);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to open shard file: " + file, e);
        }
    }

    private ShardInfo createShardInfo(UUID shardUuid, OptionalInt bucketNumber, Path file, Set<String> nodes, long rowCount, long uncompressedSize)
    {
        try {
            return new ShardInfo(shardUuid, bucketNumber, nodes, computeShardStats(file), rowCount, fileSystem.getFileStatus(file).getLen(), uncompressedSize, xxhash64(fileSystem, file));
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to get file status: " + file, e);
        }
    }

    private List<ColumnStats> computeShardStats(Path file)
    {
        try (OrcDataSource dataSource = orcDataEnvironment.createOrcDataSource(file, defaultReaderAttributes)) {
            OrcReader reader = new OrcReader(dataSource, ORC, defaultReaderAttributes.getMaxMergeDistance(), defaultReaderAttributes.getTinyStripeThreshold(), HUGE_MAX_READ_BLOCK_SIZE);

            ImmutableList.Builder<ColumnStats> list = ImmutableList.builder();
            for (ColumnInfo info : getColumnInfo(reader)) {
                computeColumnStats(reader, info.getColumnId(), info.getType()).ifPresent(list::add);
            }
            return list.build();
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to read file: " + file, e);
        }
    }

    @VisibleForTesting
    Collection<Slice> rewriteShard(long transactionId, OptionalInt bucketNumber, UUID shardUuid, Map<String, Type> columns, BitSet rowsToDelete)
    {
        if (rowsToDelete.isEmpty()) {
            return ImmutableList.of();
        }

        UUID newShardUuid = UUID.randomUUID();
        Path input = storageService.getStorageFile(shardUuid);
        Path output = storageService.getStagingFile(newShardUuid);

        OrcFileInfo info = rewriteFile(columns, input, output, rowsToDelete);
        long rowCount = info.getRowCount();

        if (rowCount == 0) {
            return shardDelta(shardUuid, Optional.empty());
        }

        shardRecorder.recordCreatedShard(transactionId, newShardUuid);

        // submit for backup and wait until it finishes
        getFutureValue(backupManager.submit(newShardUuid, output));

        Set<String> nodes = ImmutableSet.of(nodeId);
        long uncompressedSize = info.getUncompressedSize();

        ShardInfo shard = createShardInfo(newShardUuid, bucketNumber, output, nodes, rowCount, uncompressedSize);

        writeShard(newShardUuid);

        return shardDelta(shardUuid, Optional.of(shard));
    }

    private static Collection<Slice> shardDelta(UUID oldShardUuid, Optional<ShardInfo> shardInfo)
    {
        List<ShardInfo> newShards = shardInfo.map(ImmutableList::of).orElse(ImmutableList.of());
        ShardDelta delta = new ShardDelta(ImmutableList.of(oldShardUuid), newShards);
        return ImmutableList.of(Slices.wrappedBuffer(SHARD_DELTA_CODEC.toJsonBytes(delta)));
    }

    private OrcFileInfo rewriteFile(Map<String, Type> columns, Path input, Path output, BitSet rowsToDelete)
    {
        try {
            return fileRewriter.rewrite(columns, input, output, rowsToDelete);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to rewrite shard file: " + input, e);
        }
    }

    private List<ColumnInfo> getColumnInfo(OrcReader reader)
    {
        Optional<OrcFileMetadata> metadata = getOrcFileMetadata(reader);
        if (metadata.isPresent()) {
            return getColumnInfoFromOrcUserMetadata(metadata.get());
        }

        // support for legacy files without metadata
        return getColumnInfoFromOrcColumnTypes(reader.getColumnNames(), reader.getFooter().getTypes());
    }

    private List<ColumnInfo> getColumnInfoFromOrcColumnTypes(List<String> orcColumnNames, List<OrcType> orcColumnTypes)
    {
        Type rowType = getType(orcColumnTypes, 0);
        if (orcColumnNames.size() != rowType.getTypeParameters().size()) {
            throw new PrestoException(RAPTOR_ERROR, "Column names and types do not match");
        }

        ImmutableList.Builder<ColumnInfo> list = ImmutableList.builder();
        for (int i = 0; i < orcColumnNames.size(); i++) {
            list.add(new ColumnInfo(Long.parseLong(orcColumnNames.get(i)), rowType.getTypeParameters().get(i)));
        }
        return list.build();
    }

    private static Optional<OrcFileMetadata> getOrcFileMetadata(OrcReader reader)
    {
        return Optional.ofNullable(reader.getFooter().getUserMetadata().get(OrcFileMetadata.KEY))
                .map(slice -> METADATA_CODEC.fromJson(slice.getBytes()));
    }

    private List<ColumnInfo> getColumnInfoFromOrcUserMetadata(OrcFileMetadata orcFileMetadata)
    {
        return orcFileMetadata.getColumnTypes().entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ColumnInfo(entry.getKey(), typeManager.getType(entry.getValue())))
                .collect(toList());
    }

    private Type getType(List<OrcType> types, int index)
    {
        OrcType type = types.get(index);
        switch (type.getOrcTypeKind()) {
            case BOOLEAN:
                return BOOLEAN;
            case LONG:
                return BIGINT;
            case DOUBLE:
                return DOUBLE;
            case STRING:
                return createUnboundedVarcharType();
            case VARCHAR:
                return createVarcharType(type.getLength().get());
            case CHAR:
                return createCharType(type.getLength().get());
            case BINARY:
                return VARBINARY;
            case DECIMAL:
                return DecimalType.createDecimalType(type.getPrecision().get(), type.getScale().get());
            case LIST:
                TypeSignature elementType = getType(types, type.getFieldTypeIndex(0)).getTypeSignature();
                return typeManager.getParameterizedType(StandardTypes.ARRAY, ImmutableList.of(TypeSignatureParameter.of(elementType)));
            case MAP:
                TypeSignature keyType = getType(types, type.getFieldTypeIndex(0)).getTypeSignature();
                TypeSignature valueType = getType(types, type.getFieldTypeIndex(1)).getTypeSignature();
                return typeManager.getParameterizedType(StandardTypes.MAP, ImmutableList.of(TypeSignatureParameter.of(keyType), TypeSignatureParameter.of(valueType)));
            case STRUCT:
                List<String> fieldNames = type.getFieldNames();
                ImmutableList.Builder<TypeSignatureParameter> fieldTypes = ImmutableList.builder();
                for (int i = 0; i < type.getFieldCount(); i++) {
                    fieldTypes.add(TypeSignatureParameter.of(new NamedTypeSignature(
                            Optional.of(new RowFieldName(fieldNames.get(i), false)),
                            getType(types, type.getFieldTypeIndex(i)).getTypeSignature())));
                }
                return typeManager.getParameterizedType(StandardTypes.ROW, fieldTypes.build());
        }
        throw new PrestoException(RAPTOR_ERROR, "Unhandled ORC type: " + type);
    }

    private static OrcPredicate getPredicate(TupleDomain<RaptorColumnHandle> effectivePredicate, Map<Long, Integer> indexMap)
    {
        ImmutableList.Builder<ColumnReference<RaptorColumnHandle>> columns = ImmutableList.builder();
        for (RaptorColumnHandle column : effectivePredicate.getDomains().get().keySet()) {
            Integer index = indexMap.get(column.getColumnId());
            if (index != null) {
                columns.add(new ColumnReference<>(column, index, column.getColumnType()));
            }
        }
        return new TupleDomainOrcPredicate<>(effectivePredicate, columns.build(), false, Optional.empty());
    }

    private static Map<Long, Integer> columnIdIndex(List<String> columnNames)
    {
        ImmutableMap.Builder<Long, Integer> map = ImmutableMap.builder();
        for (int i = 0; i < columnNames.size(); i++) {
            map.put(Long.valueOf(columnNames.get(i)), i);
        }
        return map.build();
    }

    private class OrcStoragePageSink
            implements StoragePageSink
    {
        private final long transactionId;
        private final List<Long> columnIds;
        private final List<Type> columnTypes;
        private final OptionalInt bucketNumber;

        private final List<Path> stagingFiles = new ArrayList<>();
        private final List<ShardInfo> shards = new ArrayList<>();
        private final List<CompletableFuture<?>> futures = new ArrayList<>();

        private boolean committed;
        private FileWriter writer;
        private UUID shardUuid;

        public OrcStoragePageSink(
                long transactionId,
                List<Long> columnIds,
                List<Type> columnTypes,
                OptionalInt bucketNumber)
        {
            this.transactionId = transactionId;
            this.columnIds = ImmutableList.copyOf(requireNonNull(columnIds, "columnIds is null"));
            this.columnTypes = ImmutableList.copyOf(requireNonNull(columnTypes, "columnTypes is null"));
            this.bucketNumber = requireNonNull(bucketNumber, "bucketNumber is null");
        }

        @Override
        public void appendPages(List<Page> pages)
        {
            createWriterIfNecessary();
            writer.appendPages(pages);
        }

        @Override
        public void appendPages(List<Page> inputPages, int[] pageIndexes, int[] positionIndexes)
        {
            createWriterIfNecessary();
            writer.appendPages(inputPages, pageIndexes, positionIndexes);
        }

        @Override
        public boolean isFull()
        {
            if (writer == null) {
                return false;
            }
            return (writer.getRowCount() >= maxShardRows) || (writer.getUncompressedSize() >= maxShardSize.toBytes());
        }

        @Override
        public void flush()
        {
            if (writer != null) {
                try {
                    writer.close();
                }
                catch (IOException e) {
                    throw new PrestoException(RAPTOR_ERROR, "Failed to close writer", e);
                }

                shardRecorder.recordCreatedShard(transactionId, shardUuid);

                Path stagingFile = storageService.getStagingFile(shardUuid);
                futures.add(backupManager.submit(shardUuid, stagingFile));

                Set<String> nodes = ImmutableSet.of(nodeId);
                long rowCount = writer.getRowCount();
                long uncompressedSize = writer.getUncompressedSize();

                shards.add(createShardInfo(shardUuid, bucketNumber, stagingFile, nodes, rowCount, uncompressedSize));

                writer = null;
                shardUuid = null;
            }
        }

        @Override
        public CompletableFuture<List<ShardInfo>> commit()
        {
            checkState(!committed, "already committed");
            committed = true;

            flush();

            return allAsList(futures).thenApplyAsync(ignored -> {
                for (ShardInfo shard : shards) {
                    writeShard(shard.getShardUuid());
                }
                return ImmutableList.copyOf(shards);
            }, commitExecutor);
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        @Override
        public void rollback()
        {
            try {
                if (writer != null) {
                    try {
                        writer.close();
                    }
                    catch (IOException e) {
                        throw new PrestoException(RAPTOR_ERROR, "Failed to close writer", e);
                    }
                    finally {
                        writer = null;
                    }
                }
            }
            finally {
                for (Path file : stagingFiles) {
                    try {
                        fileSystem.delete(file, false);
                    }
                    catch (IOException e) {
                        // ignore
                    }
                }

                // cancel incomplete backup jobs
                futures.forEach(future -> future.cancel(true));

                // delete completed backup shards
                backupStore.ifPresent(backupStore -> {
                    for (ShardInfo shard : shards) {
                        backupStore.deleteShard(shard.getShardUuid());
                    }
                });
            }
        }

        private void createWriterIfNecessary()
        {
            if (writer == null) {
                shardUuid = UUID.randomUUID();
                Path stagingFile = storageService.getStagingFile(shardUuid);
                storageService.createParents(stagingFile);
                stagingFiles.add(stagingFile);
                OrcDataSink sink;
                try {
                    sink = orcDataEnvironment.createOrcDataSink(stagingFile);
                }
                catch (IOException e) {
                    throw new PrestoException(RAPTOR_ERROR, format("Failed to create staging file %s", stagingFile), e);
                }
                writer = new OrcFileWriter(columnIds, columnTypes, sink, orcOptimizedWriterStage.equals(ENABLED_AND_VALIDATED), stats, typeManager, compression);
            }
        }
    }

    private static void closeQuietly(Closeable closeable)
    {
        try {
            closeable.close();
        }
        catch (IOException ignored) {
        }
    }
}
