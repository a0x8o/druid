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
package com.facebook.presto.hive;

import com.facebook.presto.hive.HdfsEnvironment.HdfsContext;
import com.facebook.presto.hive.HiveSessionProperties.InsertExistingPartitionsBehavior;
import com.facebook.presto.hive.LocationService.WriteInfo;
import com.facebook.presto.hive.PartitionUpdate.UpdateMode;
import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.HivePageSinkMetadataProvider;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.SortingColumn;
import com.facebook.presto.hive.metastore.StorageFormat;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.NodeManager;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageSorter;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.spi.session.PropertyMetadata;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.event.client.EventClient;
import io.airlift.units.DataSize;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapred.JobConf;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import static com.facebook.presto.hive.HiveCompressionCodec.NONE;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_FILESYSTEM_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PARTITION_READ_ONLY;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PARTITION_SCHEMA_MISMATCH;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PATH_ALREADY_EXISTS;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_UNSUPPORTED_FORMAT;
import static com.facebook.presto.hive.HiveSessionProperties.getSortedWriteTempPathSubdirectoryCount;
import static com.facebook.presto.hive.HiveSessionProperties.isSortedWriteToTempPathEnabled;
import static com.facebook.presto.hive.HiveType.toHiveTypes;
import static com.facebook.presto.hive.HiveWriteUtils.createPartitionValues;
import static com.facebook.presto.hive.LocationHandle.WriteMode.DIRECT_TO_TARGET_EXISTING_DIRECTORY;
import static com.facebook.presto.hive.PartitionUpdate.FileWriteInfo;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getHiveSchema;
import static com.facebook.presto.hive.metastore.PrestoTableType.TEMPORARY_TABLE;
import static com.facebook.presto.hive.metastore.StorageFormat.fromHiveStorageFormat;
import static com.facebook.presto.hive.util.ConfigurationUtils.configureCompression;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMNS;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMN_TYPES;

public class HiveWriterFactory
{
    private static final int MAX_BUCKET_COUNT = 100_000;
    private static final int BUCKET_NUMBER_PADDING = Integer.toString(MAX_BUCKET_COUNT - 1).length();

    private final Set<HiveFileWriterFactory> fileWriterFactories;
    private final String schemaName;
    private final String tableName;

    private final List<DataColumn> dataColumns;

    private final List<String> partitionColumnNames;
    private final List<Type> partitionColumnTypes;

    private final HiveStorageFormat tableStorageFormat;
    private final HiveStorageFormat partitionStorageFormat;
    private final HiveCompressionCodec compressionCodec;
    private final LocationHandle locationHandle;
    private final LocationService locationService;
    private final String filePrefix;

    private final HivePageSinkMetadataProvider pageSinkMetadataProvider;
    private final TypeManager typeManager;
    private final HdfsEnvironment hdfsEnvironment;
    private final JobConf conf;

    private final Table table;
    private final Optional<SortingFileWriterFactory> sortingFileWriterFactory;
    private final boolean immutablePartitions;
    private final InsertExistingPartitionsBehavior insertExistingPartitionsBehavior;

    private final ConnectorSession session;
    private final OptionalInt bucketCount;

    private final NodeManager nodeManager;
    private final EventClient eventClient;
    private final Map<String, String> sessionProperties;

    private final HiveWriterStats hiveWriterStats;

    private final boolean partitionCommitRequired;

