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
package com.facebook.presto.hive.metastore;

import com.facebook.presto.hive.HdfsEnvironment;
import com.facebook.presto.hive.HdfsEnvironment.HdfsContext;
import com.facebook.presto.hive.PartitionOfflineException;
import com.facebook.presto.hive.TableOfflineException;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TableNotFoundException;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.metastore.ProtectMode;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import static com.facebook.presto.hive.HiveErrorCode.HIVE_FILESYSTEM_ERROR;
import static com.facebook.presto.hive.HiveSplitManager.PRESTO_OFFLINE;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.metastore.MetaStoreUtils.typeToThriftType;
import static org.apache.hadoop.hive.metastore.ProtectMode.getProtectModeFromString;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.BUCKET_COUNT;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.BUCKET_FIELD_NAME;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.FILE_OUTPUT_FORMAT;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMNS;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMN_TYPES;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_LOCATION;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_NAME;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_PARTITION_COLUMNS;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_PARTITION_COLUMN_TYPES;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_DDL;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LIB;

public class MetastoreUtil
{
    private MetastoreUtil()
    {
    }

    public static Properties getHiveSchema(Table table)
    {
        // Mimics function in Hive: MetaStoreUtils.getTableMetadata(Table)
        return getHiveSchema(
                table.getStorage(),
                table.getDataColumns(),
                table.getDataColumns(),
                table.getParameters(),
                table.getDatabaseName(),
                table.getTableName(),
                table.getPartitionColumns());
    }

    public static Properties getHiveSchema(Partition partition, Table table)
    {
        // Mimics function in Hive: MetaStoreUtils.getSchema(Partition, Table)
        return getHiveSchema(
                partition.getStorage(),
                partition.getColumns(),
                table.getDataColumns(),
                table.getParameters(),
                table.getDatabaseName(),
                table.getTableName(),
                table.getPartitionColumns());
    }

