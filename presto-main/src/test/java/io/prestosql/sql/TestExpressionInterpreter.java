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
package io.prestosql.sql;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.metadata.Metadata;
import io.prestosql.operator.scalar.FunctionAssertions;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.SqlTimestampWithTimeZone;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarbinaryType;
import io.prestosql.sql.parser.ParsingOptions;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.ExpressionInterpreter;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.TypeAnalyzer;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.LikePredicate;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.StringLiteral;
import org.intellij.lang.annotations.Language;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
import org.testng.annotations.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.sql.ExpressionFormatter.formatExpression;
import static io.prestosql.sql.ExpressionUtils.rewriteIdentifiersToSymbolReferences;
import static io.prestosql.sql.ParsingUtil.createParsingOptions;
import static io.prestosql.sql.planner.ExpressionInterpreter.expressionInterpreter;
import static io.prestosql.sql.planner.ExpressionInterpreter.expressionOptimizer;
import static io.prestosql.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.prestosql.util.DateTimeZoneIndex.getDateTimeZone;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class TestExpressionInterpreter
{
    private static final int TEST_VARCHAR_TYPE_LENGTH = 17;
    private static final TypeProvider SYMBOL_TYPES = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(new Symbol("bound_integer"), INTEGER)
            .put(new Symbol("bound_long"), BIGINT)
            .put(new Symbol("bound_string"), createVarcharType(TEST_VARCHAR_TYPE_LENGTH))
            .put(new Symbol("bound_varbinary"), VarbinaryType.VARBINARY)
            .put(new Symbol("bound_double"), DOUBLE)
            .put(new Symbol("bound_boolean"), BOOLEAN)
            .put(new Symbol("bound_date"), DATE)
            .put(new Symbol("bound_time"), TIME)
            .put(new Symbol("bound_timestamp"), TIMESTAMP)
            .put(new Symbol("bound_pattern"), VARCHAR)
            .put(new Symbol("bound_null_string"), VARCHAR)
            .put(new Symbol("bound_decimal_short"), createDecimalType(5, 2))
            .put(new Symbol("bound_decimal_long"), createDecimalType(23, 3))
            .put(new Symbol("time"), BIGINT) // for testing reserved identifiers
            .put(new Symbol("unbound_integer"), INTEGER)
            .put(new Symbol("unbound_long"), BIGINT)
            .put(new Symbol("unbound_long2"), BIGINT)
            .put(new Symbol("unbound_long3"), BIGINT)
            .put(new Symbol("unbound_string"), VARCHAR)
            .put(new Symbol("unbound_double"), DOUBLE)
            .put(new Symbol("unbound_boolean"), BOOLEAN)
            .put(new Symbol("unbound_date"), DATE)
            .put(new Symbol("unbound_time"), TIME)
            .put(new Symbol("unbound_timestamp"), TIMESTAMP)
            .put(new Symbol("unbound_interval"), INTERVAL_DAY_TIME)
            .put(new Symbol("unbound_pattern"), VARCHAR)
            .put(new Symbol("unbound_null_string"), VARCHAR)
            .build());

    private static final SqlParser SQL_PARSER = new SqlParser();
    private static final Metadata METADATA = createTestMetadataManager();
    private static final TypeAnalyzer TYPE_ANALYZER = new TypeAnalyzer(SQL_PARSER, METADATA);

    @Test
    public void testAnd()
    {
        assertOptimizedEquals("true AND false", "false");
        assertOptimizedEquals("false AND true", "false");
        assertOptimizedEquals("false AND false", "false");

        assertOptimizedEquals("true AND NULL", "NULL");
        assertOptimizedEquals("false AND NULL", "false");
        assertOptimizedEquals("NULL AND true", "NULL");
        assertOptimizedEquals("NULL AND false", "false");
        assertOptimizedEquals("NULL AND NULL", "NULL");

        assertOptimizedEquals("unbound_string='z' AND true", "unbound_string='z'");
        assertOptimizedEquals("unbound_string='z' AND false", "false");
        assertOptimizedEquals("true AND unbound_string='z'", "unbound_string='z'");
        assertOptimizedEquals("false AND unbound_string='z'", "false");

        assertOptimizedEquals("bound_string='z' AND bound_long=1+1", "bound_string='z' AND bound_long=2");
    }

    @Test
    public void testOr()
    {
        assertOptimizedEquals("true OR true", "true");
        assertOptimizedEquals("true OR false", "true");
        assertOptimizedEquals("false OR true", "true");
        assertOptimizedEquals("false OR false", "false");

        assertOptimizedEquals("true OR NULL", "true");
        assertOptimizedEquals("NULL OR true", "true");
        assertOptimizedEquals("NULL OR NULL", "NULL");

        assertOptimizedEquals("false OR NULL", "NULL");
        assertOptimizedEquals("NULL OR false", "NULL");

        assertOptimizedEquals("bound_string='z' OR true", "true");
        assertOptimizedEquals("bound_string='z' OR false", "bound_string='z'");
        assertOptimizedEquals("true OR bound_string='z'", "true");
        assertOptimizedEquals("false OR bound_string='z'", "bound_string='z'");

        assertOptimizedEquals("bound_string='z' OR bound_long=1+1", "bound_string='z' OR bound_long=2");
    }

    @Test
    public void testComparison()
    {
        assertOptimizedEquals("NULL = NULL", "NULL");

        assertOptimizedEquals("'a' = 'b'", "false");
        assertOptimizedEquals("'a' = 'a'", "true");
        assertOptimizedEquals("'a' = NULL", "NULL");
        assertOptimizedEquals("NULL = 'a'", "NULL");
        assertOptimizedEquals("bound_integer = 1234", "true");
        assertOptimizedEquals("bound_integer = 12340000000", "false");
        assertOptimizedEquals("bound_long = BIGINT '1234'", "true");
        assertOptimizedEquals("bound_long = 1234", "true");
        assertOptimizedEquals("bound_double = 12.34", "true");
        assertOptimizedEquals("bound_string = 'hello'", "true");
        assertOptimizedEquals("unbound_long = bound_long", "unbound_long = 1234");

        assertOptimizedEquals("10151082135029368 = 10151082135029369", "false");

        assertOptimizedEquals("bound_varbinary = X'a b'", "true");
        assertOptimizedEquals("bound_varbinary = X'a d'", "false");

        assertOptimizedEquals("1.1 = 1.1", "true");
        assertOptimizedEquals("9876543210.9874561203 = 9876543210.9874561203", "true");
        assertOptimizedEquals("bound_decimal_short = 123.45", "true");
        assertOptimizedEquals("bound_decimal_long = 12345678901234567890.123", "true");
    }

    @Test
    public void testIsDistinctFrom()
    {
        assertOptimizedEquals("NULL IS DISTINCT FROM NULL", "false");

        assertOptimizedEquals("3 IS DISTINCT FROM 4", "true");
        assertOptimizedEquals("3 IS DISTINCT FROM BIGINT '4'", "true");
        assertOptimizedEquals("3 IS DISTINCT FROM 4000000000", "true");
        assertOptimizedEquals("3 IS DISTINCT FROM 3", "false");
        assertOptimizedEquals("3 IS DISTINCT FROM NULL", "true");
        assertOptimizedEquals("NULL IS DISTINCT FROM 3", "true");

        assertOptimizedEquals("10151082135029368 IS DISTINCT FROM 10151082135029369", "true");

        assertOptimizedEquals("1.1 IS DISTINCT FROM 1.1", "false");
        assertOptimizedEquals("9876543210.9874561203 IS DISTINCT FROM NULL", "true");
        assertOptimizedEquals("bound_decimal_short IS DISTINCT FROM NULL", "true");
        assertOptimizedEquals("bound_decimal_long IS DISTINCT FROM 12345678901234567890.123", "false");
        assertOptimizedMatches("unbound_integer IS DISTINCT FROM 1", "unbound_integer IS DISTINCT FROM 1");
        assertOptimizedMatches("unbound_integer IS DISTINCT FROM NULL", "unbound_integer IS NOT NULL");
        assertOptimizedMatches("NULL IS DISTINCT FROM unbound_integer", "unbound_integer IS NOT NULL");
    }

    @Test
    public void testIsNull()
    {
        assertOptimizedEquals("NULL IS NULL", "true");
        assertOptimizedEquals("1 IS NULL", "false");
        assertOptimizedEquals("10000000000 IS NULL", "false");
        assertOptimizedEquals("BIGINT '1' IS NULL", "false");
        assertOptimizedEquals("1.0 IS NULL", "false");
        assertOptimizedEquals("'a' IS NULL", "false");
        assertOptimizedEquals("true IS NULL", "false");
        assertOptimizedEquals("NULL+1 IS NULL", "true");
        assertOptimizedEquals("unbound_string IS NULL", "unbound_string IS NULL");
        assertOptimizedEquals("unbound_long+(1+1) IS NULL", "unbound_long+2 IS NULL");
        assertOptimizedEquals("1.1 IS NULL", "false");
        assertOptimizedEquals("9876543210.9874561203 IS NULL", "false");
        assertOptimizedEquals("bound_decimal_short IS NULL", "false");
        assertOptimizedEquals("bound_decimal_long IS NULL", "false");
    }

    @Test
    public void testIsNotNull()
    {
        assertOptimizedEquals("NULL IS NOT NULL", "false");
        assertOptimizedEquals("1 IS NOT NULL", "true");
        assertOptimizedEquals("10000000000 IS NOT NULL", "true");
        assertOptimizedEquals("BIGINT '1' IS NOT NULL", "true");
        assertOptimizedEquals("1.0 IS NOT NULL", "true");
        assertOptimizedEquals("'a' IS NOT NULL", "true");
        assertOptimizedEquals("true IS NOT NULL", "true");
        assertOptimizedEquals("NULL+1 IS NOT NULL", "false");
        assertOptimizedEquals("unbound_string IS NOT NULL", "unbound_string IS NOT NULL");
        assertOptimizedEquals("unbound_long+(1+1) IS NOT NULL", "unbound_long+2 IS NOT NULL");
        assertOptimizedEquals("1.1 IS NOT NULL", "true");
        assertOptimizedEquals("9876543210.9874561203 IS NOT NULL", "true");
        assertOptimizedEquals("bound_decimal_short IS NOT NULL", "true");
        assertOptimizedEquals("bound_decimal_long IS NOT NULL", "true");
    }

    @Test
    public void testNullIf()
    {
        assertOptimizedEquals("nullif(true, true)", "NULL");
        assertOptimizedEquals("nullif(true, false)", "true");
        assertOptimizedEquals("nullif(NULL, false)", "NULL");
        assertOptimizedEquals("nullif(true, NULL)", "true");

        assertOptimizedEquals("nullif('a', 'a')", "NULL");
        assertOptimizedEquals("nullif('a', 'b')", "'a'");
        assertOptimizedEquals("nullif(NULL, 'b')", "NULL");
        assertOptimizedEquals("nullif('a', NULL)", "'a'");

        assertOptimizedEquals("nullif(1, 1)", "NULL");
        assertOptimizedEquals("nullif(1, 2)", "1");
        assertOptimizedEquals("nullif(1, BIGINT '2')", "1");
        assertOptimizedEquals("nullif(1, 20000000000)", "1");
        assertOptimizedEquals("nullif(1.0E0, 1)", "NULL");
        assertOptimizedEquals("nullif(10000000000.0E0, 10000000000)", "NULL");
        assertOptimizedEquals("nullif(1.1E0, 1)", "1.1E0");
        assertOptimizedEquals("nullif(1.1E0, 1.1E0)", "NULL");
        assertOptimizedEquals("nullif(1, 2-1)", "NULL");
        assertOptimizedEquals("nullif(NULL, NULL)", "NULL");
        assertOptimizedEquals("nullif(1, NULL)", "1");
        assertOptimizedEquals("nullif(unbound_long, 1)", "nullif(unbound_long, 1)");
        assertOptimizedEquals("nullif(unbound_long, unbound_long2)", "nullif(unbound_long, unbound_long2)");
        assertOptimizedEquals("nullif(unbound_long, unbound_long2+(1+1))", "nullif(unbound_long, unbound_long2+2)");

        assertOptimizedEquals("nullif(1.1, 1.2)", "1.1");
        assertOptimizedEquals("nullif(9876543210.9874561203, 9876543210.9874561203)", "NULL");
        assertOptimizedEquals("nullif(bound_decimal_short, 123.45)", "NULL");
        assertOptimizedEquals("nullif(bound_decimal_long, 12345678901234567890.123)", "NULL");
        assertOptimizedEquals("nullif(ARRAY[CAST(1 AS bigint)], ARRAY[CAST(1 AS bigint)]) IS NULL", "true");
        assertOptimizedEquals("nullif(ARRAY[CAST(1 AS bigint)], ARRAY[CAST(NULL AS bigint)]) IS NULL", "false");
        assertOptimizedEquals("nullif(ARRAY[CAST(NULL AS bigint)], ARRAY[CAST(NULL AS bigint)]) IS NULL", "false");
    }

    @Test
    public void testNegative()
    {
        assertOptimizedEquals("-(1)", "-1");
        assertOptimizedEquals("-(BIGINT '1')", "BIGINT '-1'");
        assertOptimizedEquals("-(unbound_long+1)", "-(unbound_long+1)");
        assertOptimizedEquals("-(1+1)", "-2");
        assertOptimizedEquals("-(1+ BIGINT '1')", "BIGINT '-2'");
        assertOptimizedEquals("-(CAST(NULL AS bigint))", "NULL");
        assertOptimizedEquals("-(unbound_long+(1+1))", "-(unbound_long+2)");
        assertOptimizedEquals("-(1.1+1.2)", "-2.3");
        assertOptimizedEquals("-(9876543210.9874561203-9876543210.9874561203)", "CAST(0 AS decimal(20,10))");
        assertOptimizedEquals("-(bound_decimal_short+123.45)", "-246.90");
        assertOptimizedEquals("-(bound_decimal_long-12345678901234567890.123)", "CAST(0 AS decimal(20,10))");
    }

    @Test
    public void testNot()
    {
        assertOptimizedEquals("not true", "false");
        assertOptimizedEquals("not false", "true");
        assertOptimizedEquals("not NULL", "NULL");
        assertOptimizedEquals("not 1=1", "false");
        assertOptimizedEquals("not 1=BIGINT '1'", "false");
        assertOptimizedEquals("not 1!=1", "true");
        assertOptimizedEquals("not unbound_long=1", "not unbound_long=1");
        assertOptimizedEquals("not unbound_long=(1+1)", "not unbound_long=2");
    }

    @Test
    public void testFunctionCall()
    {
        assertOptimizedEquals("abs(-5)", "5");
        assertOptimizedEquals("abs(-10-5)", "15");
        assertOptimizedEquals("abs(-bound_integer + 1)", "1233");
        assertOptimizedEquals("abs(-bound_long + 1)", "1233");
        assertOptimizedEquals("abs(-bound_long + BIGINT '1')", "1233");
        assertOptimizedEquals("abs(-bound_long)", "1234");
        assertOptimizedEquals("abs(unbound_long)", "abs(unbound_long)");
        assertOptimizedEquals("abs(unbound_long + 1)", "abs(unbound_long + 1)");
    }

    @Test
    public void testNonDeterministicFunctionCall()
    {
        // optimize should do nothing
        assertOptimizedEquals("random()", "random()");

        // evaluate should execute
        Object value = evaluate("random()");
        assertTrue(value instanceof Double);
        double randomValue = (double) value;
        assertTrue(0 <= randomValue && randomValue < 1);
    }

    @Test
    public void testBetween()
    {
        assertOptimizedEquals("3 BETWEEN 2 AND 4", "true");
        assertOptimizedEquals("2 BETWEEN 3 AND 4", "false");
        assertOptimizedEquals("NULL BETWEEN 2 AND 4", "NULL");
        assertOptimizedEquals("3 BETWEEN NULL AND 4", "NULL");
        assertOptimizedEquals("3 BETWEEN 2 AND NULL", "NULL");

        assertOptimizedEquals("'cc' BETWEEN 'b' AND 'd'", "true");
        assertOptimizedEquals("'b' BETWEEN 'cc' AND 'd'", "false");
        assertOptimizedEquals("NULL BETWEEN 'b' AND 'd'", "NULL");
        assertOptimizedEquals("'cc' BETWEEN NULL AND 'd'", "NULL");
        assertOptimizedEquals("'cc' BETWEEN 'b' AND NULL", "NULL");

        assertOptimizedEquals("bound_integer BETWEEN 1000 AND 2000", "true");
        assertOptimizedEquals("bound_integer BETWEEN 3 AND 4", "false");
        assertOptimizedEquals("bound_long BETWEEN 1000 AND 2000", "true");
        assertOptimizedEquals("bound_long BETWEEN 3 AND 4", "false");
        assertOptimizedEquals("bound_long BETWEEN bound_integer AND (bound_long + 1)", "true");
        assertOptimizedEquals("bound_string BETWEEN 'e' AND 'i'", "true");
        assertOptimizedEquals("bound_string BETWEEN 'a' AND 'b'", "false");

        assertOptimizedEquals("bound_long BETWEEN unbound_long AND 2000 + 1", "1234 BETWEEN unbound_long AND 2001");
        assertOptimizedEquals(
                "bound_string BETWEEN unbound_string AND 'bar'",
                format("CAST('hello' AS varchar(%s)) BETWEEN unbound_string AND 'bar'", TEST_VARCHAR_TYPE_LENGTH));

        assertOptimizedEquals("1.15 BETWEEN 1.1 AND 1.2", "true");
        assertOptimizedEquals("9876543210.98745612035 BETWEEN 9876543210.9874561203 AND 9876543210.9874561204", "true");
        assertOptimizedEquals("123.455 BETWEEN bound_decimal_short AND 123.46", "true");
        assertOptimizedEquals("12345678901234567890.1235 BETWEEN bound_decimal_long AND 12345678901234567890.123", "false");
    }

    @Test
    public void testExtract()
    {
        DateTime dateTime = new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(TEST_SESSION.getTimeZoneKey()));
        double seconds = dateTime.getMillis() / 1000.0;

        assertOptimizedEquals("extract(YEAR FROM from_unixtime(" + seconds + "))", "2001");
        assertOptimizedEquals("extract(QUARTER FROM from_unixtime(" + seconds + "))", "3");
        assertOptimizedEquals("extract(MONTH FROM from_unixtime(" + seconds + "))", "8");
        assertOptimizedEquals("extract(WEEK FROM from_unixtime(" + seconds + "))", "34");
        assertOptimizedEquals("extract(DOW FROM from_unixtime(" + seconds + "))", "3");
        assertOptimizedEquals("extract(DOY FROM from_unixtime(" + seconds + "))", "234");
        assertOptimizedEquals("extract(DAY FROM from_unixtime(" + seconds + "))", "22");
        assertOptimizedEquals("extract(HOUR FROM from_unixtime(" + seconds + "))", "3");
        assertOptimizedEquals("extract(MINUTE FROM from_unixtime(" + seconds + "))", "4");
        assertOptimizedEquals("extract(SECOND FROM from_unixtime(" + seconds + "))", "5");
        assertOptimizedEquals("extract(TIMEZONE_HOUR FROM from_unixtime(" + seconds + ", 7, 9))", "7");
        assertOptimizedEquals("extract(TIMEZONE_MINUTE FROM from_unixtime(" + seconds + ", 7, 9))", "9");

        assertOptimizedEquals("extract(YEAR FROM bound_timestamp)", "2001");
        assertOptimizedEquals("extract(QUARTER FROM bound_timestamp)", "3");
        assertOptimizedEquals("extract(MONTH FROM bound_timestamp)", "8");
        assertOptimizedEquals("extract(WEEK FROM bound_timestamp)", "34");
        assertOptimizedEquals("extract(DOW FROM bound_timestamp)", "2");
        assertOptimizedEquals("extract(DOY FROM bound_timestamp)", "233");
        assertOptimizedEquals("extract(DAY FROM bound_timestamp)", "21");
        assertOptimizedEquals("extract(HOUR FROM bound_timestamp)", "16");
        assertOptimizedEquals("extract(MINUTE FROM bound_timestamp)", "4");
        assertOptimizedEquals("extract(SECOND FROM bound_timestamp)", "5");
        // todo reenable when cast as timestamp with time zone is implemented
        // todo add bound timestamp with time zone
        //assertOptimizedEquals("extract(TIMEZONE_HOUR FROM bound_timestamp)", "0");
        //assertOptimizedEquals("extract(TIMEZONE_MINUTE FROM bound_timestamp)", "0");

        assertOptimizedEquals("extract(YEAR FROM unbound_timestamp)", "extract(YEAR FROM unbound_timestamp)");
        assertOptimizedEquals("extract(SECOND FROM bound_timestamp + INTERVAL '3' SECOND)", "8");
    }

    @Test
    public void testIn()
    {
        assertOptimizedEquals("3 IN (2, 4, 3, 5)", "true");
        assertOptimizedEquals("3 IN (2, 4, 9, 5)", "false");
        assertOptimizedEquals("3 IN (2, NULL, 3, 5)", "true");

        assertOptimizedEquals("'foo' IN ('bar', 'baz', 'foo', 'blah')", "true");
        assertOptimizedEquals("'foo' IN ('bar', 'baz', 'buz', 'blah')", "false");
        assertOptimizedEquals("'foo' IN ('bar', NULL, 'foo', 'blah')", "true");

        assertOptimizedEquals("NULL IN (2, NULL, 3, 5)", "NULL");
        assertOptimizedEquals("3 IN (2, NULL)", "NULL");

        assertOptimizedEquals("bound_integer IN (2, 1234, 3, 5)", "true");
        assertOptimizedEquals("bound_integer IN (2, 4, 3, 5)", "false");
        assertOptimizedEquals("1234 IN (2, bound_integer, 3, 5)", "true");
        assertOptimizedEquals("99 IN (2, bound_integer, 3, 5)", "false");
        assertOptimizedEquals("bound_integer IN (2, bound_integer, 3, 5)", "true");

        assertOptimizedEquals("bound_long IN (2, 1234, 3, 5)", "true");
        assertOptimizedEquals("bound_long IN (2, 4, 3, 5)", "false");
        assertOptimizedEquals("1234 IN (2, bound_long, 3, 5)", "true");
        assertOptimizedEquals("99 IN (2, bound_long, 3, 5)", "false");
        assertOptimizedEquals("bound_long IN (2, bound_long, 3, 5)", "true");

        assertOptimizedEquals("bound_string IN ('bar', 'hello', 'foo', 'blah')", "true");
        assertOptimizedEquals("bound_string IN ('bar', 'baz', 'foo', 'blah')", "false");
        assertOptimizedEquals("'hello' IN ('bar', bound_string, 'foo', 'blah')", "true");
        assertOptimizedEquals("'baz' IN ('bar', bound_string, 'foo', 'blah')", "false");

        assertOptimizedEquals("bound_long IN (2, 1234, unbound_long, 5)", "true");
        assertOptimizedEquals("bound_string IN ('bar', 'hello', unbound_string, 'blah')", "true");

        assertOptimizedEquals("bound_long IN (2, 4, unbound_long, unbound_long2, 9)", "1234 IN (unbound_long, unbound_long2)");
        assertOptimizedEquals("unbound_long IN (2, 4, bound_long, unbound_long2, 5)", "unbound_long IN (2, 4, 1234, unbound_long2, 5)");

        assertOptimizedEquals("1.15 IN (1.1, 1.2, 1.3, 1.15)", "true");
        assertOptimizedEquals("9876543210.98745612035 IN (9876543210.9874561203, 9876543210.9874561204, 9876543210.98745612035)", "true");
        assertOptimizedEquals("bound_decimal_short IN (123.455, 123.46, 123.45)", "true");
        assertOptimizedEquals("bound_decimal_long IN (12345678901234567890.123, 9876543210.9874561204, 9876543210.98745612035)", "true");
        assertOptimizedEquals("bound_decimal_long IN (9876543210.9874561204, NULL, 9876543210.98745612035)", "NULL");

        assertOptimizedEquals("unbound_integer IN (1)", "unbound_integer = 1");
        assertOptimizedEquals("unbound_long IN (unbound_long2)", "unbound_long = unbound_long2");
    }

    @Test
    public void testInComplexTypes()
    {
        assertEvaluatedEquals("ARRAY[1] IN (ARRAY[1])", "true");
        assertEvaluatedEquals("ARRAY[1] IN (ARRAY[2])", "false");
        assertEvaluatedEquals("ARRAY[1] IN (ARRAY[2], ARRAY[1])", "true");
        assertEvaluatedEquals("ARRAY[1] IN (NULL)", "NULL");
        assertEvaluatedEquals("ARRAY[1] IN (NULL, ARRAY[1])", "true");
        assertEvaluatedEquals("ARRAY[1, 2, NULL] IN (ARRAY[2, NULL], ARRAY[1, NULL])", "false");
        assertEvaluatedEquals("ARRAY[1, NULL] IN (ARRAY[2, NULL], NULL)", "NULL");
        assertEvaluatedEquals("ARRAY[NULL] IN (ARRAY[NULL])", "NULL");
        assertEvaluatedEquals("ARRAY[1] IN (ARRAY[NULL])", "NULL");
        assertEvaluatedEquals("ARRAY[NULL] IN (ARRAY[1])", "NULL");
        assertEvaluatedEquals("ARRAY[1, NULL] IN (ARRAY[1, NULL])", "NULL");
        assertEvaluatedEquals("ARRAY[1, NULL] IN (ARRAY[2, NULL])", "false");
        assertEvaluatedEquals("ARRAY[1, NULL] IN (ARRAY[1, NULL], ARRAY[2, NULL])", "NULL");
        assertEvaluatedEquals("ARRAY[1, NULL] IN (ARRAY[1, NULL], ARRAY[2, NULL], ARRAY[1, NULL])", "NULL");
        assertEvaluatedEquals("ARRAY[ARRAY[1, 2], ARRAY[3, 4]] in (ARRAY[ARRAY[1, 2], ARRAY[3, NULL]])", "NULL");

        assertEvaluatedEquals("ROW(1) IN (ROW(1))", "true");
        assertEvaluatedEquals("ROW(1) IN (ROW(2))", "false");
        assertEvaluatedEquals("ROW(1) IN (ROW(2), ROW(1), ROW(2))", "true");
        assertEvaluatedEquals("ROW(1) IN (NULL)", "NULL");
        assertEvaluatedEquals("ROW(1) IN (NULL, ROW(1))", "true");
        assertEvaluatedEquals("ROW(1, NULL) IN (ROW(2, NULL), NULL)", "NULL");
        assertEvaluatedEquals("ROW(NULL) IN (ROW(NULL))", "NULL");
        assertEvaluatedEquals("ROW(1) IN (ROW(NULL))", "NULL");
        assertEvaluatedEquals("ROW(NULL) IN (ROW(1))", "NULL");
        assertEvaluatedEquals("ROW(1, NULL) IN (ROW(1, NULL))", "NULL");
        assertEvaluatedEquals("ROW(1, NULL) IN (ROW(2, NULL))", "false");
        assertEvaluatedEquals("ROW(1, NULL) IN (ROW(1, NULL), ROW(2, NULL))", "NULL");
        assertEvaluatedEquals("ROW(1, NULL) IN (ROW(1, NULL), ROW(2, NULL), ROW(1, NULL))", "NULL");

        assertEvaluatedEquals("map(ARRAY[1], ARRAY[1]) IN (map(ARRAY[1], ARRAY[1]))", "true");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[1]) IN (NULL)", "NULL");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[1]) IN (NULL, map(ARRAY[1], ARRAY[1]))", "true");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[1]) IN (map(ARRAY[1, 2], ARRAY[1, NULL]))", "false");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[2, NULL]), NULL)", "NULL");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[1, NULL]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 3], ARRAY[1, NULL]))", "false");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[NULL]) IN (map(ARRAY[1], ARRAY[NULL]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[1]) IN (map(ARRAY[1], ARRAY[NULL]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1], ARRAY[NULL]) IN (map(ARRAY[1], ARRAY[1]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[1, NULL]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 3], ARRAY[1, NULL]))", "false");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[2, NULL]))", "false");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[1, NULL]), map(ARRAY[1, 2], ARRAY[2, NULL]))", "NULL");
        assertEvaluatedEquals("map(ARRAY[1, 2], ARRAY[1, NULL]) IN (map(ARRAY[1, 2], ARRAY[1, NULL]), map(ARRAY[1, 2], ARRAY[2, NULL]), map(ARRAY[1, 2], ARRAY[1, NULL]))", "NULL");
    }

    @Test
    public void testCurrentTimestamp()
    {
        double current = TEST_SESSION.getStartTime() / 1000.0;
        assertOptimizedEquals("current_timestamp = from_unixtime(" + current + ")", "true");
        double future = current + TimeUnit.MINUTES.toSeconds(1);
        assertOptimizedEquals("current_timestamp > from_unixtime(" + future + ")", "false");
    }

    @Test
    public void testCurrentUser()
    {
        assertOptimizedEquals("current_user", "'" + TEST_SESSION.getUser() + "'");
    }

    @Test
    public void testCastToString()
    {
        // integer
        assertOptimizedEquals("CAST(123 AS varchar(20))", "'123'");
        assertOptimizedEquals("CAST(-123 AS varchar(20))", "'-123'");

        // bigint
        assertOptimizedEquals("CAST(BIGINT '123' AS varchar)", "'123'");
        assertOptimizedEquals("CAST(12300000000 AS varchar)", "'12300000000'");
        assertOptimizedEquals("CAST(-12300000000 AS varchar)", "'-12300000000'");

        // double
        assertOptimizedEquals("CAST(123.0E0 AS varchar)", "'123.0'");
        assertOptimizedEquals("CAST(-123.0E0 AS varchar)", "'-123.0'");
        assertOptimizedEquals("CAST(123.456E0 AS varchar)", "'123.456'");
        assertOptimizedEquals("CAST(-123.456E0 AS varchar)", "'-123.456'");

        // boolean
        assertOptimizedEquals("CAST(true AS varchar)", "'true'");
        assertOptimizedEquals("CAST(false AS varchar)", "'false'");

        // string
        assertOptimizedEquals("CAST('xyz' AS varchar)", "'xyz'");

        // NULL
        assertOptimizedEquals("CAST(NULL AS varchar)", "NULL");

        // decimal
        assertOptimizedEquals("CAST(1.1 AS varchar)", "'1.1'");
        // TODO enabled when DECIMAL is default for literal: assertOptimizedEquals("CAST(12345678901234567890.123 AS varchar)", "'12345678901234567890.123'");
    }

    @Test
    public void testCastToBoolean()
    {
        // integer
        assertOptimizedEquals("CAST(123 AS boolean)", "true");
        assertOptimizedEquals("CAST(-123 AS boolean)", "true");
        assertOptimizedEquals("CAST(0 AS boolean)", "false");

        // bigint
        assertOptimizedEquals("CAST(12300000000 AS boolean)", "true");
        assertOptimizedEquals("CAST(-12300000000 AS boolean)", "true");
        assertOptimizedEquals("CAST(BIGINT '0' AS boolean)", "false");

        // boolean
        assertOptimizedEquals("CAST(true AS boolean)", "true");
        assertOptimizedEquals("CAST(false AS boolean)", "false");

        // string
        assertOptimizedEquals("CAST('true' AS boolean)", "true");
        assertOptimizedEquals("CAST('false' AS boolean)", "false");
        assertOptimizedEquals("CAST('t' AS boolean)", "true");
        assertOptimizedEquals("CAST('f' AS boolean)", "false");
        assertOptimizedEquals("CAST('1' AS boolean)", "true");
        assertOptimizedEquals("CAST('0' AS boolean)", "false");

        // NULL
        assertOptimizedEquals("CAST(NULL AS boolean)", "NULL");

        // double
        assertOptimizedEquals("CAST(123.45E0 AS boolean)", "true");
        assertOptimizedEquals("CAST(-123.45E0 AS boolean)", "true");
        assertOptimizedEquals("CAST(0.0E0 AS boolean)", "false");

        // decimal
        assertOptimizedEquals("CAST(0.00 AS boolean)", "false");
        assertOptimizedEquals("CAST(7.8 AS boolean)", "true");
        assertOptimizedEquals("CAST(12345678901234567890.123 AS boolean)", "true");
        assertOptimizedEquals("CAST(00000000000000000000.000 AS boolean)", "false");
    }

    @Test
    public void testCastToBigint()
    {
        // integer
        assertOptimizedEquals("CAST(0 AS bigint)", "0");
        assertOptimizedEquals("CAST(123 AS bigint)", "123");
        assertOptimizedEquals("CAST(-123 AS bigint)", "-123");

        // bigint
        assertOptimizedEquals("CAST(BIGINT '0' AS bigint)", "0");
        assertOptimizedEquals("CAST(BIGINT '123' AS bigint)", "123");
        assertOptimizedEquals("CAST(BIGINT '-123' AS bigint)", "-123");

        // double
        assertOptimizedEquals("CAST(123.0E0 AS bigint)", "123");
        assertOptimizedEquals("CAST(-123.0E0 AS bigint)", "-123");
        assertOptimizedEquals("CAST(123.456E0 AS bigint)", "123");
        assertOptimizedEquals("CAST(-123.456E0 AS bigint)", "-123");

        // boolean
        assertOptimizedEquals("CAST(true AS bigint)", "1");
        assertOptimizedEquals("CAST(false AS bigint)", "0");

        // string
        assertOptimizedEquals("CAST('123' AS bigint)", "123");
        assertOptimizedEquals("CAST('-123' AS bigint)", "-123");

        // NULL
        assertOptimizedEquals("CAST(NULL AS bigint)", "NULL");

        // decimal
        assertOptimizedEquals("CAST(DECIMAL '1.01' AS bigint)", "1");
        assertOptimizedEquals("CAST(DECIMAL '7.8' AS bigint)", "8");
        assertOptimizedEquals("CAST(DECIMAL '1234567890.123' AS bigint)", "1234567890");
        assertOptimizedEquals("CAST(DECIMAL '00000000000000000000.000' AS bigint)", "0");
    }

    @Test
    public void testCastToInteger()
    {
        // integer
        assertOptimizedEquals("CAST(0 AS integer)", "0");
        assertOptimizedEquals("CAST(123 AS integer)", "123");
        assertOptimizedEquals("CAST(-123 AS integer)", "-123");

        // bigint
        assertOptimizedEquals("CAST(BIGINT '0' AS integer)", "0");
        assertOptimizedEquals("CAST(BIGINT '123' AS integer)", "123");
        assertOptimizedEquals("CAST(BIGINT '-123' AS integer)", "-123");

        // double
        assertOptimizedEquals("CAST(123.0E0 AS integer)", "123");
        assertOptimizedEquals("CAST(-123.0E0 AS integer)", "-123");
        assertOptimizedEquals("CAST(123.456E0 AS integer)", "123");
        assertOptimizedEquals("CAST(-123.456E0 AS integer)", "-123");

        // boolean
        assertOptimizedEquals("CAST(true AS integer)", "1");
        assertOptimizedEquals("CAST(false AS integer)", "0");

        // string
        assertOptimizedEquals("CAST('123' AS integer)", "123");
        assertOptimizedEquals("CAST('-123' AS integer)", "-123");

        // NULL
        assertOptimizedEquals("CAST(NULL AS integer)", "NULL");
    }

    @Test
    public void testCastToDouble()
    {
        // integer
        assertOptimizedEquals("CAST(0 AS double)", "0.0E0");
        assertOptimizedEquals("CAST(123 AS double)", "123.0E0");
        assertOptimizedEquals("CAST(-123 AS double)", "-123.0E0");

        // bigint
        assertOptimizedEquals("CAST(BIGINT '0' AS double)", "0.0E0");
        assertOptimizedEquals("CAST(12300000000 AS double)", "12300000000.0E0");
        assertOptimizedEquals("CAST(-12300000000 AS double)", "-12300000000.0E0");

        // double
        assertOptimizedEquals("CAST(123.0E0 AS double)", "123.0E0");
        assertOptimizedEquals("CAST(-123.0E0 AS double)", "-123.0E0");
        assertOptimizedEquals("CAST(123.456E0 AS double)", "123.456E0");
        assertOptimizedEquals("CAST(-123.456E0 AS double)", "-123.456E0");

        // string
        assertOptimizedEquals("CAST('0' AS double)", "0.0E0");
        assertOptimizedEquals("CAST('123' AS double)", "123.0E0");
        assertOptimizedEquals("CAST('-123' AS double)", "-123.0E0");
        assertOptimizedEquals("CAST('123.0E0' AS double)", "123.0E0");
        assertOptimizedEquals("CAST('-123.0E0' AS double)", "-123.0E0");
        assertOptimizedEquals("CAST('123.456E0' AS double)", "123.456E0");
        assertOptimizedEquals("CAST('-123.456E0' AS double)", "-123.456E0");

        // NULL
        assertOptimizedEquals("CAST(NULL AS double)", "NULL");

        // boolean
        assertOptimizedEquals("CAST(true AS double)", "1.0E0");
        assertOptimizedEquals("CAST(false AS double)", "0.0E0");

        // decimal
        assertOptimizedEquals("CAST(1.01 AS double)", "DOUBLE '1.01'");
        assertOptimizedEquals("CAST(7.8 AS double)", "DOUBLE '7.8'");
        assertOptimizedEquals("CAST(1234567890.123 AS double)", "DOUBLE '1234567890.123'");
        assertOptimizedEquals("CAST(00000000000000000000.000 AS double)", "DOUBLE '0.0'");
    }

    @Test
    public void testCastToDecimal()
    {
        // long
        assertOptimizedEquals("CAST(0 AS decimal(1,0))", "DECIMAL '0'");
        assertOptimizedEquals("CAST(123 AS decimal(3,0))", "DECIMAL '123'");
        assertOptimizedEquals("CAST(-123 AS decimal(3,0))", "DECIMAL '-123'");
        assertOptimizedEquals("CAST(-123 AS decimal(20,10))", "CAST(-123 AS decimal(20,10))");

        // double
        assertOptimizedEquals("CAST(0E0 AS decimal(1,0))", "DECIMAL '0'");
        assertOptimizedEquals("CAST(123.2E0 AS decimal(4,1))", "DECIMAL '123.2'");
        assertOptimizedEquals("CAST(-123.0E0 AS decimal(3,0))", "DECIMAL '-123'");
        assertOptimizedEquals("CAST(-123.55E0 AS decimal(20,10))", "CAST(-123.55 AS decimal(20,10))");

        // string
        assertOptimizedEquals("CAST('0' AS decimal(1,0))", "DECIMAL '0'");
        assertOptimizedEquals("CAST('123.2' AS decimal(4,1))", "DECIMAL '123.2'");
        assertOptimizedEquals("CAST('-123.0' AS decimal(3,0))", "DECIMAL '-123'");
        assertOptimizedEquals("CAST('-123.55' AS decimal(20,10))", "CAST(-123.55 AS decimal(20,10))");

        // NULL
        assertOptimizedEquals("CAST(NULL AS decimal(1,0))", "NULL");
        assertOptimizedEquals("CAST(NULL AS decimal(20,10))", "NULL");

        // boolean
        assertOptimizedEquals("CAST(true AS decimal(1,0))", "DECIMAL '1'");
        assertOptimizedEquals("CAST(false AS decimal(4,1))", "DECIMAL '000.0'");
        assertOptimizedEquals("CAST(true AS decimal(3,0))", "DECIMAL '001'");
        assertOptimizedEquals("CAST(false AS decimal(20,10))", "CAST(0 AS decimal(20,10))");

        // decimal
        assertOptimizedEquals("CAST(0.0 AS decimal(1,0))", "DECIMAL '0'");
        assertOptimizedEquals("CAST(123.2 AS decimal(4,1))", "DECIMAL '123.2'");
        assertOptimizedEquals("CAST(-123.0 AS decimal(3,0))", "DECIMAL '-123'");
        assertOptimizedEquals("CAST(-123.55 AS decimal(20,10))", "CAST(-123.55 AS decimal(20,10))");
    }

    @Test
    public void testCastOptimization()
    {
        assertOptimizedEquals("CAST(bound_integer AS varchar)", "'1234'");
        assertOptimizedEquals("CAST(bound_long AS varchar)", "'1234'");
        assertOptimizedEquals("CAST(bound_integer + 1 AS varchar)", "'1235'");
        assertOptimizedEquals("CAST(bound_long + 1 AS varchar)", "'1235'");
        assertOptimizedEquals("CAST(unbound_string AS varchar)", "CAST(unbound_string AS varchar)");
        assertOptimizedMatches("CAST(unbound_string AS varchar)", "unbound_string");
        assertOptimizedMatches("CAST(unbound_integer AS integer)", "unbound_integer");
        assertOptimizedMatches("CAST(unbound_string AS varchar(10))", "CAST(unbound_string AS varchar(10))");
    }

    @Test
    public void testTryCast()
    {
        assertOptimizedEquals("TRY_CAST(NULL AS bigint)", "NULL");
        assertOptimizedEquals("TRY_CAST(123 AS bigint)", "123");
        assertOptimizedEquals("TRY_CAST(NULL AS integer)", "NULL");
        assertOptimizedEquals("TRY_CAST(123 AS integer)", "123");
        assertOptimizedEquals("TRY_CAST('foo' AS varchar)", "'foo'");
        assertOptimizedEquals("TRY_CAST('foo' AS bigint)", "NULL");
        assertOptimizedEquals("TRY_CAST(unbound_string AS bigint)", "TRY_CAST(unbound_string AS bigint)");
        assertOptimizedEquals("TRY_CAST('foo' AS decimal(2,1))", "NULL");
    }

    @Test
    public void testReservedWithDoubleQuotes()
    {
        assertOptimizedEquals("\"time\"", "\"time\"");
    }

    @Test
    public void testSearchCase()
    {
        assertOptimizedEquals("CASE " +
                        "WHEN true THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 1 " +
                        "ELSE 33 " +
                        "END",
                "33");

        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 10000000000 " +
                        "ELSE 33 " +
                        "END",
                "33");

        assertOptimizedEquals("CASE " +
                        "WHEN bound_long = 1234 THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE " +
                        "WHEN true THEN bound_long " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 1 " +
                        "ELSE bound_long " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE " +
                        "WHEN bound_integer = 1234 THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE " +
                        "WHEN true THEN bound_integer " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 1 " +
                        "ELSE bound_integer " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE " +
                        "WHEN bound_long = 1234 THEN 33 " +
                        "ELSE unbound_long " +
                        "END",
                "33");
        assertOptimizedEquals("CASE " +
                        "WHEN true THEN bound_long " +
                        "ELSE unbound_long " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE " +
                        "WHEN false THEN unbound_long " +
                        "ELSE bound_long " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE " +
                        "WHEN bound_integer = 1234 THEN 33 " +
                        "ELSE unbound_integer " +
                        "END",
                "33");
        assertOptimizedEquals("CASE " +
                        "WHEN true THEN bound_integer " +
                        "ELSE unbound_integer " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE " +
                        "WHEN false THEN unbound_integer " +
                        "ELSE bound_integer " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE " +
                        "WHEN unbound_long = 1234 THEN 33 " +
                        "ELSE 1 " +
                        "END",
                "" +
                        "CASE " +
                        "WHEN unbound_long = 1234 THEN 33 " +
                        "ELSE 1 " +
                        "END");

        assertOptimizedMatches("CASE WHEN 0 / 0 = 0 THEN 1 END",
                "CASE WHEN CAST(fail('fail') AS boolean) THEN 1 END");

        assertOptimizedMatches("IF(false, 1, 0 / 0)", "CAST(fail('fail') AS integer)");

        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 2.2 " +
                        "WHEN true THEN 2.2 " +
                        "END",
                "2.2");

        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 1234567890.0987654321 " +
                        "WHEN true THEN 3.3 " +
                        "END",
                "CAST(3.3 AS decimal(20,10))");

        assertOptimizedEquals("CASE " +
                        "WHEN false THEN 1 " +
                        "WHEN true THEN 2.2 " +
                        "END",
                "2.2");

        assertOptimizedEquals("CASE WHEN ARRAY[CAST(1 AS bigint)] = ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'matched'");
        assertOptimizedEquals("CASE WHEN ARRAY[CAST(2 AS bigint)] = ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'not_matched'");
        assertOptimizedEquals("CASE WHEN ARRAY[CAST(NULL AS bigint)] = ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'not_matched'");
    }

    @Test
    public void testSimpleCase()
    {
        assertOptimizedEquals("CASE 1 " +
                        "WHEN 1 THEN 32 + 1 " +
                        "WHEN 1 THEN 34 " +
                        "END",
                "33");

        assertOptimizedEquals("CASE NULL " +
                        "WHEN true THEN 33 " +
                        "END",
                "NULL");
        assertOptimizedEquals("CASE NULL " +
                        "WHEN true THEN 33 " +
                        "ELSE 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN NULL THEN 1 " +
                        "ELSE 33 " +
                        "END",
                "33");

        assertOptimizedEquals("CASE NULL " +
                        "WHEN true THEN 3300000000 " +
                        "END",
                "NULL");
        assertOptimizedEquals("CASE NULL " +
                        "WHEN true THEN 3300000000 " +
                        "ELSE 3300000000 " +
                        "END",
                "3300000000");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN NULL THEN 3300000000 " +
                        "ELSE 33 " +
                        "END",
                "33");

        assertOptimizedEquals("CASE true " +
                        "WHEN true THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN 1 " +
                        "ELSE 33 END",
                "33");

        assertOptimizedEquals("CASE bound_long " +
                        "WHEN 1234 THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE 1234 " +
                        "WHEN bound_long THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE true " +
                        "WHEN true THEN bound_long " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN 1 " +
                        "ELSE bound_long " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE bound_integer " +
                        "WHEN 1234 THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE 1234 " +
                        "WHEN bound_integer THEN 33 " +
                        "END",
                "33");
        assertOptimizedEquals("CASE true " +
                        "WHEN true THEN bound_integer " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN 1 " +
                        "ELSE bound_integer " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE bound_long " +
                        "WHEN 1234 THEN 33 " +
                        "ELSE unbound_long " +
                        "END",
                "33");
        assertOptimizedEquals("CASE true " +
                        "WHEN true THEN bound_long " +
                        "ELSE unbound_long " +
                        "END",
                "1234");
        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN unbound_long " +
                        "ELSE bound_long " +
                        "END",
                "1234");

        assertOptimizedEquals("CASE unbound_long " +
                        "WHEN 1234 THEN 33 " +
                        "ELSE 1 " +
                        "END",
                "" +
                        "CASE unbound_long " +
                        "WHEN 1234 THEN 33 " +
                        "ELSE 1 " +
                        "END");

        assertOptimizedEquals("CASE 33 " +
                        "WHEN 0 THEN 0 " +
                        "WHEN 33 THEN unbound_long " +
                        "ELSE 1 " +
                        "END",
                "unbound_long");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN 0 THEN 0 " +
                        "WHEN 33 THEN 1 " +
                        "WHEN unbound_long THEN 2 " +
                        "ELSE 1 " +
                        "END",
                "1");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN unbound_long THEN 0 " +
                        "WHEN 1 THEN 1 " +
                        "WHEN 33 THEN 2 " +
                        "ELSE 0 " +
                        "END",
                "CASE 33 " +
                        "WHEN unbound_long THEN 0 " +
                        "ELSE 2 " +
                        "END");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN 0 THEN 0 " +
                        "WHEN 1 THEN 1 " +
                        "ELSE unbound_long " +
                        "END",
                "unbound_long");
        assertOptimizedEquals("CASE 33 " +
                        "WHEN unbound_long THEN 0 " +
                        "WHEN 1 THEN 1 " +
                        "WHEN unbound_long2 THEN 2 " +
                        "ELSE 3 " +
                        "END",
                "CASE 33 " +
                        "WHEN unbound_long THEN 0 " +
                        "WHEN unbound_long2 THEN 2 " +
                        "ELSE 3 " +
                        "END");

        assertOptimizedEquals("CASE true " +
                        "WHEN unbound_long = 1 THEN 1 " +
                        "WHEN 0 / 0 = 0 THEN 2 " +
                        "ELSE 33 END",
                "" +
                        "CASE true " +
                        "WHEN unbound_long = 1 THEN 1 " +
                        "WHEN 0 / 0 = 0 THEN 2 ELSE 33 " +
                        "END");

        assertOptimizedEquals("CASE bound_long " +
                        "WHEN unbound_long + 123 * 10  THEN 1 = 1 " +
                        "ELSE 1 = 2 " +
                        "END",
                "" +
                        "CASE bound_long WHEN unbound_long + 1230 THEN true " +
                        "ELSE false " +
                        "END");

        assertOptimizedEquals("CASE bound_long " +
                        "WHEN unbound_long THEN 2 + 2 " +
                        "END",
                "" +
                        "CASE bound_long " +
                        "WHEN unbound_long THEN 4 " +
                        "END");

        assertOptimizedEquals("CASE bound_long " +
                        "WHEN unbound_long THEN 2 + 2 " +
                        "WHEN 1 THEN NULL " +
                        "WHEN 2 THEN NULL " +
                        "END",
                "" +
                        "CASE bound_long " +
                        "WHEN unbound_long THEN 4 " +
                        "END");

        assertOptimizedMatches("CASE 1 " +
                        "WHEN unbound_long THEN 1 " +
                        "WHEN 0 / 0 THEN 2 " +
                        "ELSE 1 " +
                        "END",
                "" +
                        "CASE BIGINT '1' " +
                        "WHEN unbound_long THEN 1 " +
                        "WHEN CAST(fail('fail') AS integer) THEN 2 " +
                        "ELSE 1 " +
                        "END");

        assertOptimizedMatches("CASE 1 " +
                        "WHEN 0 / 0 THEN 1 " +
                        "WHEN 0 / 0 THEN 2 " +
                        "ELSE 1 " +
                        "END",
                "" +
                        "CASE 1 " +
                        "WHEN CAST(fail('fail') AS integer) THEN 1 " +
                        "WHEN CAST(fail('fail') AS integer) THEN 2 " +
                        "ELSE 1 " +
                        "END");

        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN 2.2 " +
                        "WHEN true THEN 2.2 " +
                        "END",
                "2.2");

        // TODO enabled WHEN DECIMAL is default for literal:
