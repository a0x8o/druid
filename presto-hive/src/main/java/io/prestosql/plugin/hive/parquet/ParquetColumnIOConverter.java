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
package io.prestosql.plugin.hive.parquet;

import com.google.common.collect.ImmutableList;
import io.prestosql.parquet.Field;
import io.prestosql.parquet.GroupField;
import io.prestosql.parquet.PrimitiveField;
import io.prestosql.parquet.RichColumnDescriptor;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.NamedTypeSignature;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignatureParameter;
import org.apache.parquet.io.ColumnIO;
import org.apache.parquet.io.GroupColumnIO;
import org.apache.parquet.io.PrimitiveColumnIO;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.prestosql.parquet.ParquetTypeUtils.getArrayElementColumn;
import static io.prestosql.parquet.ParquetTypeUtils.getMapKeyValueColumn;
import static io.prestosql.parquet.ParquetTypeUtils.lookupColumnByName;
import static org.apache.parquet.io.ColumnIOUtil.columnDefinitionLevel;
import static org.apache.parquet.io.ColumnIOUtil.columnRepetitionLevel;
import static org.apache.parquet.schema.Type.Repetition.OPTIONAL;

public final class ParquetColumnIOConverter
{
    private ParquetColumnIOConverter() {}

    public static Optional<Field> constructField(Type type, ColumnIO columnIO)
    {
        if (columnIO == null) {
            return Optional.empty();
        }
        boolean required = columnIO.getType().getRepetition() != OPTIONAL;
        int repetitionLevel = columnRepetitionLevel(columnIO);
        int definitionLevel = columnDefinitionLevel(columnIO);
        if (type instanceof RowType) {
            GroupColumnIO groupColumnIO = (GroupColumnIO) columnIO;
            List<Type> parameters = type.getTypeParameters();
            ImmutableList.Builder<Optional<Field>> fieldsBuilder = ImmutableList.builder();
            List<TypeSignatureParameter> fields = type.getTypeSignature().getParameters();
            boolean structHasParameters = false;
            for (int i = 0; i < fields.size(); i++) {
                NamedTypeSignature namedTypeSignature = fields.get(i).getNamedTypeSignature();
                String name = namedTypeSignature.getName().get().toLowerCase(Locale.ENGLISH);
                Optional<Field> field = constructField(parameters.get(i), lookupColumnByName(groupColumnIO, name));
                structHasParameters |= field.isPresent();
                fieldsBuilder.add(field);
            }
            if (structHasParameters) {
                return Optional.of(new GroupField(type, repetitionLevel, definitionLevel, required, fieldsBuilder.build()));
            }
            return Optional.empty();
        }
        if (type instanceof MapType) {
            GroupColumnIO groupColumnIO = (GroupColumnIO) columnIO;
            MapType mapType = (MapType) type;
            GroupColumnIO keyValueColumnIO = getMapKeyValueColumn(groupColumnIO);
            if (keyValueColumnIO.getChildrenCount() != 2) {
                return Optional.empty();
            }
            Optional<Field> keyField = constructField(mapType.getKeyType(), keyValueColumnIO.getChild(0));
            Optional<Field> valueField = constructField(mapType.getValueType(), keyValueColumnIO.getChild(1));
            return Optional.of(new GroupField(type, repetitionLevel, definitionLevel, required, ImmutableList.of(keyField, valueField)));
        }
        if (type instanceof ArrayType) {
            GroupColumnIO groupColumnIO = (GroupColumnIO) columnIO;
            List<Type> types = type.getTypeParameters();
            if (groupColumnIO.getChildrenCount() != 1) {
                return Optional.empty();
            }
            Optional<Field> field = constructField(types.get(0), getArrayElementColumn(groupColumnIO.getChild(0)));
            return Optional.of(new GroupField(type, repetitionLevel, definitionLevel, required, ImmutableList.of(field)));
        }
        PrimitiveColumnIO primitiveColumnIO = (PrimitiveColumnIO) columnIO;
        RichColumnDescriptor column = new RichColumnDescriptor(primitiveColumnIO.getColumnDescriptor(), columnIO.getType().asPrimitiveType());
        return Optional.of(new PrimitiveField(type, repetitionLevel, definitionLevel, required, column, primitiveColumnIO.getId()));
    }
}