    public HiveWriterFactory(
            Set<HiveFileWriterFactory> fileWriterFactories,
            String schemaName,
            String tableName,
            boolean isCreateTable,
            List<HiveColumnHandle> inputColumns,
            HiveStorageFormat tableStorageFormat,
            HiveStorageFormat partitionStorageFormat,
            HiveCompressionCodec compressionCodec,
            OptionalInt bucketCount,
            List<SortingColumn> sortedBy,
            LocationHandle locationHandle,
            LocationService locationService,
            String filePrefix,
            HivePageSinkMetadataProvider pageSinkMetadataProvider,
            TypeManager typeManager,
            HdfsEnvironment hdfsEnvironment,
            PageSorter pageSorter,
            DataSize sortBufferSize,
            int maxOpenSortFiles,
            boolean immutablePartitions,
            ConnectorSession session,
            NodeManager nodeManager,
            EventClient eventClient,
            HiveSessionProperties hiveSessionProperties,
            HiveWriterStats hiveWriterStats,
            OrcFileWriterFactory orcFileWriterFactory,
            boolean partitionCommitRequired)
    {
        this.fileWriterFactories = ImmutableSet.copyOf(requireNonNull(fileWriterFactories, "fileWriterFactories is null"));
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");

        this.tableStorageFormat = requireNonNull(tableStorageFormat, "tableStorageFormat is null");
        this.partitionStorageFormat = requireNonNull(partitionStorageFormat, "partitionStorageFormat is null");
        this.compressionCodec = requireNonNull(compressionCodec, "compressionCodec is null");
        this.locationHandle = requireNonNull(locationHandle, "locationHandle is null");
        this.locationService = requireNonNull(locationService, "locationService is null");
        this.filePrefix = requireNonNull(filePrefix, "filePrefix is null");

        this.pageSinkMetadataProvider = requireNonNull(pageSinkMetadataProvider, "pageSinkMetadataProvider is null");

        this.typeManager = requireNonNull(typeManager, "typeManager is null");

        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.immutablePartitions = immutablePartitions;
        this.insertExistingPartitionsBehavior = HiveSessionProperties.getInsertExistingPartitionsBehavior(session);
        if (immutablePartitions) {
            checkArgument(insertExistingPartitionsBehavior != InsertExistingPartitionsBehavior.APPEND, "insertExistingPartitionsBehavior cannot be APPEND");
        }

        // divide input columns into partition and data columns
        requireNonNull(inputColumns, "inputColumns is null");
        ImmutableList.Builder<String> partitionColumnNames = ImmutableList.builder();
        ImmutableList.Builder<Type> partitionColumnTypes = ImmutableList.builder();
        ImmutableList.Builder<DataColumn> dataColumns = ImmutableList.builder();
        for (HiveColumnHandle column : inputColumns) {
            HiveType hiveType = column.getHiveType();
            if (column.isPartitionKey()) {
                partitionColumnNames.add(column.getName());
                partitionColumnTypes.add(typeManager.getType(column.getTypeSignature()));
            }
            else {
                dataColumns.add(new DataColumn(column.getName(), hiveType));
            }
        }
        this.partitionColumnNames = partitionColumnNames.build();
        this.partitionColumnTypes = partitionColumnTypes.build();
        this.dataColumns = dataColumns.build();

        Path writePath;
        if (isCreateTable) {
            this.table = null;
            WriteInfo writeInfo = locationService.getQueryWriteInfo(locationHandle);
            checkArgument(writeInfo.getWriteMode() != DIRECT_TO_TARGET_EXISTING_DIRECTORY, "CREATE TABLE write mode cannot be DIRECT_TO_TARGET_EXISTING_DIRECTORY");
            writePath = writeInfo.getWritePath();
        }
        else {
            Optional<Table> table = pageSinkMetadataProvider.getTable();
            if (!table.isPresent()) {
                throw new PrestoException(HIVE_INVALID_METADATA, format("Table %s.%s was dropped during insert", schemaName, tableName));
            }
            this.table = table.get();
            writePath = locationService.getQueryWriteInfo(locationHandle).getWritePath();
        }

        this.bucketCount = requireNonNull(bucketCount, "bucketCount is null");
        if (bucketCount.isPresent()) {
            checkArgument(bucketCount.getAsInt() < MAX_BUCKET_COUNT, "bucketCount must be smaller than " + MAX_BUCKET_COUNT);
        }

        this.session = requireNonNull(session, "session is null");
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.eventClient = requireNonNull(eventClient, "eventClient is null");

        requireNonNull(hiveSessionProperties, "hiveSessionProperties is null");
        this.sessionProperties = hiveSessionProperties.getSessionProperties().stream()
                .collect(toImmutableMap(PropertyMetadata::getName,
                        entry -> session.getProperty(entry.getName(), entry.getJavaType()).toString()));

        this.conf = configureCompression(hdfsEnvironment.getConfiguration(new HdfsContext(session, schemaName, tableName), writePath), compressionCodec);

        if (!sortedBy.isEmpty()) {
            List<Type> types = this.dataColumns.stream()
                    .map(column -> column.getHiveType().getType(typeManager))
                    .collect(toImmutableList());

            Map<String, Integer> columnIndexes = new HashMap<>();
            for (int i = 0; i < this.dataColumns.size(); i++) {
                columnIndexes.put(this.dataColumns.get(i).getName(), i);
            }

            List<Integer> sortFields = new ArrayList<>();
            List<SortOrder> sortOrders = new ArrayList<>();
            for (SortingColumn column : sortedBy) {
                Integer index = columnIndexes.get(column.getColumnName());
                if (index == null) {
                    throw new PrestoException(HIVE_INVALID_METADATA, format("Sorting column '%s' does exist in table '%s.%s'", column.getColumnName(), schemaName, tableName));
                }
                sortFields.add(index);
                sortOrders.add(column.getOrder().getSortOrder());
            }

            this.sortingFileWriterFactory = Optional.of(new SortingFileWriterFactory(
                    hdfsEnvironment,
                    session,
                    conf,
                    types,
                    sortFields,
                    sortOrders,
                    sortBufferSize,
                    maxOpenSortFiles,
                    pageSorter,
                    orcFileWriterFactory,
                    isSortedWriteToTempPathEnabled(session),
                    getSortedWriteTempPathSubdirectoryCount(session)));
        }
        else {
            this.sortingFileWriterFactory = Optional.empty();
        }

        // make sure the FileSystem is created with the correct Configuration object
        try {
            hdfsEnvironment.getFileSystem(session.getUser(), writePath, conf);
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_FILESYSTEM_ERROR, "Failed getting FileSystem: " + writePath, e);
        }

