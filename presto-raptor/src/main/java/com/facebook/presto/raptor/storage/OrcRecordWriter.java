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

import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.raptor.util.SyncingFileSystem;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.classloader.ThreadContextClassLoader;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.TimestampWithTimeZoneType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.spi.type.VarbinaryType;
import com.facebook.presto.spi.type.VarcharType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.orc.NullMemoryManager;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.OrcWriterOptions;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.SettableStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_ERROR;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_UNSUPPORTED_COMPRESSION_KIND;
import static com.facebook.presto.raptor.storage.Row.extractRow;
import static com.facebook.presto.raptor.storage.StorageType.arrayOf;
import static com.facebook.presto.raptor.storage.StorageType.mapOf;
import static com.facebook.presto.raptor.util.Types.isArrayType;
import static com.facebook.presto.raptor.util.Types.isMapType;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Functions.toStringFunction;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.transform;
import static io.airlift.json.JsonCodec.jsonCodec;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMNS;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMN_TYPES;
import static org.apache.hadoop.hive.ql.exec.FileSinkOperator.RecordWriter;
import static org.apache.hadoop.hive.ql.io.orc.CompressionKind.NONE;
import static org.apache.hadoop.hive.ql.io.orc.CompressionKind.SNAPPY;
import static org.apache.hadoop.hive.ql.io.orc.CompressionKind.ZLIB;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.LIST;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.MAP;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.PRIMITIVE;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardListObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardMapObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory.getPrimitiveTypeInfo;

