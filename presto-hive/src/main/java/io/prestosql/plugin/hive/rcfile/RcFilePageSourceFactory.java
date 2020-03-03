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
package io.prestosql.plugin.hive.rcfile;

import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.airlift.units.DataSize.Unit;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HivePageSourceFactory;
import io.prestosql.rcfile.AircompressorCodecFactory;
import io.prestosql.rcfile.HadoopCodecFactory;
import io.prestosql.rcfile.RcFileCorruptionException;
import io.prestosql.rcfile.RcFileEncoding;
import io.prestosql.rcfile.RcFileReader;
import io.prestosql.rcfile.binary.BinaryRcFileEncoding;
import io.prestosql.rcfile.text.TextRcFileEncoding;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockMissingException;
import org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe;
import org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static com.google.common.base.Strings.nullToEmpty;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_MISSING_DATA;
import static io.prestosql.plugin.hive.util.HiveUtil.getDeserializerClassName;
import static io.prestosql.rcfile.text.TextRcFileEncoding.DEFAULT_NULL_SEQUENCE;
import static io.prestosql.rcfile.text.TextRcFileEncoding.DEFAULT_SEPARATORS;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.serde.serdeConstants.COLLECTION_DELIM;
import static org.apache.hadoop.hive.serde.serdeConstants.ESCAPE_CHAR;
import static org.apache.hadoop.hive.serde.serdeConstants.FIELD_DELIM;
import static org.apache.hadoop.hive.serde.serdeConstants.MAPKEY_DELIM;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_FORMAT;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LAST_COLUMN_TAKES_REST;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_NULL_FORMAT;
import static org.apache.hadoop.hive.serde2.lazy.LazySerDeParameters.SERIALIZATION_EXTEND_NESTING_LEVELS;
import static org.apache.hadoop.hive.serde2.lazy.LazyUtils.getByte;

public class RcFilePageSourceFactory
        implements HivePageSourceFactory
{
    private static final int TEXT_LEGACY_NESTING_LEVELS = 8;
    private static final int TEXT_EXTENDED_NESTING_LEVELS = 29;

    private final TypeManager typeManager;
    private final HdfsEnvironment hdfsEnvironment;
    private final FileFormatDataSourceStats stats;

    @Inject
    public RcFilePageSourceFactory(TypeManager typeManager, HdfsEnvironment hdfsEnvironment, FileFormatDataSourceStats stats)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.stats = requireNonNull(stats, "stats is null");
    }

    @Override
    public Optional<? extends ConnectorPageSource> createPageSource(
            Configuration configuration,
            ConnectorSession session,
            Path path,
            long start,
            long length,
            long fileSize,
            Properties schema,
            List<HiveColumnHandle> columns,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone hiveStorageTimeZone)
    {
        RcFileEncoding rcFileEncoding;
        String deserializerClassName = getDeserializerClassName(schema);
        if (deserializerClassName.equals(LazyBinaryColumnarSerDe.class.getName())) {
            rcFileEncoding = new BinaryRcFileEncoding();
        }
        else if (deserializerClassName.equals(ColumnarSerDe.class.getName())) {
            rcFileEncoding = createTextVectorEncoding(schema, hiveStorageTimeZone);
        }
        else {
            return Optional.empty();
        }

        if (fileSize == 0) {
            throw new PrestoException(HIVE_BAD_DATA, "RCFile is empty: " + path);
        }

        FSDataInputStream inputStream;
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(session.getUser(), path, configuration);
            inputStream = hdfsEnvironment.doAs(session.getUser(), () -> fileSystem.open(path));
        }
        catch (Exception e) {
            if (nullToEmpty(e.getMessage()).trim().equals("Filesystem closed") ||
                    e instanceof FileNotFoundException) {
                throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }

        try {
            ImmutableMap.Builder<Integer, Type> readColumns = ImmutableMap.builder();
            for (HiveColumnHandle column : columns) {
                readColumns.put(column.getHiveColumnIndex(), column.getHiveType().getType(typeManager));
            }

            RcFileReader rcFileReader = new RcFileReader(
                    new HdfsRcFileDataSource(path.toString(), inputStream, fileSize, stats),
                    rcFileEncoding,
                    readColumns.build(),
                    new AircompressorCodecFactory(new HadoopCodecFactory(configuration.getClassLoader())),
                    start,
                    length,
                    new DataSize(8, Unit.MEGABYTE));

            return Optional.of(new RcFilePageSource(rcFileReader, columns, typeManager));
        }
        catch (Throwable e) {
            try {
                inputStream.close();
            }
            catch (IOException ignored) {
            }
            if (e instanceof PrestoException) {
                throw (PrestoException) e;
            }
            String message = splitError(e, path, start, length);
            if (e instanceof RcFileCorruptionException) {
                throw new PrestoException(HIVE_BAD_DATA, message, e);
            }
            if (e instanceof BlockMissingException) {
                throw new PrestoException(HIVE_MISSING_DATA, message, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static String splitError(Throwable t, Path path, long start, long length)
    {
        return format("Error opening Hive split %s (offset=%s, length=%s): %s", path, start, length, t.getMessage());
    }

    public static TextRcFileEncoding createTextVectorEncoding(Properties schema, DateTimeZone hiveStorageTimeZone)
    {
        // separators
        int nestingLevels;
        if (!"true".equalsIgnoreCase(schema.getProperty(SERIALIZATION_EXTEND_NESTING_LEVELS))) {
            nestingLevels = TEXT_LEGACY_NESTING_LEVELS;
        }
        else {
            nestingLevels = TEXT_EXTENDED_NESTING_LEVELS;
        }
        byte[] separators = Arrays.copyOf(DEFAULT_SEPARATORS, nestingLevels);

        // the first three separators are set by old-old properties
        separators[0] = getByte(schema.getProperty(FIELD_DELIM, schema.getProperty(SERIALIZATION_FORMAT)), DEFAULT_SEPARATORS[0]);
        // for map field collection delimiter, Hive 1.x uses "colelction.delim" but Hive 3.x uses "collection.delim"
        // https://issues.apache.org/jira/browse/HIVE-16922
        separators[1] = getByte(schema.getProperty(COLLECTION_DELIM, schema.getProperty("colelction.delim")), DEFAULT_SEPARATORS[1]);
        separators[2] = getByte(schema.getProperty(MAPKEY_DELIM), DEFAULT_SEPARATORS[2]);

        // null sequence
        Slice nullSequence;
        String nullSequenceString = schema.getProperty(SERIALIZATION_NULL_FORMAT);
        if (nullSequenceString == null) {
            nullSequence = DEFAULT_NULL_SEQUENCE;
        }
        else {
            nullSequence = Slices.utf8Slice(nullSequenceString);
        }

        // last column takes rest
        String lastColumnTakesRestString = schema.getProperty(SERIALIZATION_LAST_COLUMN_TAKES_REST);
        boolean lastColumnTakesRest = "true".equalsIgnoreCase(lastColumnTakesRestString);

        // escaped
        String escapeProperty = schema.getProperty(ESCAPE_CHAR);
        Byte escapeByte = null;
        if (escapeProperty != null) {
            escapeByte = getByte(escapeProperty, (byte) '\\');
        }

        return new TextRcFileEncoding(
                hiveStorageTimeZone,
                nullSequence,
                separators,
                escapeByte,
                lastColumnTakesRest);
    }
}
