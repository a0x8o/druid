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
package io.prestosql.plugin.hive;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.stats.CounterStat;
import io.airlift.units.DataSize;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Column;
import io.prestosql.plugin.hive.metastore.Partition;
import io.prestosql.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.util.HiveBucketing.HiveBucketFilter;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.VersionEmbedder;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedSplitSource;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterables.transform;
import static io.prestosql.plugin.hive.BackgroundHiveSplitLoader.BucketSplitInfo.createBucketSplitInfo;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_PARTITION_DROPPED_DURING_QUERY;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_PARTITION_SCHEMA_MISMATCH;
import static io.prestosql.plugin.hive.HivePartition.UNPARTITIONED_ID;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.getProtectMode;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.makePartName;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.verifyOnline;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.StandardErrorCode.SERVER_SHUTTING_DOWN;
import static io.prestosql.spi.connector.ConnectorSplitManager.SplitSchedulingStrategy.GROUPED_SCHEDULING;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HiveSplitManager
        implements ConnectorSplitManager
{
    public static final String PRESTO_OFFLINE = "presto_offline";
    public static final String OBJECT_NOT_READABLE = "object_not_readable";

    private final Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider;
    private final HivePartitionManager partitionManager;
    private final NamenodeStats namenodeStats;
    private final HdfsEnvironment hdfsEnvironment;
    private final DirectoryLister directoryLister;
    private final Executor executor;
    private final CoercionPolicy coercionPolicy;
    private final int maxOutstandingSplits;
    private final DataSize maxOutstandingSplitsSize;
    private final int minPartitionBatchSize;
    private final int maxPartitionBatchSize;
    private final int maxInitialSplits;
    private final int splitLoaderConcurrency;
    private final int maxSplitsPerSecond;
    private final boolean recursiveDfsWalkerEnabled;
    private final CounterStat highMemorySplitSourceCounter;

    @Inject
    public HiveSplitManager(
            HiveConfig hiveConfig,
            Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider,
            HivePartitionManager partitionManager,
            NamenodeStats namenodeStats,
            HdfsEnvironment hdfsEnvironment,
            DirectoryLister directoryLister,
            @ForHive ExecutorService executorService,
            VersionEmbedder versionEmbedder,
            CoercionPolicy coercionPolicy)
    {
        this(
                metastoreProvider,
                partitionManager,
                namenodeStats,
                hdfsEnvironment,
                directoryLister,
                versionEmbedder.embedVersion(new BoundedExecutor(executorService, hiveConfig.getMaxSplitIteratorThreads())),
                coercionPolicy,
                new CounterStat(),
                hiveConfig.getMaxOutstandingSplits(),
                hiveConfig.getMaxOutstandingSplitsSize(),
                hiveConfig.getMinPartitionBatchSize(),
                hiveConfig.getMaxPartitionBatchSize(),
                hiveConfig.getMaxInitialSplits(),
                hiveConfig.getSplitLoaderConcurrency(),
                hiveConfig.getMaxSplitsPerSecond(),
                hiveConfig.getRecursiveDirWalkerEnabled());
    }

    public HiveSplitManager(
            Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider,
            HivePartitionManager partitionManager,
            NamenodeStats namenodeStats,
            HdfsEnvironment hdfsEnvironment,
            DirectoryLister directoryLister,
            Executor executor,
            CoercionPolicy coercionPolicy,
            CounterStat highMemorySplitSourceCounter,
            int maxOutstandingSplits,
            DataSize maxOutstandingSplitsSize,
            int minPartitionBatchSize,
            int maxPartitionBatchSize,
            int maxInitialSplits,
            int splitLoaderConcurrency,
            @Nullable Integer maxSplitsPerSecond,
            boolean recursiveDfsWalkerEnabled)
    {
        this.metastoreProvider = requireNonNull(metastoreProvider, "metastore is null");
        this.partitionManager = requireNonNull(partitionManager, "partitionManager is null");
        this.namenodeStats = requireNonNull(namenodeStats, "namenodeStats is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.directoryLister = requireNonNull(directoryLister, "directoryLister is null");
        this.executor = new ErrorCodedExecutor(executor);
        this.coercionPolicy = requireNonNull(coercionPolicy, "coercionPolicy is null");
        this.highMemorySplitSourceCounter = requireNonNull(highMemorySplitSourceCounter, "highMemorySplitSourceCounter is null");
        checkArgument(maxOutstandingSplits >= 1, "maxOutstandingSplits must be at least 1");
        this.maxOutstandingSplits = maxOutstandingSplits;
        this.maxOutstandingSplitsSize = maxOutstandingSplitsSize;
        this.minPartitionBatchSize = minPartitionBatchSize;
        this.maxPartitionBatchSize = maxPartitionBatchSize;
        this.maxInitialSplits = maxInitialSplits;
        this.splitLoaderConcurrency = splitLoaderConcurrency;
        this.maxSplitsPerSecond = firstNonNull(maxSplitsPerSecond, Integer.MAX_VALUE);
        this.recursiveDfsWalkerEnabled = recursiveDfsWalkerEnabled;
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            SplitSchedulingStrategy splitSchedulingStrategy)
    {
        HiveTableHandle hiveTable = (HiveTableHandle) tableHandle;
        SchemaTableName tableName = hiveTable.getSchemaTableName();

        // get table metadata
        SemiTransactionalHiveMetastore metastore = metastoreProvider.apply((HiveTransactionHandle) transaction);
        Table table = metastore.getTable(new HiveIdentity(session), tableName.getSchemaName(), tableName.getTableName())
                .orElseThrow(() -> new TableNotFoundException(tableName));

        // verify table is not marked as non-readable
        String tableNotReadable = table.getParameters().get(OBJECT_NOT_READABLE);
        if (!isNullOrEmpty(tableNotReadable)) {
            throw new HiveNotReadableException(tableName, Optional.empty(), tableNotReadable);
        }

        // get partitions
        List<HivePartition> partitions = partitionManager.getOrLoadPartitions(metastore, new HiveIdentity(session), hiveTable);

        // short circuit if we don't have any partitions
        if (partitions.isEmpty()) {
            return new FixedSplitSource(ImmutableList.of());
        }

        // get buckets from first partition (arbitrary)
        Optional<HiveBucketFilter> bucketFilter = hiveTable.getBucketFilter();

        // validate bucket bucketed execution
        Optional<HiveBucketHandle> bucketHandle = hiveTable.getBucketHandle();
        if ((splitSchedulingStrategy == GROUPED_SCHEDULING) && !bucketHandle.isPresent()) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "SchedulingPolicy is bucketed, but BucketHandle is not present");
        }

        // sort partitions
        partitions = Ordering.natural().onResultOf(HivePartition::getPartitionId).reverse().sortedCopy(partitions);

        Iterable<HivePartitionMetadata> hivePartitions = getPartitionMetadata(session, metastore, table, tableName, partitions, bucketHandle.map(HiveBucketHandle::toTableBucketProperty));

        HiveSplitLoader hiveSplitLoader = new BackgroundHiveSplitLoader(
                table,
                hivePartitions,
                hiveTable.getCompactEffectivePredicate(),
                createBucketSplitInfo(bucketHandle, bucketFilter),
                session,
                hdfsEnvironment,
                namenodeStats,
                directoryLister,
                executor,
                splitLoaderConcurrency,
                recursiveDfsWalkerEnabled);

        HiveSplitSource splitSource;
        switch (splitSchedulingStrategy) {
            case UNGROUPED_SCHEDULING:
                splitSource = HiveSplitSource.allAtOnce(
                        session,
                        table.getDatabaseName(),
                        table.getTableName(),
                        maxInitialSplits,
                        maxOutstandingSplits,
                        maxOutstandingSplitsSize,
                        maxSplitsPerSecond,
                        hiveSplitLoader,
                        executor,
                        new CounterStat());
                break;
            case GROUPED_SCHEDULING:
                splitSource = HiveSplitSource.bucketed(
                        session,
                        table.getDatabaseName(),
                        table.getTableName(),
                        maxInitialSplits,
                        maxOutstandingSplits,
                        maxOutstandingSplitsSize,
                        maxSplitsPerSecond,
                        hiveSplitLoader,
                        executor,
                        new CounterStat());
                break;
            default:
                throw new IllegalArgumentException("Unknown splitSchedulingStrategy: " + splitSchedulingStrategy);
        }
        hiveSplitLoader.start(splitSource);

        return splitSource;
    }

    @Managed
    @Nested
    public CounterStat getHighMemorySplitSource()
    {
        return highMemorySplitSourceCounter;
    }

    private Iterable<HivePartitionMetadata> getPartitionMetadata(ConnectorSession session, SemiTransactionalHiveMetastore metastore, Table table, SchemaTableName tableName, List<HivePartition> hivePartitions, Optional<HiveBucketProperty> bucketProperty)
    {
        if (hivePartitions.isEmpty()) {
            return ImmutableList.of();
        }

        if (hivePartitions.size() == 1) {
            HivePartition firstPartition = getOnlyElement(hivePartitions);
            if (firstPartition.getPartitionId().equals(UNPARTITIONED_ID)) {
                return ImmutableList.of(new HivePartitionMetadata(firstPartition, Optional.empty(), ImmutableMap.of()));
            }
        }

        Iterable<List<HivePartition>> partitionNameBatches = partitionExponentially(hivePartitions, minPartitionBatchSize, maxPartitionBatchSize);
        Iterable<List<HivePartitionMetadata>> partitionBatches = transform(partitionNameBatches, partitionBatch -> {
            Map<String, Optional<Partition>> batch = metastore.getPartitionsByNames(
                    new HiveIdentity(session),
                    tableName.getSchemaName(),
                    tableName.getTableName(),
                    Lists.transform(partitionBatch, HivePartition::getPartitionId));
            ImmutableMap.Builder<String, Partition> partitionBuilder = ImmutableMap.builder();
            for (Map.Entry<String, Optional<Partition>> entry : batch.entrySet()) {
                if (!entry.getValue().isPresent()) {
                    throw new PrestoException(HIVE_PARTITION_DROPPED_DURING_QUERY, "Partition no longer exists: " + entry.getKey());
                }
                partitionBuilder.put(entry.getKey(), entry.getValue().get());
            }
            Map<String, Partition> partitions = partitionBuilder.build();
            if (partitionBatch.size() != partitions.size()) {
                throw new PrestoException(GENERIC_INTERNAL_ERROR, format("Expected %s partitions but found %s", partitionBatch.size(), partitions.size()));
            }

            ImmutableList.Builder<HivePartitionMetadata> results = ImmutableList.builder();
            for (HivePartition hivePartition : partitionBatch) {
                Partition partition = partitions.get(hivePartition.getPartitionId());
                if (partition == null) {
                    throw new PrestoException(GENERIC_INTERNAL_ERROR, "Partition not loaded: " + hivePartition);
                }
                String partName = makePartName(table.getPartitionColumns(), partition.getValues());

                // verify partition is online
                verifyOnline(tableName, Optional.of(partName), getProtectMode(partition), partition.getParameters());

                // verify partition is not marked as non-readable
                String partitionNotReadable = partition.getParameters().get(OBJECT_NOT_READABLE);
                if (!isNullOrEmpty(partitionNotReadable)) {
                    throw new HiveNotReadableException(tableName, Optional.of(partName), partitionNotReadable);
                }

                // Verify that the partition schema matches the table schema.
                // Either adding or dropping columns from the end of the table
                // without modifying existing partitions is allowed, but every
                // column that exists in both the table and partition must have
                // the same type.
                List<Column> tableColumns = table.getDataColumns();
                List<Column> partitionColumns = partition.getColumns();
                if ((tableColumns == null) || (partitionColumns == null)) {
                    throw new PrestoException(HIVE_INVALID_METADATA, format("Table '%s' or partition '%s' has null columns", tableName, partName));
                }
                ImmutableMap.Builder<Integer, HiveTypeName> columnCoercions = ImmutableMap.builder();
                for (int i = 0; i < min(partitionColumns.size(), tableColumns.size()); i++) {
                    HiveType tableType = tableColumns.get(i).getType();
                    HiveType partitionType = partitionColumns.get(i).getType();
                    if (!tableType.equals(partitionType)) {
                        if (!coercionPolicy.canCoerce(partitionType, tableType)) {
                            throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, format("" +
                                            "There is a mismatch between the table and partition schemas. " +
                                            "The types are incompatible and cannot be coerced. " +
                                            "The column '%s' in table '%s' is declared as type '%s', " +
                                            "but partition '%s' declared column '%s' as type '%s'.",
                                    tableColumns.get(i).getName(),
                                    tableName,
                                    tableType,
                                    partName,
                                    partitionColumns.get(i).getName(),
                                    partitionType));
                        }
                        columnCoercions.put(i, partitionType.getHiveTypeName());
                    }
                }

                if (bucketProperty.isPresent()) {
                    Optional<HiveBucketProperty> partitionBucketProperty = partition.getStorage().getBucketProperty();
                    if (!partitionBucketProperty.isPresent()) {
                        throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, format(
                                "Hive table (%s) is bucketed but partition (%s) is not bucketed",
                                hivePartition.getTableName(),
                                hivePartition.getPartitionId()));
                    }
                    int tableBucketCount = bucketProperty.get().getBucketCount();
                    int partitionBucketCount = partitionBucketProperty.get().getBucketCount();
                    List<String> tableBucketColumns = bucketProperty.get().getBucketedBy();
                    List<String> partitionBucketColumns = partitionBucketProperty.get().getBucketedBy();
                    if (!tableBucketColumns.equals(partitionBucketColumns) || !isBucketCountCompatible(tableBucketCount, partitionBucketCount)) {
                        throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, format(
                                "Hive table (%s) bucketing (columns=%s, buckets=%s) is not compatible with partition (%s) bucketing (columns=%s, buckets=%s)",
                                hivePartition.getTableName(),
                                tableBucketColumns,
                                tableBucketCount,
                                hivePartition.getPartitionId(),
                                partitionBucketColumns,
                                partitionBucketCount));
                    }
                }

                results.add(new HivePartitionMetadata(hivePartition, Optional.of(partition), columnCoercions.build()));
            }

            return results.build();
        });
        return concat(partitionBatches);
    }

    static boolean isBucketCountCompatible(int tableBucketCount, int partitionBucketCount)
    {
        checkArgument(tableBucketCount > 0 && partitionBucketCount > 0);
        int larger = Math.max(tableBucketCount, partitionBucketCount);
        int smaller = min(tableBucketCount, partitionBucketCount);
        if (larger % smaller != 0) {
            // must be evenly divisible
            return false;
        }
        // ratio must be power of two
        return Integer.bitCount(larger / smaller) == 1;
    }

    /**
     * Partition the given list in exponentially (power of 2) increasing batch sizes starting at 1 up to maxBatchSize
     */
    private static <T> Iterable<List<T>> partitionExponentially(List<T> values, int minBatchSize, int maxBatchSize)
    {
        return () -> new AbstractIterator<List<T>>()
        {
            private int currentSize = minBatchSize;
            private final Iterator<T> iterator = values.iterator();

            @Override
            protected List<T> computeNext()
            {
                if (!iterator.hasNext()) {
                    return endOfData();
                }

                int count = 0;
                ImmutableList.Builder<T> builder = ImmutableList.builder();
                while (iterator.hasNext() && count < currentSize) {
                    builder.add(iterator.next());
                    ++count;
                }

                currentSize = min(maxBatchSize, currentSize * 2);
                return builder.build();
            }
        };
    }

    private static class ErrorCodedExecutor
            implements Executor
    {
        private final Executor delegate;

        private ErrorCodedExecutor(Executor delegate)
        {
            this.delegate = requireNonNull(delegate, "delegate is null");
        }

        @Override
        public void execute(Runnable command)
        {
            try {
                delegate.execute(command);
            }
            catch (RejectedExecutionException e) {
                throw new PrestoException(SERVER_SHUTTING_DOWN, "Server is shutting down", e);
            }
        }
    }
}