public class OrcRecordWriter
        implements FileWriter
{
    private static final Configuration CONFIGURATION = new Configuration();
    private static final Constructor<? extends RecordWriter> WRITER_CONSTRUCTOR = getOrcWriterConstructor();
    private static final JsonCodec<OrcFileMetadata> METADATA_CODEC = jsonCodec(OrcFileMetadata.class);

    private final List<Type> columnTypes;

    private final OrcSerde serializer;
    private final RecordWriter recordWriter;
    private final SettableStructObjectInspector tableInspector;
    private final List<StructField> structFields;
    private final Object orcRow;

    private boolean closed;
    private long rowCount;
    private long uncompressedSize;

    public OrcRecordWriter(List<Long> columnIds, List<Type> columnTypes, File target, CompressionKind compression)
    {
        this(columnIds, columnTypes, target, compression, true);
    }

    @VisibleForTesting
    OrcRecordWriter(List<Long> columnIds, List<Type> columnTypes, File target, CompressionKind compression, boolean writeMetadata)
    {
        this.columnTypes = ImmutableList.copyOf(requireNonNull(columnTypes, "columnTypes is null"));
        checkArgument(columnIds.size() == columnTypes.size(), "ids and types mismatch");
        checkArgument(isUnique(columnIds), "ids must be unique");

        List<StorageType> storageTypes = ImmutableList.copyOf(toStorageTypes(columnTypes));
        Iterable<String> hiveTypeNames = storageTypes.stream().map(StorageType::getHiveTypeName).collect(toList());
        List<String> columnNames = ImmutableList.copyOf(transform(columnIds, toStringFunction()));

        Properties properties = new Properties();
        properties.setProperty(META_TABLE_COLUMNS, Joiner.on(',').join(columnNames));
        properties.setProperty(META_TABLE_COLUMN_TYPES, Joiner.on(':').join(hiveTypeNames));

        serializer = createSerializer(properties);
        recordWriter = createRecordWriter(new Path(target.toURI()), columnIds, columnTypes, requireNonNull(compression, "compression is null"), writeMetadata);

        tableInspector = getStandardStructObjectInspector(columnNames, getJavaObjectInspectors(storageTypes));
        structFields = ImmutableList.copyOf(tableInspector.getAllStructFieldRefs());
        orcRow = tableInspector.create();
    }

    @Override
    public void appendPages(List<Page> pages)
    {
        for (Page page : pages) {
            for (int position = 0; position < page.getPositionCount(); position++) {
                appendRow(extractRow(page, position, columnTypes));
            }
        }
    }

    @Override
    public void appendPages(List<Page> inputPages, int[] pageIndexes, int[] positionIndexes)
    {
        checkArgument(pageIndexes.length == positionIndexes.length, "pageIndexes and positionIndexes do not match");
        for (int i = 0; i < pageIndexes.length; i++) {
            Page page = inputPages.get(pageIndexes[i]);
            appendRow(extractRow(page, positionIndexes[i], columnTypes));
        }
    }

    @Override
    public void close()
            throws IOException
    {
        if (closed) {
            return;
        }
        closed = true;

        recordWriter.close(false);
    }

    @Override
    public long getRowCount()
    {
        return rowCount;
    }

    @Override
    public long getUncompressedSize()
    {
        return uncompressedSize;
    }

    private void appendRow(Row row)
    {
        List<Object> columns = row.getColumns();
        checkArgument(columns.size() == columnTypes.size());
        for (int channel = 0; channel < columns.size(); channel++) {
            tableInspector.setStructFieldData(orcRow, structFields.get(channel), columns.get(channel));
        }
        try {
            recordWriter.write(serializer.serialize(orcRow, tableInspector));
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to write record", e);
        }
        rowCount++;
        uncompressedSize += row.getSizeInBytes();
    }

    private static OrcSerde createSerializer(Properties properties)
    {
        OrcSerde serde = new OrcSerde();
        serde.initialize(CONFIGURATION, properties);
        return serde;
    }

    private static RecordWriter createRecordWriter(Path target, List<Long> columnIds, List<Type> columnTypes, CompressionKind compression, boolean writeMetadata)
    {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(FileSystem.class.getClassLoader());
                FileSystem fileSystem = new SyncingFileSystem(CONFIGURATION)) {
            OrcFile.WriterOptions options = new OrcWriterOptions(CONFIGURATION)
                    .memory(new NullMemoryManager(CONFIGURATION))
                    .fileSystem(fileSystem)
                    .compress(toCompressionKind(compression));

            if (writeMetadata) {
                options.callback(createFileMetadataCallback(columnIds, columnTypes));
            }

            return WRITER_CONSTRUCTOR.newInstance(target, options);
        }
        catch (ReflectiveOperationException | IOException e) {
            throw new PrestoException(RAPTOR_ERROR, "Failed to create writer", e);
        }
    }

    private static org.apache.hadoop.hive.ql.io.orc.CompressionKind toCompressionKind(CompressionKind compression)
    {
        switch (compression) {
            case NONE:
                return NONE;
            case SNAPPY:
                return SNAPPY;
            case ZLIB:
                return ZLIB;
            default:
                throw new PrestoException(RAPTOR_UNSUPPORTED_COMPRESSION_KIND, "Found unsupported compression kind: " + compression);
        }
    }

    private static OrcFile.WriterCallback createFileMetadataCallback(List<Long> columnIds, List<Type> columnTypes)
    {
        return new OrcFile.WriterCallback()
        {
            @Override
            public void preStripeWrite(OrcFile.WriterContext context)
            {}

            @Override
            public void preFooterWrite(OrcFile.WriterContext context)
            {
                ImmutableMap.Builder<Long, TypeSignature> columnTypesMap = ImmutableMap.builder();
                for (int i = 0; i < columnIds.size(); i++) {
                    columnTypesMap.put(columnIds.get(i), columnTypes.get(i).getTypeSignature());
                }
                byte[] bytes = METADATA_CODEC.toJsonBytes(new OrcFileMetadata(columnTypesMap.build()));
                context.getWriter().addUserMetadata(OrcFileMetadata.KEY, ByteBuffer.wrap(bytes));
            }
        };
    }

    private static Constructor<? extends RecordWriter> getOrcWriterConstructor()
    {
        try {
            String writerClassName = OrcOutputFormat.class.getName() + "$OrcRecordWriter";
            Constructor<? extends RecordWriter> constructor = OrcOutputFormat.class.getClassLoader()
                    .loadClass(writerClassName).asSubclass(RecordWriter.class)
                    .getDeclaredConstructor(Path.class, OrcFile.WriterOptions.class);
            constructor.setAccessible(true);
            return constructor;
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<ObjectInspector> getJavaObjectInspectors(List<StorageType> types)
    {
        return types.stream()
                .map(StorageType::getHiveTypeName)
                .map(TypeInfoUtils::getTypeInfoFromTypeString)
                .map(OrcRecordWriter::getJavaObjectInspector)
                .collect(toList());
    }

    private static ObjectInspector getJavaObjectInspector(TypeInfo typeInfo)
    {
        Category category = typeInfo.getCategory();
        if (category == PRIMITIVE) {
            return getPrimitiveJavaObjectInspector(getPrimitiveTypeInfo(typeInfo.getTypeName()));
        }
        if (category == LIST) {
            ListTypeInfo listTypeInfo = (ListTypeInfo) typeInfo;
            return getStandardListObjectInspector(getJavaObjectInspector(listTypeInfo.getListElementTypeInfo()));
        }
        if (category == MAP) {
            MapTypeInfo mapTypeInfo = (MapTypeInfo) typeInfo;
            return getStandardMapObjectInspector(
                    getJavaObjectInspector(mapTypeInfo.getMapKeyTypeInfo()),
                    getJavaObjectInspector(mapTypeInfo.getMapValueTypeInfo()));
        }
        throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled storage type: " + category);
    }

    private static <T> boolean isUnique(Collection<T> items)
    {
        return new HashSet<>(items).size() == items.size();
    }

    private static List<StorageType> toStorageTypes(List<Type> columnTypes)
    {
        return columnTypes.stream().map(OrcRecordWriter::toStorageType).collect(toList());
    }

    private static StorageType toStorageType(Type type)
    {
        if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            return StorageType.decimal(decimalType.getPrecision(), decimalType.getScale());
        }
        if (type instanceof TimestampWithTimeZoneType) {
            throw new PrestoException(NOT_SUPPORTED, "The current ORC writer does not support type: " + type);
        }
        Class<?> javaType = type.getJavaType();
        if (javaType == boolean.class) {
            return StorageType.BOOLEAN;
        }
        if (javaType == long.class) {
            return StorageType.LONG;
        }
        if (javaType == double.class) {
            return StorageType.DOUBLE;
        }
        if (javaType == Slice.class) {
            if (type instanceof VarcharType) {
                return StorageType.STRING;
            }
            if (type.equals(VarbinaryType.VARBINARY)) {
                return StorageType.BYTES;
            }
        }
        if (isArrayType(type)) {
            return arrayOf(toStorageType(type.getTypeParameters().get(0)));
        }
        if (isMapType(type)) {
            return mapOf(toStorageType(type.getTypeParameters().get(0)), toStorageType(type.getTypeParameters().get(1)));
        }
        throw new PrestoException(NOT_SUPPORTED, "No storage type for type: " + type);
    }
}