//        assertOptimizedEquals("CASE true " +
//                        "WHEN false THEN 1234567890.0987654321 " +
//                        "WHEN true THEN 3.3 " +
//                        "END",
//                "CAST(3.3 AS decimal(20,10))");

        assertOptimizedEquals("CASE true " +
                        "WHEN false THEN 1 " +
                        "WHEN true THEN 2.2 " +
                        "END",
                "2.2");

        assertOptimizedEquals("CASE ARRAY[CAST(1 AS bigint)] WHEN ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'matched'");
        assertOptimizedEquals("CASE ARRAY[CAST(2 AS bigint)] WHEN ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'not_matched'");
        assertOptimizedEquals("CASE ARRAY[CAST(NULL AS bigint)] WHEN ARRAY[CAST(1 AS bigint)] THEN 'matched' ELSE 'not_matched' END", "'not_matched'");
    }

    @Test
    public void testCoalesce()
    {
        assertOptimizedEquals("coalesce(unbound_long * (2 * 3), 1 - 1, NULL)", "coalesce(unbound_long * 6, 0)");
        assertOptimizedEquals("coalesce(unbound_long * (2 * 3), 1.0E0/2.0E0, NULL)", "coalesce(unbound_long * 6, 0.5E0)");
        assertOptimizedEquals("coalesce(unbound_long, 2, 1.0E0/2.0E0, 12.34E0, NULL)", "coalesce(unbound_long, 2.0E0, 0.5E0, 12.34E0)");
        assertOptimizedEquals("coalesce(unbound_integer * (2 * 3), 1 - 1, NULL)", "coalesce(6 * unbound_integer, 0)");
        assertOptimizedEquals("coalesce(unbound_integer * (2 * 3), 1.0E0/2.0E0, NULL)", "coalesce(6 * unbound_integer, 0.5E0)");
        assertOptimizedEquals("coalesce(unbound_integer, 2, 1.0E0/2.0E0, 12.34E0, NULL)", "coalesce(unbound_integer, 2.0E0, 0.5E0, 12.34E0)");
        assertOptimizedMatches("coalesce(0 / 0 > 1, unbound_boolean, 0 / 0 = 0)",
                "coalesce(CAST(fail('fail') AS boolean), unbound_boolean)");
        assertOptimizedMatches("coalesce(unbound_long, unbound_long)", "unbound_long");
        assertOptimizedMatches("coalesce(2 * unbound_long, 2 * unbound_long)", "unbound_long * BIGINT '2'");
        assertOptimizedMatches("coalesce(unbound_long, unbound_long2, unbound_long)", "coalesce(unbound_long, unbound_long2)");
        assertOptimizedMatches("coalesce(unbound_long, unbound_long2, unbound_long, unbound_long3)", "coalesce(unbound_long, unbound_long2, unbound_long3)");
        assertOptimizedEquals("coalesce(6, unbound_long2, unbound_long, unbound_long3)", "6");
        assertOptimizedEquals("coalesce(2 * 3, unbound_long2, unbound_long, unbound_long3)", "6");
        assertOptimizedMatches("coalesce(random(), random(), 5)", "coalesce(random(), random(), 5E0)");
        assertOptimizedMatches("coalesce(unbound_long, coalesce(unbound_long, 1))", "coalesce(unbound_long, BIGINT '1')");
        assertOptimizedMatches("coalesce(coalesce(unbound_long, coalesce(unbound_long, 1)), unbound_long2)", "coalesce(unbound_long, BIGINT '1')");
        assertOptimizedMatches("coalesce(unbound_long, 2, coalesce(unbound_long, 1))", "coalesce(unbound_long, BIGINT '2')");
        assertOptimizedMatches("coalesce(coalesce(unbound_long, coalesce(unbound_long2, unbound_long3)), 1)", "coalesce(unbound_long, unbound_long2, unbound_long3, BIGINT '1')");
        assertOptimizedMatches("coalesce(unbound_double, coalesce(random(), unbound_double))", "coalesce(unbound_double, random())");
    }

    @Test
    public void testIf()
    {
        assertOptimizedEquals("IF(2 = 2, 3, 4)", "3");
        assertOptimizedEquals("IF(1 = 2, 3, 4)", "4");
        assertOptimizedEquals("IF(1 = 2, BIGINT '3', 4)", "4");
        assertOptimizedEquals("IF(1 = 2, 3000000000, 4)", "4");

        assertOptimizedEquals("IF(true, 3, 4)", "3");
        assertOptimizedEquals("IF(false, 3, 4)", "4");
        assertOptimizedEquals("IF(NULL, 3, 4)", "4");

        assertOptimizedEquals("IF(true, 3, NULL)", "3");
        assertOptimizedEquals("IF(false, 3, NULL)", "NULL");
        assertOptimizedEquals("IF(true, NULL, 4)", "NULL");
        assertOptimizedEquals("IF(false, NULL, 4)", "4");
        assertOptimizedEquals("IF(true, NULL, NULL)", "NULL");
        assertOptimizedEquals("IF(false, NULL, NULL)", "NULL");

        assertOptimizedEquals("IF(true, 3.5E0, 4.2E0)", "3.5E0");
        assertOptimizedEquals("IF(false, 3.5E0, 4.2E0)", "4.2E0");

        assertOptimizedEquals("IF(true, 'foo', 'bar')", "'foo'");
        assertOptimizedEquals("IF(false, 'foo', 'bar')", "'bar'");

        assertOptimizedEquals("IF(true, 1.01, 1.02)", "1.01");
        assertOptimizedEquals("IF(false, 1.01, 1.02)", "1.02");
        assertOptimizedEquals("IF(true, 1234567890.123, 1.02)", "1234567890.123");
        assertOptimizedEquals("IF(false, 1.01, 1234567890.123)", "1234567890.123");

        // todo optimize case statement
        assertOptimizedEquals("IF(unbound_boolean, 1 + 2, 3 + 4)", "CASE WHEN unbound_boolean THEN (1 + 2) ELSE (3 + 4) END");
        assertOptimizedEquals("IF(unbound_boolean, BIGINT '1' + 2, 3 + 4)", "CASE WHEN unbound_boolean THEN (BIGINT '1' + 2) ELSE (3 + 4) END");
    }

    @Test
    public void testLike()
    {
        assertOptimizedEquals("'a' LIKE 'a'", "true");
        assertOptimizedEquals("'' LIKE 'a'", "false");
        assertOptimizedEquals("'abc' LIKE 'a'", "false");

        assertOptimizedEquals("'a' LIKE '_'", "true");
        assertOptimizedEquals("'' LIKE '_'", "false");
        assertOptimizedEquals("'abc' LIKE '_'", "false");

        assertOptimizedEquals("'a' LIKE '%'", "true");
        assertOptimizedEquals("'' LIKE '%'", "true");
        assertOptimizedEquals("'abc' LIKE '%'", "true");

        assertOptimizedEquals("'abc' LIKE '___'", "true");
        assertOptimizedEquals("'ab' LIKE '___'", "false");
        assertOptimizedEquals("'abcd' LIKE '___'", "false");

        assertOptimizedEquals("'abc' LIKE 'abc'", "true");
        assertOptimizedEquals("'xyz' LIKE 'abc'", "false");
        assertOptimizedEquals("'abc0' LIKE 'abc'", "false");
        assertOptimizedEquals("'0abc' LIKE 'abc'", "false");

        assertOptimizedEquals("'abc' LIKE 'abc%'", "true");
        assertOptimizedEquals("'abc0' LIKE 'abc%'", "true");
        assertOptimizedEquals("'0abc' LIKE 'abc%'", "false");

        assertOptimizedEquals("'abc' LIKE '%abc'", "true");
        assertOptimizedEquals("'0abc' LIKE '%abc'", "true");
        assertOptimizedEquals("'abc0' LIKE '%abc'", "false");

        assertOptimizedEquals("'abc' LIKE '%abc%'", "true");
        assertOptimizedEquals("'0abc' LIKE '%abc%'", "true");
        assertOptimizedEquals("'abc0' LIKE '%abc%'", "true");
        assertOptimizedEquals("'0abc0' LIKE '%abc%'", "true");
        assertOptimizedEquals("'xyzw' LIKE '%abc%'", "false");

        assertOptimizedEquals("'abc' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'0abc' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'abc0' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'0abc0' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'ab01c' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'0ab01c' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'ab01c0' LIKE '%ab%c%'", "true");
        assertOptimizedEquals("'0ab01c0' LIKE '%ab%c%'", "true");

        assertOptimizedEquals("'xyzw' LIKE '%ab%c%'", "false");

        // ensure regex chars are escaped
        assertOptimizedEquals("'\' LIKE '\'", "true");
        assertOptimizedEquals("'.*' LIKE '.*'", "true");
        assertOptimizedEquals("'[' LIKE '['", "true");
        assertOptimizedEquals("']' LIKE ']'", "true");
        assertOptimizedEquals("'{' LIKE '{'", "true");
        assertOptimizedEquals("'}' LIKE '}'", "true");
        assertOptimizedEquals("'?' LIKE '?'", "true");
        assertOptimizedEquals("'+' LIKE '+'", "true");
        assertOptimizedEquals("'(' LIKE '('", "true");
        assertOptimizedEquals("')' LIKE ')'", "true");
        assertOptimizedEquals("'|' LIKE '|'", "true");
        assertOptimizedEquals("'^' LIKE '^'", "true");
        assertOptimizedEquals("'$' LIKE '$'", "true");

        assertOptimizedEquals("NULL LIKE '%'", "NULL");
        assertOptimizedEquals("'a' LIKE NULL", "NULL");
        assertOptimizedEquals("'a' LIKE '%' ESCAPE NULL", "NULL");

        assertOptimizedEquals("'%' LIKE 'z%' ESCAPE 'z'", "true");
    }

    @Test
    public void testLikeOptimization()
    {
        assertOptimizedEquals("unbound_string LIKE 'abc'", "unbound_string = CAST('abc' AS varchar)");

        assertOptimizedEquals("unbound_string LIKE '' ESCAPE '#'", "unbound_string LIKE '' ESCAPE '#'");
        assertOptimizedEquals("unbound_string LIKE 'abc' ESCAPE '#'", "unbound_string = CAST('abc' AS varchar)");
        assertOptimizedEquals("unbound_string LIKE 'a#_b' ESCAPE '#'", "unbound_string = CAST('a_b' AS varchar)");
        assertOptimizedEquals("unbound_string LIKE 'a#%b' ESCAPE '#'", "unbound_string = CAST('a%b' AS varchar)");
        assertOptimizedEquals("unbound_string LIKE 'a#_##b' ESCAPE '#'", "unbound_string = CAST('a_#b' AS varchar)");
        assertOptimizedEquals("unbound_string LIKE 'a#__b' ESCAPE '#'", "unbound_string LIKE 'a#__b' ESCAPE '#'");
        assertOptimizedEquals("unbound_string LIKE 'a##%b' ESCAPE '#'", "unbound_string LIKE 'a##%b' ESCAPE '#'");

        assertOptimizedEquals("bound_string LIKE bound_pattern", "true");
        assertOptimizedEquals("'abc' LIKE bound_pattern", "false");

        assertOptimizedEquals("unbound_string LIKE bound_pattern", "unbound_string LIKE bound_pattern");

        assertOptimizedEquals("unbound_string LIKE unbound_pattern ESCAPE unbound_string", "unbound_string LIKE unbound_pattern ESCAPE unbound_string");
    }

    @Test
    public void testInvalidLike()
    {
        assertThrows(PrestoException.class, () -> optimize("unbound_string LIKE 'abc' ESCAPE ''"));
        assertThrows(PrestoException.class, () -> optimize("unbound_string LIKE 'abc' ESCAPE 'bc'"));
        assertThrows(PrestoException.class, () -> optimize("unbound_string LIKE '#' ESCAPE '#'"));
        assertThrows(PrestoException.class, () -> optimize("unbound_string LIKE '#abc' ESCAPE '#'"));
        assertThrows(PrestoException.class, () -> optimize("unbound_string LIKE 'ab#' ESCAPE '#'"));
    }

    @Test
    public void testFailedExpressionOptimization()
    {
        assertOptimizedEquals("IF(unbound_boolean, 1, 0 / 0)", "CASE WHEN unbound_boolean THEN 1 ELSE 0 / 0 END");
        assertOptimizedEquals("IF(unbound_boolean, 0 / 0, 1)", "CASE WHEN unbound_boolean THEN 0 / 0 ELSE 1 END");

        assertOptimizedMatches("CASE unbound_long WHEN 1 THEN 1 WHEN 0 / 0 THEN 2 END",
                "CASE unbound_long WHEN BIGINT '1' THEN 1 WHEN CAST(fail('fail') AS bigint) THEN 2 END");

        assertOptimizedMatches("CASE unbound_boolean WHEN true THEN 1 ELSE 0 / 0 END",
                "CASE unbound_boolean WHEN true THEN 1 ELSE CAST(fail('fail') AS integer) END");

        assertOptimizedMatches("CASE bound_long WHEN unbound_long THEN 1 WHEN 0 / 0 THEN 2 ELSE 1 END",
                "CASE BIGINT '1234' WHEN unbound_long THEN 1 WHEN CAST(fail('fail') AS bigint) THEN 2 ELSE 1 END");

        assertOptimizedMatches("CASE WHEN unbound_boolean THEN 1 WHEN 0 / 0 = 0 THEN 2 END",
                "CASE WHEN unbound_boolean THEN 1 WHEN CAST(fail('fail') AS boolean) THEN 2 END");

        assertOptimizedMatches("CASE WHEN unbound_boolean THEN 1 ELSE 0 / 0  END",
                "CASE WHEN unbound_boolean THEN 1 ELSE CAST(fail('fail') AS integer) END");

        assertOptimizedMatches("CASE WHEN unbound_boolean THEN 0 / 0 ELSE 1 END",
                "CASE WHEN unbound_boolean THEN CAST(fail('fail') AS integer) ELSE 1 END");
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testOptimizeDivideByZero()
    {
        optimize("0 / 0");
    }

    @Test
    public void testMassiveArrayConstructor()
    {
        optimize(format("ARRAY[%s]", Joiner.on(", ").join(IntStream.range(0, 10_000).mapToObj(i -> "(bound_long + " + i + ")").iterator())));
        optimize(format("ARRAY[%s]", Joiner.on(", ").join(IntStream.range(0, 10_000).mapToObj(i -> "(bound_integer + " + i + ")").iterator())));
        optimize(format("ARRAY[%s]", Joiner.on(", ").join(IntStream.range(0, 10_000).mapToObj(i -> "'" + i + "'").iterator())));
        optimize(format("ARRAY[%s]", Joiner.on(", ").join(IntStream.range(0, 10_000).mapToObj(i -> "ARRAY['" + i + "']").iterator())));
    }

    @Test
    public void testArrayConstructor()
    {
        optimize("ARRAY[]");
        assertOptimizedEquals("ARRAY[(unbound_long + 0), (unbound_long + 1), (unbound_long + 2)]",
                "array_constructor((unbound_long + 0), (unbound_long + 1), (unbound_long + 2))");
        assertOptimizedEquals("ARRAY[(bound_long + 0), (unbound_long + 1), (bound_long + 2)]",
                "array_constructor((bound_long + 0), (unbound_long + 1), (bound_long + 2))");
        assertOptimizedEquals("ARRAY[(bound_long + 0), (unbound_long + 1), NULL]",
                "array_constructor((bound_long + 0), (unbound_long + 1), NULL)");
    }

    @Test
    public void testRowConstructor()
    {
        optimize("ROW(NULL)");
        optimize("ROW(1)");
        optimize("ROW(unbound_long + 0)");
        optimize("ROW(unbound_long + unbound_long2, unbound_string, unbound_double)");
        optimize("ROW(unbound_boolean, FALSE, ARRAY[unbound_long, unbound_long2], unbound_null_string, unbound_interval)");
        optimize("ARRAY[ROW(unbound_string, unbound_double), ROW(unbound_string, 0.0E0)]");
        optimize("ARRAY[ROW('string', unbound_double), ROW('string', bound_double)]");
        optimize("ROW(ROW(NULL), ROW(ROW(ROW(ROW('rowception')))))");
        optimize("ROW(unbound_string, bound_string)");

        optimize("ARRAY[ROW(unbound_string, unbound_double), ROW(CAST(bound_string AS varchar), 0.0E0)]");
        optimize("ARRAY[ROW(CAST(bound_string AS varchar), 0.0E0), ROW(unbound_string, unbound_double)]");

        optimize("ARRAY[ROW(unbound_string, unbound_double), CAST(NULL AS row(varchar, double))]");
        optimize("ARRAY[CAST(NULL AS row(varchar, double)), ROW(unbound_string, unbound_double)]");
    }

    @Test
    public void testRowSubscript()
    {
        assertOptimizedEquals("ROW(1, 'a', true)[3]", "true");
        assertOptimizedEquals("ROW(1, 'a', ROW(2, 'b', ROW(3, 'c')))[3][3][2]", "'c'");
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testArraySubscriptConstantNegativeIndex()
    {
        optimize("ARRAY[1, 2, 3][-1]");
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testArraySubscriptConstantZeroIndex()
    {
        optimize("ARRAY[1, 2, 3][0]");
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testMapSubscriptMissingKey()
    {
        optimize("MAP(ARRAY[1, 2], ARRAY[3, 4])[-1]");
    }

    @Test
    public void testMapSubscriptConstantIndexes()
    {
        optimize("MAP(ARRAY[1, 2], ARRAY[3, 4])[1]");
        optimize("MAP(ARRAY[BIGINT '1', 2], ARRAY[3, 4])[1]");
        optimize("MAP(ARRAY[1, 2], ARRAY[3, 4])[2]");
        optimize("MAP(ARRAY[ARRAY[1,1]], ARRAY['a'])[ARRAY[1,1]]");
    }

    @Test(timeOut = 60000)
    public void testLikeInvalidUtf8()
    {
        assertLike(new byte[] {'a', 'b', 'c'}, "%b%", true);
        assertLike(new byte[] {'a', 'b', 'c', (byte) 0xFF, 'x', 'y'}, "%b%", true);
    }

    @Test
    public void testLiterals()
    {
        optimize("DATE '2013-04-03' + unbound_interval");
        optimize("TIME '03:04:05.321' + unbound_interval");
        optimize("TIME '03:04:05.321 UTC' + unbound_interval");
        optimize("TIMESTAMP '2013-04-03 03:04:05.321' + unbound_interval");
        optimize("TIMESTAMP '2013-04-03 03:04:05.321 UTC' + unbound_interval");

        optimize("INTERVAL '3' DAY * unbound_long");
        optimize("INTERVAL '3' YEAR * unbound_long");

        assertEquals(optimize("X'1234'"), Slices.wrappedBuffer((byte) 0x12, (byte) 0x34));
    }

    private static void assertLike(byte[] value, String pattern, boolean expected)
    {
        Expression predicate = new LikePredicate(
                rawStringLiteral(Slices.wrappedBuffer(value)),
                new StringLiteral(pattern),
                Optional.empty());
        assertEquals(evaluate(predicate), expected);
    }

    private static StringLiteral rawStringLiteral(final Slice slice)
    {
        return new StringLiteral(slice.toStringUtf8())
        {
            @Override
            public Slice getSlice()
            {
                return slice;
            }
        };
    }

    private static void assertOptimizedEquals(@Language("SQL") String actual, @Language("SQL") String expected)
    {
        assertEquals(optimize(actual), optimize(expected));
    }

    private static void assertOptimizedMatches(@Language("SQL") String actual, @Language("SQL") String expected)
    {
        // replaces FunctionCalls to FailureFunction by fail()
        Object actualOptimized = optimize(actual);
        if (actualOptimized instanceof Expression) {
            actualOptimized = ExpressionTreeRewriter.rewriteWith(new FailedFunctionRewriter(), (Expression) actualOptimized);
        }
        assertEquals(
                actualOptimized,
                rewriteIdentifiersToSymbolReferences(SQL_PARSER.createExpression(expected)));
    }

    private static Object optimize(@Language("SQL") String expression)
    {
        assertRoundTrip(expression);

        Expression parsedExpression = FunctionAssertions.createExpression(expression, METADATA, SYMBOL_TYPES);

        Map<NodeRef<Expression>, Type> expressionTypes = TYPE_ANALYZER.getTypes(TEST_SESSION, SYMBOL_TYPES, parsedExpression);
        ExpressionInterpreter interpreter = expressionOptimizer(parsedExpression, METADATA, TEST_SESSION, expressionTypes);
        return interpreter.optimize(symbol -> {
            switch (symbol.getName().toLowerCase(ENGLISH)) {
                case "bound_integer":
                    return 1234L;
                case "bound_long":
                    return 1234L;
                case "bound_string":
                    return utf8Slice("hello");
                case "bound_double":
                    return 12.34;
                case "bound_date":
                    return new LocalDate(2001, 8, 22).toDateMidnight(DateTimeZone.UTC).getMillis();
                case "bound_time":
                    return new LocalTime(3, 4, 5, 321).toDateTime(new DateTime(0, DateTimeZone.UTC)).getMillis();
                case "bound_timestamp":
                    return new DateTime(2001, 8, 22, 3, 4, 5, 321, DateTimeZone.UTC).getMillis();
                case "bound_pattern":
                    return utf8Slice("%el%");
                case "bound_timestamp_with_timezone":
                    return new SqlTimestampWithTimeZone(new DateTime(1970, 1, 1, 1, 0, 0, 999, DateTimeZone.UTC).getMillis(), getTimeZoneKey("Z"));
                case "bound_varbinary":
                    return Slices.wrappedBuffer((byte) 0xab);
                case "bound_decimal_short":
                    return 12345L;
                case "bound_decimal_long":
                    return Decimals.encodeUnscaledValue(new BigInteger("12345678901234567890123"));
            }

            return symbol.toSymbolReference();
        });
    }

    private static void assertEvaluatedEquals(@Language("SQL") String actual, @Language("SQL") String expected)
    {
        assertEquals(evaluate(actual), evaluate(expected));
    }

    private static Object evaluate(String expression)
    {
        assertRoundTrip(expression);

        Expression parsedExpression = FunctionAssertions.createExpression(expression, METADATA, SYMBOL_TYPES);

        return evaluate(parsedExpression);
    }

    private static void assertRoundTrip(String expression)
    {
        ParsingOptions parsingOptions = createParsingOptions(TEST_SESSION);
        Expression parsed = SQL_PARSER.createExpression(expression, parsingOptions);
        String formatted = formatExpression(parsed);
        assertEquals(parsed, SQL_PARSER.createExpression(formatted, parsingOptions));
    }

    private static Object evaluate(Expression expression)
    {
        Map<NodeRef<Expression>, Type> expressionTypes = TYPE_ANALYZER.getTypes(TEST_SESSION, SYMBOL_TYPES, expression);
        ExpressionInterpreter interpreter = expressionInterpreter(expression, METADATA, TEST_SESSION, expressionTypes);

        return interpreter.evaluate();
    }

    private static class FailedFunctionRewriter
            extends ExpressionRewriter<Object>
    {
        @Override
        public Expression rewriteFunctionCall(FunctionCall node, Object context, ExpressionTreeRewriter<Object> treeRewriter)
        {
            if (node.getName().equals(QualifiedName.of("fail"))) {
                return new FunctionCallBuilder(METADATA)
                        .setName(QualifiedName.of("fail"))
                        .addArgument(VARCHAR, new StringLiteral("fail"))
                        .build();
            }
            return node;
        }
    }
}
