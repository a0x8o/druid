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
package io.prestosql.plugin.cassandra;

import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.querybuilder.Insert;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.SignedBytes;
import io.airlift.slice.Slice;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.type.Type;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.primitives.Shorts.checkedCast;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class CassandraPageSink
        implements ConnectorPageSink
{
    private static final DateTimeFormatter DATE_FORMATTER = ISODateTimeFormat.date().withZoneUTC();

    private final CassandraSession cassandraSession;
    private final PreparedStatement insert;
    private final List<Type> columnTypes;
    private final boolean generateUUID;
    private final Function<Long, Object> toCassandraDate;

    public CassandraPageSink(
            CassandraSession cassandraSession,
            ProtocolVersion protocolVersion,
            String schemaName,
            String tableName,
            List<String> columnNames,
            List<Type> columnTypes,
            boolean generateUUID)
    {
        this.cassandraSession = requireNonNull(cassandraSession, "cassandraSession");
        requireNonNull(schemaName, "schemaName is null");
        requireNonNull(tableName, "tableName is null");
        requireNonNull(columnNames, "columnNames is null");
        this.columnTypes = ImmutableList.copyOf(requireNonNull(columnTypes, "columnTypes is null"));
        this.generateUUID = generateUUID;

        if (protocolVersion.toInt() <= ProtocolVersion.V3.toInt()) {
            this.toCassandraDate = value -> DATE_FORMATTER.print(TimeUnit.DAYS.toMillis(value));
        }
        else {
            this.toCassandraDate = value -> LocalDate.fromDaysSinceEpoch(toIntExact(value));
        }

        Insert insert = insertInto(schemaName, tableName);
        if (generateUUID) {
            insert.value("id", bindMarker());
        }
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            checkArgument(columnName != null, "columnName is null at position: %s", i);
            insert.value(columnName, bindMarker());
        }
        this.insert = cassandraSession.prepare(insert);
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        for (int position = 0; position < page.getPositionCount(); position++) {
            List<Object> values = new ArrayList<>(columnTypes.size() + 1);
            if (generateUUID) {
                values.add(UUID.randomUUID());
            }

            for (int channel = 0; channel < page.getChannelCount(); channel++) {
                appendColumn(values, page, position, channel);
            }

            cassandraSession.execute(insert.bind(values.toArray()));
        }
        return NOT_BLOCKED;
    }

    private void appendColumn(List<Object> values, Page page, int position, int channel)
    {
        Block block = page.getBlock(channel);
        Type type = columnTypes.get(channel);
        if (block.isNull(position)) {
            values.add(null);
        }
        else if (BOOLEAN.equals(type)) {
            values.add(type.getBoolean(block, position));
        }
        else if (BIGINT.equals(type)) {
            values.add(type.getLong(block, position));
        }
        else if (INTEGER.equals(type)) {
            values.add(toIntExact(type.getLong(block, position)));
        }
        else if (SMALLINT.equals(type)) {
            values.add(checkedCast(type.getLong(block, position)));
        }
        else if (TINYINT.equals(type)) {
            values.add(SignedBytes.checkedCast(type.getLong(block, position)));
        }
        else if (DOUBLE.equals(type)) {
            values.add(type.getDouble(block, position));
        }
        else if (REAL.equals(type)) {
            values.add(intBitsToFloat(toIntExact(type.getLong(block, position))));
        }
        else if (DATE.equals(type)) {
            values.add(toCassandraDate.apply(type.getLong(block, position)));
        }
        else if (TIMESTAMP.equals(type)) {
            values.add(new Timestamp(type.getLong(block, position)));
        }
        else if (isVarcharType(type)) {
            values.add(type.getSlice(block, position).toStringUtf8());
        }
        else if (VARBINARY.equals(type)) {
            values.add(type.getSlice(block, position).toByteBuffer());
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        // the committer does not need any additional info
        return completedFuture(ImmutableList.of());
    }

    @Override
    public void abort() {}
}