        this.hiveWriterStats = requireNonNull(hiveWriterStats, "hiveWriterStats is null");

        this.partitionCommitRequired = partitionCommitRequired;
    }

    public HiveWriter createWriter(Page partitionColumns, int position, OptionalInt bucketNumber)
    {
        if (bucketCount.isPresent()) {
            checkArgument(bucketNumber.isPresent(), "Bucket not provided for bucketed table");
            checkArgument(bucketNumber.getAsInt() < bucketCount.getAsInt(), "Bucket number %s must be less than bucket count %s", bucketNumber, bucketCount);
        }
        else {
            checkArgument(!bucketNumber.isPresent(), "Bucket number provided by for table that is not bucketed");
        }

        List<String> partitionValues = createPartitionValues(partitionColumnTypes, partitionColumns, position);

        Optional<String> partitionName;
        if (!partitionColumnNames.isEmpty()) {
            partitionName = Optional.of(FileUtils.makePartName(partitionColumnNames, partitionValues));
        }
        else {
            partitionName = Optional.empty();
        }

        // attempt to get the existing partition (if this is an existing partitioned table)
        Optional<Partition> partition = Optional.empty();
        if (!partitionValues.isEmpty() && table != null) {
            partition = pageSinkMetadataProvider.getPartition(partitionValues);
        }

        UpdateMode updateMode;
        Properties schema;
        WriteInfo writeInfo;
        StorageFormat outputStorageFormat;
        if (!partition.isPresent()) {
            if (table == null) {
                // Write to: a new partition in a new partitioned table,
                //           or a new unpartitioned table.
                updateMode = UpdateMode.NEW;
                schema = new Properties();
                schema.setProperty(META_TABLE_COLUMNS, dataColumns.stream()
                        .map(DataColumn::getName)
                        .collect(joining(",")));
                schema.setProperty(META_TABLE_COLUMN_TYPES, dataColumns.stream()
                        .map(DataColumn::getHiveType)
                        .map(HiveType::getHiveTypeName)
                        .map(HiveTypeName::toString)
                        .collect(joining(":")));

                if (!partitionName.isPresent()) {
                    // new unpartitioned table
                    writeInfo = locationService.getTableWriteInfo(locationHandle);
                }
                else {
                    // a new partition in a new partitioned table
                    writeInfo = locationService.getPartitionWriteInfo(locationHandle, partition, partitionName.get());

                    if (!writeInfo.getWriteMode().isWritePathSameAsTargetPath()) {
                        // When target path is different from write path,
                        // verify that the target directory for the partition does not already exist
                        if (HiveWriteUtils.pathExists(new HdfsContext(session, schemaName, tableName), hdfsEnvironment, writeInfo.getTargetPath())) {
                            throw new PrestoException(HIVE_PATH_ALREADY_EXISTS, format(
                                    "Target directory for new partition '%s' of table '%s.%s' already exists: %s",
                                    partitionName,
                                    schemaName,
                                    tableName,
                                    writeInfo.getTargetPath()));
                        }
                    }
                }
            }
            else {
                // Write to: a new partition in an existing partitioned table,
                //           or an existing unpartitioned table
                if (partitionName.isPresent()) {
                    // a new partition in an existing partitioned table
                    updateMode = UpdateMode.NEW;
                    writeInfo = locationService.getPartitionWriteInfo(locationHandle, partition, partitionName.get());
                }
                else if (table.getTableType().equals(TEMPORARY_TABLE)) {
                    // Note: temporary table is always empty at this step
                    updateMode = UpdateMode.APPEND;
                    writeInfo = locationService.getTableWriteInfo(locationHandle);
                }
                else {
                    if (bucketNumber.isPresent()) {
                        throw new PrestoException(HIVE_PARTITION_READ_ONLY, "Cannot insert into bucketed unpartitioned Hive table");
                    }
                    if (immutablePartitions) {
                        throw new PrestoException(HIVE_PARTITION_READ_ONLY, "Unpartitioned Hive tables are immutable");
                    }
                    updateMode = UpdateMode.APPEND;
                    writeInfo = locationService.getTableWriteInfo(locationHandle);
                }

                schema = getHiveSchema(table);
            }

            if (partitionName.isPresent()) {
                // Write to a new partition
                outputStorageFormat = fromHiveStorageFormat(partitionStorageFormat);
            }
            else {
                // Write to a new/existing unpartitioned table
                outputStorageFormat = fromHiveStorageFormat(tableStorageFormat);
            }
        }
        else {
            // Write to: an existing partition in an existing partitioned table
            if (insertExistingPartitionsBehavior == InsertExistingPartitionsBehavior.APPEND) {
                // Append to an existing partition
                checkState(!immutablePartitions);
                if (bucketNumber.isPresent()) {
                    throw new PrestoException(HIVE_PARTITION_READ_ONLY, "Cannot insert into existing partition of bucketed Hive table: " + partitionName.get());
                }
                updateMode = UpdateMode.APPEND;
                // Check the column types in partition schema match the column types in table schema
                List<Column> tableColumns = table.getDataColumns();
                List<Column> existingPartitionColumns = partition.get().getColumns();
                for (int i = 0; i < min(existingPartitionColumns.size(), tableColumns.size()); i++) {
                    HiveType tableType = tableColumns.get(i).getType();
                    HiveType partitionType = existingPartitionColumns.get(i).getType();
                    if (!tableType.equals(partitionType)) {
                        throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, format("" +
                                        "You are trying to write into an existing partition in a table. " +
                                        "The table schema has changed since the creation of the partition. " +
                                        "Inserting rows into such partition is not supported. " +
                                        "The column '%s' in table '%s' is declared as type '%s', " +
                                        "but partition '%s' declared column '%s' as type '%s'.",
                                tableColumns.get(i).getName(),
                                tableName,
                                tableType,
                                partitionName,
                                existingPartitionColumns.get(i).getName(),
                                partitionType));
                    }
                }

                HiveWriteUtils.checkPartitionIsWritable(partitionName.get(), partition.get());

                outputStorageFormat = partition.get().getStorage().getStorageFormat();
                schema = getHiveSchema(partition.get(), table);

                writeInfo = locationService.getPartitionWriteInfo(locationHandle, partition, partitionName.get());
            }
            else if (insertExistingPartitionsBehavior == InsertExistingPartitionsBehavior.OVERWRITE) {
                // Overwrite an existing partition
                //
                // The behavior of overwrite considered as if first dropping the partition and inserting a new partition, thus:
                // * No partition writable check is required.
                // * Table schema and storage format is used for the new partition (instead of existing partition schema and storage format).
                updateMode = UpdateMode.OVERWRITE;

                outputStorageFormat = fromHiveStorageFormat(partitionStorageFormat);
                schema = getHiveSchema(table);

                writeInfo = locationService.getPartitionWriteInfo(locationHandle, Optional.empty(), partitionName.get());
                checkState(writeInfo.getWriteMode() != DIRECT_TO_TARGET_EXISTING_DIRECTORY, "Overwriting existing partition doesn't support DIRECT_TO_TARGET_EXISTING_DIRECTORY write mode");
            }
            else if (insertExistingPartitionsBehavior == InsertExistingPartitionsBehavior.ERROR) {
                throw new PrestoException(HIVE_PARTITION_READ_ONLY, "Cannot insert into an existing partition of Hive table: " + partitionName.get());
            }
            else {
                throw new IllegalArgumentException(format("Unsupported insert existing partitions behavior: %s", insertExistingPartitionsBehavior));
            }
        }

        validateSchema(partitionName, schema);

        String extension = getFileExtension(outputStorageFormat, compressionCodec);
        String targetFileName;
        if (bucketNumber.isPresent()) {
            targetFileName = computeBucketedFileName(filePrefix, bucketNumber.getAsInt()) + extension;
        }
        else {
            targetFileName = filePrefix + "_" + randomUUID() + extension;
        }

        String writeFileName;
        if (partitionCommitRequired) {
            writeFileName = ".tmp.presto." + filePrefix + "_" + randomUUID() + extension;
        }
        else {
            writeFileName = targetFileName;
        }

        Path path = new Path(writeInfo.getWritePath(), writeFileName);

        HiveFileWriter hiveFileWriter = null;
        for (HiveFileWriterFactory fileWriterFactory : fileWriterFactories) {
            Optional<HiveFileWriter> fileWriter = fileWriterFactory.createFileWriter(
                    path,
                    dataColumns.stream()
                            .map(DataColumn::getName)
                            .collect(toList()),
                    outputStorageFormat,
                    schema,
                    conf,
                    session);
            if (fileWriter.isPresent()) {
                hiveFileWriter = fileWriter.get();
                break;
            }
        }

        if (hiveFileWriter == null) {
            hiveFileWriter = new RecordFileWriter(
                    path,
                    dataColumns.stream()
                            .map(DataColumn::getName)
                            .collect(toList()),
                    outputStorageFormat,
                    schema,
                    partitionStorageFormat.getEstimatedWriterSystemMemoryUsage(),
                    conf,
                    typeManager,
                    session);
        }

        String writerImplementation = hiveFileWriter.getClass().getName();

        Consumer<HiveWriter> onCommit = hiveWriter -> {
            Optional<Long> size;
            try {
                size = Optional.of(hdfsEnvironment.getFileSystem(session.getUser(), path, conf).getFileStatus(path).getLen());
            }
            catch (IOException | RuntimeException e) {
                // Do not fail the query if file system is not available
                size = Optional.empty();
            }

            eventClient.post(new WriteCompletedEvent(
                    session.getQueryId(),
                    path.toString(),
                    schemaName,
                    tableName,
                    partitionName.orElse(null),
                    outputStorageFormat.getOutputFormat(),
                    writerImplementation,
                    nodeManager.getCurrentNode().getVersion(),
                    nodeManager.getCurrentNode().getHost(),
                    session.getIdentity().getPrincipal().map(Principal::getName).orElse(null),
                    nodeManager.getEnvironment(),
                    sessionProperties,
                    size.orElse(null),
                    hiveWriter.getRowCount()));
        };

        if (sortingFileWriterFactory.isPresent()) {
            checkState(bucketNumber.isPresent(), "missing bucket number for sorted table write");
            hiveFileWriter = sortingFileWriterFactory.get().createSortingFileWriter(path, hiveFileWriter, bucketNumber.getAsInt(), writeInfo.getTempPath());
        }

        return new HiveWriter(
                hiveFileWriter,
                partitionName,
                updateMode,
                new FileWriteInfo(writeFileName, targetFileName),
                writeInfo.getWritePath().toString(),
                writeInfo.getTargetPath().toString(),
                onCommit,
                hiveWriterStats);
    }

    private void validateSchema(Optional<String> partitionName, Properties schema)
    {
        // existing tables may have columns in a different order
        List<String> fileColumnNames = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(schema.getProperty(META_TABLE_COLUMNS, ""));
        List<HiveType> fileColumnHiveTypes = toHiveTypes(schema.getProperty(META_TABLE_COLUMN_TYPES, ""));

        // verify we can write all input columns to the file
        Map<String, DataColumn> inputColumnMap = dataColumns.stream()
                .collect(toMap(DataColumn::getName, identity()));
        Set<String> missingColumns = Sets.difference(inputColumnMap.keySet(), new HashSet<>(fileColumnNames));
        if (!missingColumns.isEmpty()) {
            throw new PrestoException(NOT_FOUND, format("Table %s.%s does not have columns %s", schema, tableName, missingColumns));
        }
        if (fileColumnNames.size() != fileColumnHiveTypes.size()) {
            throw new PrestoException(HIVE_INVALID_METADATA, format(
                    "Partition '%s' in table '%s.%s' has mismatched metadata for column names and types",
                    partitionName,
                    schemaName,
                    tableName));
        }

        // verify the file types match the input type
        // todo adapt input types to the file types as Hive does
        for (int fileIndex = 0; fileIndex < fileColumnNames.size(); fileIndex++) {
            String columnName = fileColumnNames.get(fileIndex);
            HiveType fileColumnHiveType = fileColumnHiveTypes.get(fileIndex);
            HiveType inputHiveType = inputColumnMap.get(columnName).getHiveType();

            if (!fileColumnHiveType.equals(inputHiveType)) {
                // todo this should be moved to a helper
                throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, format(
                        "" +
                                "There is a mismatch between the table and partition schemas. " +
                                "The column '%s' in table '%s.%s' is declared as type '%s', " +
                                "but partition '%s' declared column '%s' as type '%s'.",
                        columnName,
                        schemaName,
                        tableName,
                        inputHiveType,
                        partitionName,
                        columnName,
                        fileColumnHiveType));
            }
        }
    }

    public static String computeBucketedFileName(String filePrefix, int bucket)
    {
        return filePrefix + "_bucket-" + Strings.padStart(Integer.toString(bucket), BUCKET_NUMBER_PADDING, '0');
    }

    public static String getFileExtension(StorageFormat storageFormat, HiveCompressionCodec compressionCodec)
    {
        // text format files must have the correct extension when compressed
        if (compressionCodec == NONE || !HiveIgnoreKeyTextOutputFormat.class.getName().equals(storageFormat.getOutputFormat())) {
            return "";
        }

        if (!compressionCodec.getCodec().isPresent()) {
            return new DefaultCodec().getDefaultExtension();
        }

        try {
            return compressionCodec.getCodec().get().getConstructor().newInstance().getDefaultExtension();
        }
        catch (ReflectiveOperationException e) {
            throw new PrestoException(HIVE_UNSUPPORTED_FORMAT, "Failed to load compression codec: " + compressionCodec.getCodec().get(), e);
        }
    }

    private static class DataColumn
    {
        private final String name;
        private final HiveType hiveType;

        public DataColumn(String name, HiveType hiveType)
        {
            this.name = requireNonNull(name, "name is null");
            this.hiveType = requireNonNull(hiveType, "hiveType is null");
        }

        public String getName()
        {
            return name;
        }

        public HiveType getHiveType()
        {
            return hiveType;
        }
    }
}
