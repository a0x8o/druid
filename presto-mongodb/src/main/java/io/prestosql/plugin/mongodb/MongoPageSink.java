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
package io.prestosql.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import io.airlift.slice.Slice;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.DateType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.IntegerType;
import io.prestosql.spi.type.NamedTypeSignature;
import io.prestosql.spi.type.SmallintType;
import io.prestosql.spi.type.TimeType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TimestampWithTimeZoneType;
import io.prestosql.spi.type.TinyintType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.spi.type.VarbinaryType;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.prestosql.plugin.mongodb.ObjectIdType.OBJECT_ID;
import static io.prestosql.plugin.mongodb.TypeUtils.isArrayType;
import static io.prestosql.plugin.mongodb.TypeUtils.isMapType;
import static io.prestosql.plugin.mongodb.TypeUtils.isRowType;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static java.lang.Math.toIntExact;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class MongoPageSink
        implements ConnectorPageSink
{
    private final MongoSession mongoSession;
    private final ConnectorSession session;
    private final SchemaTableName schemaTableName;
    private final List<MongoColumnHandle> columns;
    private final String implicitPrefix;

    public MongoPageSink(
            MongoClientConfig config,
            MongoSession mongoSession,
            ConnectorSession session,
            SchemaTableName schemaTableName,
            List<MongoColumnHandle> columns)
    {
        this.mongoSession = mongoSession;
        this.session = session;
        this.schemaTableName = schemaTableName;
        this.columns = columns;
        this.implicitPrefix = requireNonNull(config.getImplicitRowFieldPrefix(), "config.getImplicitRowFieldPrefix() is null");
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        MongoCollection<Document> collection = mongoSession.getCollection(schemaTableName);
        List<Document> batch = new ArrayList<>(page.getPositionCount());

        for (int position = 0; position < page.getPositionCount(); position++) {
            Document doc = new Document();

            for (int channel = 0; channel < page.getChannelCount(); channel++) {
                MongoColumnHandle column = columns.get(channel);
                doc.append(column.getName(), getObjectValue(columns.get(channel).getType(), page.getBlock(channel), position));
            }
            batch.add(doc);
        }

        collection.insertMany(batch, new InsertManyOptions().ordered(true));
        return NOT_BLOCKED;
    }

    private Object getObjectValue(Type type, Block block, int position)
    {
        if (block.isNull(position)) {
            if (type.equals(OBJECT_ID)) {
                return new ObjectId();
            }
            return null;
        }

        if (type.equals(OBJECT_ID)) {
            return new ObjectId(block.getSlice(position, 0, block.getSliceLength(position)).getBytes());
        }
        if (type.equals(BooleanType.BOOLEAN)) {
            return type.getBoolean(block, position);
        }
        if (type.equals(BigintType.BIGINT)) {
            return type.getLong(block, position);
        }
        if (type.equals(IntegerType.INTEGER)) {
            return toIntExact(type.getLong(block, position));
        }
        if (type.equals(SmallintType.SMALLINT)) {
            return Shorts.checkedCast(type.getLong(block, position));
        }
        if (type.equals(TinyintType.TINYINT)) {
            return SignedBytes.checkedCast(type.getLong(block, position));
        }
        if (type.equals(DoubleType.DOUBLE)) {
            return type.getDouble(block, position);
        }
        if (isVarcharType(type)) {
            return type.getSlice(block, position).toStringUtf8();
        }
        if (type.equals(VarbinaryType.VARBINARY)) {
            return new Binary(type.getSlice(block, position).getBytes());
        }
        if (type.equals(DateType.DATE)) {
            long days = type.getLong(block, position);
            return new Date(TimeUnit.DAYS.toMillis(days));
        }
        if (type.equals(TimeType.TIME)) {
            long millisUtc = type.getLong(block, position);
            return new Date(millisUtc);
        }
        if (type.equals(TimestampType.TIMESTAMP)) {
            long millisUtc = type.getLong(block, position);
            return new Date(millisUtc);
        }
        if (type.equals(TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE)) {
            long millisUtc = unpackMillisUtc(type.getLong(block, position));
            return new Date(millisUtc);
        }
        if (type instanceof DecimalType) {
            // TODO: decimal type might not support yet
            // TODO: this code is likely wrong and should switch to Decimals.readBigDecimal()
            DecimalType decimalType = (DecimalType) type;
            BigInteger unscaledValue;
            if (decimalType.isShort()) {
                unscaledValue = BigInteger.valueOf(decimalType.getLong(block, position));
            }
            else {
                unscaledValue = Decimals.decodeUnscaledValue(decimalType.getSlice(block, position));
            }
            return new BigDecimal(unscaledValue);
        }
        if (isArrayType(type)) {
            Type elementType = type.getTypeParameters().get(0);

            Block arrayBlock = block.getObject(position, Block.class);

            List<Object> list = new ArrayList<>(arrayBlock.getPositionCount());
            for (int i = 0; i < arrayBlock.getPositionCount(); i++) {
                Object element = getObjectValue(elementType, arrayBlock, i);
                list.add(element);
            }

            return unmodifiableList(list);
        }
        if (isMapType(type)) {
            Type keyType = type.getTypeParameters().get(0);
            Type valueType = type.getTypeParameters().get(1);

            Block mapBlock = block.getObject(position, Block.class);

            // map type is converted into list of fixed keys document
            List<Object> values = new ArrayList<>(mapBlock.getPositionCount() / 2);
            for (int i = 0; i < mapBlock.getPositionCount(); i += 2) {
                Map<String, Object> mapValue = new HashMap<>();
                mapValue.put("key", getObjectValue(keyType, mapBlock, i));
                mapValue.put("value", getObjectValue(valueType, mapBlock, i + 1));
                values.add(mapValue);
            }

            return unmodifiableList(values);
        }
        if (isRowType(type)) {
            Block rowBlock = block.getObject(position, Block.class);

            List<Type> fieldTypes = type.getTypeParameters();
            if (fieldTypes.size() != rowBlock.getPositionCount()) {
                throw new PrestoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Expected row value field count does not match type field count");
            }

            if (isImplicitRowType(type)) {
                List<Object> rowValue = new ArrayList<>();
                for (int i = 0; i < rowBlock.getPositionCount(); i++) {
                    Object element = getObjectValue(fieldTypes.get(i), rowBlock, i);
                    rowValue.add(element);
                }
                return unmodifiableList(rowValue);
            }

            Map<String, Object> rowValue = new HashMap<>();
            for (int i = 0; i < rowBlock.getPositionCount(); i++) {
                rowValue.put(
                        type.getTypeSignature().getParameters().get(i).getNamedTypeSignature().getName().orElse("field" + i),
                        getObjectValue(fieldTypes.get(i), rowBlock, i));
            }
            return unmodifiableMap(rowValue);
        }

        throw new PrestoException(NOT_SUPPORTED, "unsupported type: " + type);
    }

    private boolean isImplicitRowType(Type type)
    {
        return type.getTypeSignature().getParameters()
                .stream()
                .map(TypeSignatureParameter::getNamedTypeSignature)
                .map(NamedTypeSignature::getName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .allMatch(name -> name.startsWith(implicitPrefix));
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        return completedFuture(ImmutableList.of());
    }

    @Override
    public void abort()
    {
    }
}
