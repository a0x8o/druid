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
package io.prestosql.tests.datatype;

import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.IntegerType;
import io.prestosql.spi.type.RealType;
import io.prestosql.spi.type.SmallintType;
import io.prestosql.spi.type.TinyintType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarbinaryType;
import io.prestosql.spi.type.VarcharType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.io.BaseEncoding.base16;
import static io.prestosql.spi.type.CharType.createCharType;
import static io.prestosql.spi.type.Chars.padSpaces;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.type.JsonType.JSON;
import static java.lang.String.format;
import static java.math.RoundingMode.UNNECESSARY;
import static java.util.function.Function.identity;

public class DataType<T>
{
    private final String insertType;
    private final Type prestoResultType;
    private final Function<T, String> toLiteral;
    private final Function<T, ?> toPrestoQueryResult;

    public static DataType<Boolean> booleanDataType()
    {
        return dataType("boolean", BooleanType.BOOLEAN);
    }

    public static DataType<Long> bigintDataType()
    {
        return dataType("bigint", BigintType.BIGINT);
    }

    public static DataType<Integer> integerDataType()
    {
        return dataType("integer", IntegerType.INTEGER);
    }

    public static DataType<Short> smallintDataType()
    {
        return dataType("smallint", SmallintType.SMALLINT);
    }

    public static DataType<Byte> tinyintDataType()
    {
        return dataType("tinyint", TinyintType.TINYINT);
    }

    public static DataType<Double> doubleDataType()
    {
        return dataType("double", DoubleType.DOUBLE);
    }

    public static DataType<Float> realDataType()
    {
        return dataType("real", RealType.REAL);
    }

    public static DataType<String> varcharDataType(int size)
    {
        return varcharDataType(size, "");
    }

    public static DataType<String> varcharDataType(int size, String properties)
    {
        return varcharDataType(Optional.of(size), properties);
    }

    public static DataType<String> varcharDataType()
    {
        return varcharDataType(Optional.empty(), "");
    }

    private static DataType<String> varcharDataType(Optional<Integer> length, String properties)
    {
        String prefix = length.map(size -> "varchar(" + size + ")").orElse("varchar");
        String suffix = properties.isEmpty() ? "" : " " + properties;
        VarcharType varcharType = length.map(VarcharType::createVarcharType).orElse(createUnboundedVarcharType());
        return stringDataType(prefix + suffix, varcharType);
    }

    public static DataType<String> stringDataType(String insertType, Type prestoResultType)
    {
        return dataType(insertType, prestoResultType, DataType::formatStringLiteral, Function.identity());
    }

    public static DataType<String> charDataType(int length)
    {
        return charDataType(length, "");
    }

    public static DataType<String> charDataType(int length, String properties)
    {
        String suffix = properties.isEmpty() ? "" : " " + properties;
        return charDataType("char(" + length + ")" + suffix, length);
    }

    public static DataType<String> charDataType(String insertType, int length)
    {
        CharType charType = createCharType(length);
        return dataType(insertType, charType, DataType::formatStringLiteral, input -> padSpaces(input, charType));
    }

    public static DataType<byte[]> varbinaryDataType()
    {
        return dataType("varbinary", VarbinaryType.VARBINARY, DataType::binaryLiteral, Function.identity());
    }

    public static DataType<BigDecimal> decimalDataType(int precision, int scale)
    {
        String databaseType = format("decimal(%s, %s)", precision, scale);
        return dataType(
                databaseType,
                createDecimalType(precision, scale),
                bigDecimal -> format("CAST('%s' AS %s)", bigDecimal, databaseType),
                bigDecimal -> bigDecimal.setScale(scale, UNNECESSARY));
    }

    public static DataType<LocalDate> dateDataType()
    {
        return dataType(
                "date",
                DATE,
                DateTimeFormatter.ofPattern("'DATE '''yyyy-MM-dd''")::format,
                identity());
    }

    public static DataType<LocalDateTime> timestampDataType()
    {
        return dataType(
                "timestamp",
                TIMESTAMP,
                DateTimeFormatter.ofPattern("'TIMESTAMP '''yyyy-MM-dd HH:mm:ss.SSS''")::format,
                identity());
    }

    public static DataType<String> jsonDataType()
    {
        return dataType(
                "json",
                JSON,
                value -> "JSON " + formatStringLiteral(value),
                identity());
    }

    public static String formatStringLiteral(String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Formats bytes using SQL standard format for binary string literal
     */
    public static String binaryLiteral(byte[] value)
    {
        return "X'" + base16().encode(value) + "'";
    }

    private static <T> DataType<T> dataType(String insertType, Type prestoResultType)
    {
        return new DataType<>(insertType, prestoResultType, Object::toString, Function.identity());
    }

    public static <T> DataType<T> dataType(String insertType, Type prestoResultType, Function<T, String> toLiteral, Function<T, ?> toPrestoQueryResult)
    {
        return new DataType<>(insertType, prestoResultType, toLiteral, toPrestoQueryResult);
    }

    private DataType(String insertType, Type prestoResultType, Function<T, String> toLiteral, Function<T, ?> toPrestoQueryResult)
    {
        this.insertType = insertType;
        this.prestoResultType = prestoResultType;
        this.toLiteral = toLiteral;
        this.toPrestoQueryResult = toPrestoQueryResult;
    }

    public String toLiteral(T inputValue)
    {
        if (inputValue == null) {
            return "NULL";
        }
        return toLiteral.apply(inputValue);
    }

    public Object toPrestoQueryResult(T inputValue)
    {
        if (inputValue == null) {
            return null;
        }
        return toPrestoQueryResult.apply(inputValue);
    }

    public String getInsertType()
    {
        return insertType;
    }

    public Type getPrestoResultType()
    {
        return prestoResultType;
    }
}
