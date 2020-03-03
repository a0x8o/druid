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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.plugin.hive.HdfsEnvironment.HdfsContext;
import io.prestosql.plugin.hive.HiveSplit.BucketConversion;
import io.prestosql.plugin.hive.util.HiveBucketing.BucketingVersion;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorPageSourceProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.FixedPageSource;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.connector.RecordPageSource;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Maps.uniqueIndex;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.PARTITION_KEY;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.SYNTHESIZED;
import static io.prestosql.plugin.hive.HivePageSourceProvider.ColumnMapping.toColumnHandles;
import static io.prestosql.plugin.hive.util.HiveUtil.getPrefilledColumnValue;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class HivePageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final DateTimeZone hiveStorageTimeZone;
    private final HdfsEnvironment hdfsEnvironment;
    private final Set<HiveRecordCursorProvider> cursorProviders;
    private final TypeManager typeManager;

    private final Set<HivePageSourceFactory> pageSourceFactories;

    @Inject
    public HivePageSourceProvider(
            HiveConfig hiveConfig,
            HdfsEnvironment hdfsEnvironment,
            Set<HiveRecordCursorProvider> cursorProviders,
            Set<HivePageSourceFactory> pageSourceFactories,
            TypeManager typeManager)
    {
        requireNonNull(hiveConfig, "hiveConfig is null");
        this.hiveStorageTimeZone = hiveConfig.getDateTimeZone();
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.cursorProviders = ImmutableSet.copyOf(requireNonNull(cursorProviders, "cursorProviders is null"));
        this.pageSourceFactories = ImmutableSet.copyOf(requireNonNull(pageSourceFactories, "pageSourceFactories is null"));
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    @Override
    public ConnectorPageSource createPageSource(ConnectorTransactionHandle transaction, ConnectorSession session, ConnectorSplit split, ConnectorTableHandle table, List<ColumnHandle> columns)
    {
        return createPageSource(transaction, session, split, table, columns, TupleDomain.all());
    }

    @Override
    public ConnectorPageSource createPageSource(ConnectorTransactionHandle transaction, ConnectorSession session, ConnectorSplit split, ConnectorTableHandle table, List<ColumnHandle> columns, TupleDomain<ColumnHandle> dynamicFilter)
    {
        HiveTableHandle hiveTable = (HiveTableHandle) table;

        List<HiveColumnHandle> hiveColumns = columns.stream()
                .map(HiveColumnHandle.class::cast)
                .collect(toList());

        HiveSplit hiveSplit = (HiveSplit) split;
        Path path = new Path(hiveSplit.getPath());

        Configuration configuration = hdfsEnvironment.getConfiguration(new HdfsContext(session, hiveSplit.getDatabase(), hiveSplit.getTable()), path);

        Optional<ConnectorPageSource> pageSource = createHivePageSource(
                cursorProviders,
                pageSourceFactories,
                configuration,
                session,
                path,
                hiveSplit.getBucketNumber(),
                hiveSplit.getStart(),
                hiveSplit.getLength(),
                hiveSplit.getFileSize(),
                hiveSplit.getFileModifiedTime(),
                hiveSplit.getSchema(),
                hiveTable.getCompactEffectivePredicate().intersect(dynamicFilter.transform(HiveColumnHandle.class::cast)),
                hiveColumns,
                hiveSplit.getPartitionKeys(),
                hiveStorageTimeZone,
                typeManager,
                hiveSplit.getColumnCoercions(),
                hiveSplit.getBucketConversion(),
                hiveSplit.isS3SelectPushdownEnabled());
        if (pageSource.isPresent()) {
            return pageSource.get();
        }
        throw new RuntimeException("Could not find a file reader for split " + hiveSplit);
    }

    public static Optional<ConnectorPageSource> createHivePageSource(
            Set<HiveRecordCursorProvider> cursorProviders,
            Set<HivePageSourceFactory> pageSourceFactories,
            Configuration configuration,
            ConnectorSession session,
            Path path,
            OptionalInt bucketNumber,
            long start,
            long length,
            long fileSize,
            long fileModifiedTime,
            Properties schema,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            List<HiveColumnHandle> hiveColumns,
            List<HivePartitionKey> partitionKeys,
            DateTimeZone hiveStorageTimeZone,
            TypeManager typeManager,
            Map<Integer, HiveType> columnCoercions,
            Optional<BucketConversion> bucketConversion,
            boolean s3SelectPushdownEnabled)
    {
        if (effectivePredicate.isNone()) {
            return Optional.of(new FixedPageSource(ImmutableList.of()));
        }

        List<ColumnMapping> columnMappings = ColumnMapping.buildColumnMappings(
                partitionKeys,
                hiveColumns,
                bucketConversion.map(BucketConversion::getBucketColumnHandles).orElse(ImmutableList.of()),
                columnCoercions,
                path,
                bucketNumber,
                fileSize,
                fileModifiedTime);
        List<ColumnMapping> regularAndInterimColumnMappings = ColumnMapping.extractRegularAndInterimColumnMappings(columnMappings);

        Optional<BucketAdaptation> bucketAdaptation = bucketConversion.map(conversion -> {
            Map<Integer, ColumnMapping> hiveIndexToBlockIndex = uniqueIndex(regularAndInterimColumnMappings, columnMapping -> columnMapping.getHiveColumnHandle().getHiveColumnIndex());
            int[] bucketColumnIndices = conversion.getBucketColumnHandles().stream()
                    .mapToInt(columnHandle -> hiveIndexToBlockIndex.get(columnHandle.getHiveColumnIndex()).getIndex())
                    .toArray();
            List<HiveType> bucketColumnHiveTypes = conversion.getBucketColumnHandles().stream()
                    .map(columnHandle -> hiveIndexToBlockIndex.get(columnHandle.getHiveColumnIndex()).getHiveColumnHandle().getHiveType())
                    .collect(toImmutableList());
            return new BucketAdaptation(
                    bucketColumnIndices,
                    bucketColumnHiveTypes,
                    conversion.getBucketingVersion(),
                    conversion.getTableBucketCount(),
                    conversion.getPartitionBucketCount(),
                    bucketNumber.getAsInt());
        });

        for (HivePageSourceFactory pageSourceFactory : pageSourceFactories) {
            Optional<? extends ConnectorPageSource> pageSource = pageSourceFactory.createPageSource(
                    configuration,
                    session,
                    path,
                    start,
                    length,
                    fileSize,
                    schema,
                    toColumnHandles(regularAndInterimColumnMappings, true),
                    effectivePredicate,
                    hiveStorageTimeZone);
            if (pageSource.isPresent()) {
                return Optional.of(
                        new HivePageSource(
                                columnMappings,
                                bucketAdaptation,
                                hiveStorageTimeZone,
                                typeManager,
                                pageSource.get()));
            }
        }

        for (HiveRecordCursorProvider provider : cursorProviders) {
            // GenericHiveRecordCursor will automatically do the coercion without HiveCoercionRecordCursor
            boolean doCoercion = !(provider instanceof GenericHiveRecordCursorProvider);

            Optional<RecordCursor> cursor = provider.createRecordCursor(
                    configuration,
                    session,
                    path,
                    start,
                    length,
                    fileSize,
                    schema,
                    toColumnHandles(regularAndInterimColumnMappings, doCoercion),
                    effectivePredicate,
                    hiveStorageTimeZone,
                    typeManager,
                    s3SelectPushdownEnabled);

            if (cursor.isPresent()) {
                RecordCursor delegate = cursor.get();

                if (bucketAdaptation.isPresent()) {
                    delegate = new HiveBucketAdapterRecordCursor(
                            bucketAdaptation.get().getBucketColumnIndices(),
                            bucketAdaptation.get().getBucketColumnHiveTypes(),
                            bucketAdaptation.get().getBucketingVersion(),
                            bucketAdaptation.get().getTableBucketCount(),
                            bucketAdaptation.get().getPartitionBucketCount(),
                            bucketAdaptation.get().getBucketToKeep(),
                            typeManager,
                            delegate);
                }

                // Need to wrap RcText and RcBinary into a wrapper, which will do the coercion for mismatch columns
                if (doCoercion) {
                    delegate = new HiveCoercionRecordCursor(regularAndInterimColumnMappings, typeManager, delegate);
                }

                HiveRecordCursor hiveRecordCursor = new HiveRecordCursor(
                        columnMappings,
                        hiveStorageTimeZone,
                        typeManager,
                        delegate);
                List<Type> columnTypes = hiveColumns.stream()
                        .map(input -> typeManager.getType(input.getTypeSignature()))
                        .collect(toList());

                return Optional.of(new RecordPageSource(columnTypes, hiveRecordCursor));
            }
        }

        return Optional.empty();
    }

    public static class ColumnMapping
    {
        private final ColumnMappingKind kind;
        private final HiveColumnHandle hiveColumnHandle;
        private final Optional<String> prefilledValue;
        /**
         * ordinal of this column in the underlying page source or record cursor
         */
        private final OptionalInt index;
        private final Optional<HiveType> coercionFrom;

        public static ColumnMapping regular(HiveColumnHandle hiveColumnHandle, int index, Optional<HiveType> coerceFrom)
        {
            checkArgument(hiveColumnHandle.getColumnType() == REGULAR);
            return new ColumnMapping(ColumnMappingKind.REGULAR, hiveColumnHandle, Optional.empty(), OptionalInt.of(index), coerceFrom);
        }

        public static ColumnMapping prefilled(HiveColumnHandle hiveColumnHandle, String prefilledValue, Optional<HiveType> coerceFrom)
        {
            checkArgument(hiveColumnHandle.getColumnType() == PARTITION_KEY || hiveColumnHandle.getColumnType() == SYNTHESIZED);
            return new ColumnMapping(ColumnMappingKind.PREFILLED, hiveColumnHandle, Optional.of(prefilledValue), OptionalInt.empty(), coerceFrom);
        }

        public static ColumnMapping interim(HiveColumnHandle hiveColumnHandle, int index)
        {
            checkArgument(hiveColumnHandle.getColumnType() == REGULAR);
            return new ColumnMapping(ColumnMappingKind.INTERIM, hiveColumnHandle, Optional.empty(), OptionalInt.of(index), Optional.empty());
        }

        private ColumnMapping(ColumnMappingKind kind, HiveColumnHandle hiveColumnHandle, Optional<String> prefilledValue, OptionalInt index, Optional<HiveType> coerceFrom)
        {
            this.kind = requireNonNull(kind, "kind is null");
            this.hiveColumnHandle = requireNonNull(hiveColumnHandle, "hiveColumnHandle is null");
            this.prefilledValue = requireNonNull(prefilledValue, "prefilledValue is null");
            this.index = requireNonNull(index, "index is null");
            this.coercionFrom = requireNonNull(coerceFrom, "coerceFrom is null");
        }

        public ColumnMappingKind getKind()
        {
            return kind;
        }

        public String getPrefilledValue()
        {
            checkState(kind == ColumnMappingKind.PREFILLED);
            return prefilledValue.get();
        }

        public HiveColumnHandle getHiveColumnHandle()
        {
            return hiveColumnHandle;
        }

        public int getIndex()
        {
            checkState(kind == ColumnMappingKind.REGULAR || kind == ColumnMappingKind.INTERIM);
            return index.getAsInt();
        }

        public Optional<HiveType> getCoercionFrom()
        {
            return coercionFrom;
        }

        /**
         * @param columns columns that need to be returned to engine
         * @param requiredInterimColumns columns that are needed for processing, but shouldn't be returned to engine (may overlaps with columns)
         * @param columnCoercions map from hive column index to hive type
         * @param bucketNumber empty if table is not bucketed, a number within [0, # bucket in table) otherwise
         */
        public static List<ColumnMapping> buildColumnMappings(
                List<HivePartitionKey> partitionKeys,
                List<HiveColumnHandle> columns,
                List<HiveColumnHandle> requiredInterimColumns,
                Map<Integer, HiveType> columnCoercions,
                Path path,
                OptionalInt bucketNumber,
                long fileSize,
                long fileModifiedTime)
        {
            Map<String, HivePartitionKey> partitionKeysByName = uniqueIndex(partitionKeys, HivePartitionKey::getName);
            int regularIndex = 0;
            Set<Integer> regularColumnIndices = new HashSet<>();
            ImmutableList.Builder<ColumnMapping> columnMappings = ImmutableList.builder();
            for (HiveColumnHandle column : columns) {
                Optional<HiveType> coercionFrom = Optional.ofNullable(columnCoercions.get(column.getHiveColumnIndex()));
                if (column.getColumnType() == REGULAR) {
                    checkArgument(regularColumnIndices.add(column.getHiveColumnIndex()), "duplicate hiveColumnIndex in columns list");
                    columnMappings.add(regular(column, regularIndex, coercionFrom));
                    regularIndex++;
                }
                else {
                    columnMappings.add(prefilled(
                            column,
                            getPrefilledColumnValue(column, partitionKeysByName.get(column.getName()), path, bucketNumber, fileSize, fileModifiedTime),
                            coercionFrom));
                }
            }
            for (HiveColumnHandle column : requiredInterimColumns) {
                checkArgument(column.getColumnType() == REGULAR);
                if (regularColumnIndices.contains(column.getHiveColumnIndex())) {
                    continue; // This column exists in columns. Do not add it again.
                }
                // If coercion does not affect bucket number calculation, coercion doesn't need to be applied here.
                // Otherwise, read of this partition should not be allowed.
                // (Alternatively, the partition could be read as an unbucketed partition. This is not implemented.)
                columnMappings.add(interim(column, regularIndex));
                regularIndex++;
            }
            return columnMappings.build();
        }

        public static List<ColumnMapping> extractRegularAndInterimColumnMappings(List<ColumnMapping> columnMappings)
        {
            return columnMappings.stream()
                    .filter(columnMapping -> columnMapping.getKind() == ColumnMappingKind.REGULAR || columnMapping.getKind() == ColumnMappingKind.INTERIM)
                    .collect(toImmutableList());
        }

        public static List<HiveColumnHandle> toColumnHandles(List<ColumnMapping> regularColumnMappings, boolean doCoercion)
        {
            return regularColumnMappings.stream()
                    .map(columnMapping -> {
                        HiveColumnHandle columnHandle = columnMapping.getHiveColumnHandle();
                        if (!doCoercion || !columnMapping.getCoercionFrom().isPresent()) {
                            return columnHandle;
                        }
                        return new HiveColumnHandle(
                                columnHandle.getName(),
                                columnMapping.getCoercionFrom().get(),
                                columnMapping.getCoercionFrom().get().getTypeSignature(),
                                columnHandle.getHiveColumnIndex(),
                                columnHandle.getColumnType(),
                                Optional.empty());
                    })
                    .collect(toList());
        }
    }

    public enum ColumnMappingKind
    {
        REGULAR,
        PREFILLED,
        INTERIM,
    }

    public static class BucketAdaptation
    {
        private final int[] bucketColumnIndices;
        private final List<HiveType> bucketColumnHiveTypes;
        private final BucketingVersion bucketingVersion;
        private final int tableBucketCount;
        private final int partitionBucketCount;
        private final int bucketToKeep;

        public BucketAdaptation(
                int[] bucketColumnIndices,
                List<HiveType> bucketColumnHiveTypes,
                BucketingVersion bucketingVersion,
                int tableBucketCount,
                int partitionBucketCount,
                int bucketToKeep)
        {
            this.bucketColumnIndices = bucketColumnIndices;
            this.bucketColumnHiveTypes = bucketColumnHiveTypes;
            this.bucketingVersion = bucketingVersion;
            this.tableBucketCount = tableBucketCount;
            this.partitionBucketCount = partitionBucketCount;
            this.bucketToKeep = bucketToKeep;
        }

        public int[] getBucketColumnIndices()
        {
            return bucketColumnIndices;
        }

        public List<HiveType> getBucketColumnHiveTypes()
        {
            return bucketColumnHiveTypes;
        }

        public BucketingVersion getBucketingVersion()
        {
            return bucketingVersion;
        }

        public int getTableBucketCount()
        {
            return tableBucketCount;
        }

        public int getPartitionBucketCount()
        {
            return partitionBucketCount;
        }

        public int getBucketToKeep()
        {
            return bucketToKeep;
        }
    }
}
