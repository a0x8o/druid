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
package io.prestosql.plugin.phoenix;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.TypeManager;
import org.apache.hadoop.util.StringUtils;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prestosql.spi.session.PropertyMetadata.booleanProperty;
import static io.prestosql.spi.session.PropertyMetadata.integerProperty;
import static io.prestosql.spi.session.PropertyMetadata.stringProperty;
import static java.util.Objects.requireNonNull;

/**
 * Class contains all table properties for the Phoenix connector. Used when creating a table:
 * <p>
 * <pre>CREATE TABLE foo (a VARCHAR with (primary_key = true), b INT) WITH (SALT_BUCKETS=10, VERSIONS=5, COMPRESSION='lz');</pre>
 */
public final class PhoenixTableProperties
{
    public static final String ROWKEYS = "rowkeys";
    public static final String SALT_BUCKETS = "salt_buckets";
    public static final String SPLIT_ON = "split_on";
    public static final String DISABLE_WAL = "disable_wal";
    public static final String IMMUTABLE_ROWS = "immutable_rows";
    public static final String DEFAULT_COLUMN_FAMILY = "default_column_family";
    public static final String BLOOMFILTER = "bloomfilter";
    public static final String VERSIONS = "versions";
    public static final String MIN_VERSIONS = "min_versions";
    public static final String COMPRESSION = "compression";
    public static final String TTL = "ttl";

    private final List<PropertyMetadata<?>> tableProperties;

    @Inject
    public PhoenixTableProperties(TypeManager typeManager)
    {
        tableProperties = ImmutableList.of(
                stringProperty(
                        ROWKEYS,
                        "Comma-separated list of columns to be the primary key.",
                        null,
                        false),
                integerProperty(
                        SALT_BUCKETS,
                        "Number of salt buckets.  This causes an extra byte to be transparently prepended to every row key to ensure an evenly distributed read and write load across all region servers.",
                        null,
                        false),
                stringProperty(
                        SPLIT_ON,
                        "Comma-separated list of keys to split on during table creation.",
                        null,
                        false),
                booleanProperty(
                        DISABLE_WAL,
                        "If true, causes HBase not to write data to the write-ahead-log, thus making updates faster at the expense of potentially losing data in the event of a region server failure.",
                        null,
                        false),
                booleanProperty(
                        IMMUTABLE_ROWS,
                        "Set to true if the table has rows which are write-once, append-only.",
                        null,
                        false),
                stringProperty(
                        DEFAULT_COLUMN_FAMILY,
                        "The column family name to use by default.",
                        null,
                        false),
                stringProperty(
                        BLOOMFILTER,
                        "NONE, ROW or ROWCOL to enable blooms per column family.",
                        null,
                        false),
                integerProperty(
                        VERSIONS,
                        "The maximum number of row versions to store, configured per column family via HColumnDescriptor.",
                        null,
                        false),
                integerProperty(
                        MIN_VERSIONS,
                        "The minimum number of row versions to store, configured per column family via HColumnDescriptor.",
                        null,
                        false),
                stringProperty(
                        COMPRESSION,
                        "Compression algorithm to use for HBase blocks. Options are: SNAPPY, GZIP, LZ, and others.",
                        null,
                        false),
                integerProperty(
                        TTL,
                        "Number of seconds for cell TTL.  HBase will automatically delete rows once the expiration time is reached.",
                        null,
                        false));
    }

    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties;
    }

    public static Optional<Integer> getSaltBuckets(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Integer value = (Integer) tableProperties.get(SALT_BUCKETS);
        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(value);
    }

    public static Optional<String> getSplitOn(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        String value = (String) tableProperties.get(SPLIT_ON);
        if (value == null) {
            return Optional.empty();
        }

        return Optional.of(value);
    }

    public static Optional<List<String>> getRowkeys(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        String rowkeysCsv = (String) tableProperties.get(ROWKEYS);
        if (rowkeysCsv == null) {
            return Optional.empty();
        }
        return Optional.of(Arrays.asList(StringUtils.split(rowkeysCsv, ',')));
    }

    public static Optional<Boolean> getDisableWal(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Boolean value = (Boolean) tableProperties.get(DISABLE_WAL);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<Boolean> getImmutableRows(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Boolean value = (Boolean) tableProperties.get(IMMUTABLE_ROWS);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<String> getDefaultColumnFamily(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        String value = (String) tableProperties.get(DEFAULT_COLUMN_FAMILY);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<String> getBloomfilter(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        String value = (String) tableProperties.get(BLOOMFILTER);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<Integer> getVersions(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Integer value = (Integer) tableProperties.get(VERSIONS);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<Integer> getMinVersions(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Integer value = (Integer) tableProperties.get(MIN_VERSIONS);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<String> getCompression(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        String value = (String) tableProperties.get(COMPRESSION);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static Optional<Integer> getTimeToLive(Map<String, Object> tableProperties)
    {
        requireNonNull(tableProperties);

        Integer value = (Integer) tableProperties.get(TTL);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