    public static Properties getHiveSchema(
            Storage storage,
            List<Column> partitionDataColumns,
            List<Column> tableDataColumns,
            Map<String, String> tableParameters,
            String databaseName,
            String tableName,
            List<Column> partitionKeys)
    {
        // Mimics function in Hive:
        // MetaStoreUtils.getSchema(StorageDescriptor, StorageDescriptor, Map<String, String>, String, String, List<FieldSchema>)

        Properties schema = new Properties();

        schema.setProperty(FILE_INPUT_FORMAT, storage.getStorageFormat().getInputFormat());
        schema.setProperty(FILE_OUTPUT_FORMAT, storage.getStorageFormat().getOutputFormat());

        schema.setProperty(META_TABLE_NAME, databaseName + "." + tableName);
        schema.setProperty(META_TABLE_LOCATION, storage.getLocation());

        if (storage.getBucketProperty().isPresent()) {
            List<String> bucketedBy = storage.getBucketProperty().get().getBucketedBy();
            if (!bucketedBy.isEmpty()) {
                schema.setProperty(BUCKET_FIELD_NAME, bucketedBy.get(0));
            }
            schema.setProperty(BUCKET_COUNT, Integer.toString(storage.getBucketProperty().get().getBucketCount()));
        }
        else {
            schema.setProperty(BUCKET_COUNT, "0");
        }

        for (Map.Entry<String, String> param : storage.getSerdeParameters().entrySet()) {
            schema.setProperty(param.getKey(), (param.getValue() != null) ? param.getValue() : "");
        }
        schema.setProperty(SERIALIZATION_LIB, storage.getStorageFormat().getSerDe());

        StringBuilder columnNameBuilder = new StringBuilder();
        StringBuilder columnTypeBuilder = new StringBuilder();
        StringBuilder columnCommentBuilder = new StringBuilder();
        boolean first = true;
        for (Column column : tableDataColumns) {
            if (!first) {
                columnNameBuilder.append(",");
                columnTypeBuilder.append(":");
                columnCommentBuilder.append('\0');
            }
            columnNameBuilder.append(column.getName());
            columnTypeBuilder.append(column.getType());
            columnCommentBuilder.append(column.getComment().orElse(""));
            first = false;
        }
        String columnNames = columnNameBuilder.toString();
        String columnTypes = columnTypeBuilder.toString();
        schema.setProperty(META_TABLE_COLUMNS, columnNames);
        schema.setProperty(META_TABLE_COLUMN_TYPES, columnTypes);
        schema.setProperty("columns.comments", columnCommentBuilder.toString());

        schema.setProperty(SERIALIZATION_DDL, toThriftDdl(tableName, partitionDataColumns));

        String partString = "";
        String partStringSep = "";
        String partTypesString = "";
        String partTypesStringSep = "";
        for (Column partKey : partitionKeys) {
            partString += partStringSep;
            partString += partKey.getName();
            partTypesString += partTypesStringSep;
            partTypesString += partKey.getType().getHiveTypeName().toString();
            if (partStringSep.length() == 0) {
                partStringSep = "/";
                partTypesStringSep = ":";
            }
        }
        if (partString.length() > 0) {
            schema.setProperty(META_TABLE_PARTITION_COLUMNS, partString);
            schema.setProperty(META_TABLE_PARTITION_COLUMN_TYPES, partTypesString);
        }

        if (tableParameters != null) {
            for (Map.Entry<String, String> entry : tableParameters.entrySet()) {
                // add non-null parameters to the schema
                if (entry.getValue() != null) {
                    schema.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        return schema;
    }

    public static Properties getHiveSchema(Map<String, String> serdeParameters, Map<String, String> tableParameters)
    {
        Properties schema = new Properties();
        for (Map.Entry<String, String> param : serdeParameters.entrySet()) {
            schema.setProperty(param.getKey(), (param.getValue() != null) ? param.getValue() : "");
        }
        for (Map.Entry<String, String> entry : tableParameters.entrySet()) {
            // add non-null parameters to the schema
            if (entry.getValue() != null) {
                schema.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return schema;
    }

    /**
     * Recreates partition schema based on the table schema and the column
     * coercions map.
     *
     * partitionColumnCount is needed to handle cases when the partition
     * has less columns than the table.
     *
     * If the partition has more columns than the table does, the partitionSchemaDifference
     * map is expected to contain information for the missing columns.
     */
    public static List<Column> reconstructPartitionSchema(List<Column> tableSchema, int partitionColumnCount, Map<Integer, Column> partitionSchemaDifference)
    {
        ImmutableList.Builder<Column> columns = ImmutableList.builder();
        for (int i = 0; i < partitionColumnCount; i++) {
            Column column = partitionSchemaDifference.get(i);
            if (column == null) {
                checkArgument(
                        i < tableSchema.size(),
                        "column descriptor for column with hiveColumnIndex %s not found: tableSchema: %s, partitionSchemaDifference: %s",
                        i,
                        tableSchema,
                        partitionSchemaDifference);
                column = tableSchema.get(i);
            }
            columns.add(column);
        }
        return columns.build();
    }

    public static ProtectMode getProtectMode(Partition partition)
    {
        return getProtectMode(partition.getParameters());
    }

    public static ProtectMode getProtectMode(Table table)
    {
        return getProtectMode(table.getParameters());
    }

    public static String makePartName(List<Column> partitionColumns, List<String> values)
    {
        checkArgument(partitionColumns.size() == values.size(), "Partition value count does not match the partition column count");
        checkArgument(values.stream().allMatch(Objects::nonNull), "partitionValue must not have null elements");

        List<String> partitionColumnNames = partitionColumns.stream().map(Column::getName).collect(toList());
        return FileUtils.makePartName(partitionColumnNames, values);
    }

    public static String getPartitionLocation(Table table, Optional<Partition> partition)
    {
        if (!partition.isPresent()) {
            return table.getStorage().getLocation();
        }
        return partition.get().getStorage().getLocation();
    }

    private static String toThriftDdl(String structName, List<Column> columns)
    {
        // Mimics function in Hive:
        // MetaStoreUtils.getDDLFromFieldSchema(String, List<FieldSchema>)
        StringBuilder ddl = new StringBuilder();
        ddl.append("struct ");
        ddl.append(structName);
        ddl.append(" { ");
        boolean first = true;
        for (Column column : columns) {
            if (first) {
                first = false;
            }
            else {
                ddl.append(", ");
            }
            ddl.append(typeToThriftType(column.getType().getHiveTypeName().toString()));
            ddl.append(' ');
            ddl.append(column.getName());
        }
        ddl.append("}");
        return ddl.toString();
    }

    private static ProtectMode getProtectMode(Map<String, String> parameters)
    {
        if (!parameters.containsKey(ProtectMode.PARAMETER_NAME)) {
            return new ProtectMode();
        }
        else {
            return getProtectModeFromString(parameters.get(ProtectMode.PARAMETER_NAME));
        }
    }

    public static void verifyOnline(SchemaTableName tableName, Optional<String> partitionName, ProtectMode protectMode, Map<String, String> parameters)
    {
        if (protectMode.offline) {
            if (partitionName.isPresent()) {
                throw new PartitionOfflineException(tableName, partitionName.get(), false, null);
            }
            throw new TableOfflineException(tableName, false, null);
        }

        String prestoOffline = parameters.get(PRESTO_OFFLINE);
        if (!isNullOrEmpty(prestoOffline)) {
            if (partitionName.isPresent()) {
                throw new PartitionOfflineException(tableName, partitionName.get(), true, prestoOffline);
            }
            throw new TableOfflineException(tableName, true, prestoOffline);
        }
    }

    public static void verifyCanDropColumn(ExtendedHiveMetastore metastore, String databaseName, String tableName, String columnName)
    {
        Table table = metastore.getTable(databaseName, tableName)
                .orElseThrow(() -> new TableNotFoundException(new SchemaTableName(databaseName, tableName)));

        if (table.getPartitionColumns().stream().anyMatch(column -> column.getName().equals(columnName))) {
            throw new PrestoException(NOT_SUPPORTED, "Cannot drop partition columns");
        }
        if (table.getDataColumns().size() <= 1) {
            throw new PrestoException(NOT_SUPPORTED, "Cannot drop the only non-partition column in a table");
        }
    }

    public static FileSystem getFileSystem(HdfsEnvironment hdfsEnvironment, HdfsContext context, Path path)
    {
        try {
            return hdfsEnvironment.getFileSystem(context, path);
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_FILESYSTEM_ERROR, format("Error getting file system. Path: %s", path), e);
        }
    }

    public static void renameFile(FileSystem fileSystem, Path source, Path target)
    {
        try {
            if (fileSystem.exists(target) || !fileSystem.rename(source, target)) {
                throw new PrestoException(HIVE_FILESYSTEM_ERROR, getRenameErrorMessage(source, target));
            }
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_FILESYSTEM_ERROR, getRenameErrorMessage(source, target), e);
        }
    }

    private static String getRenameErrorMessage(Path source, Path target)
    {
        return format("Error moving data files from %s to final location %s", source, target);
    }
}
