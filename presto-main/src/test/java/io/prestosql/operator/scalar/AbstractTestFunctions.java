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
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.Session;
import io.prestosql.metadata.FunctionListBuilder;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.spi.ErrorCodeSupplier;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.DecimalParseResult;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.SqlDecimal;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.analyzer.FeaturesConfig;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.FunctionExtractor.extractFunctions;
import static io.prestosql.metadata.Signature.mangleOperatorName;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.testing.assertions.PrestoExceptionAssert.assertPrestoExceptionThrownBy;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.fail;

public abstract class AbstractTestFunctions
{
    protected final Session session;
    private final FeaturesConfig config;
    protected FunctionAssertions functionAssertions;

    protected AbstractTestFunctions()
    {
        this(TEST_SESSION);
    }

    protected AbstractTestFunctions(Session session)
    {
        this(session, new FeaturesConfig());
    }

    protected AbstractTestFunctions(FeaturesConfig config)
    {
        this(TEST_SESSION, config);
    }

    protected AbstractTestFunctions(Session session, FeaturesConfig config)
    {
        this.session = requireNonNull(session, "session is null");
        this.config = requireNonNull(config, "config is null");
    }

    @BeforeClass
    public final void initTestFunctions()
    {
        functionAssertions = new FunctionAssertions(session, config);
    }

    @AfterClass(alwaysRun = true)
    public final void destroyTestFunctions()
    {
        closeAllRuntimeException(functionAssertions);
        functionAssertions = null;
    }

    protected void assertFunction(String projection, Type expectedType, Object expected)
    {
        functionAssertions.assertFunction(projection, expectedType, expected);
    }

    protected void assertOperator(OperatorType operator, String value, Type expectedType, Object expected)
    {
        functionAssertions.assertFunction(format("\"%s\"(%s)", mangleOperatorName(operator), value), expectedType, expected);
    }

    protected void assertDecimalFunction(String statement, SqlDecimal expectedResult)
    {
        assertFunction(
                statement,
                createDecimalType(expectedResult.getPrecision(), expectedResult.getScale()),
                expectedResult);
    }

    // this is not safe as it catches all RuntimeExceptions
    @Deprecated
    protected void assertInvalidFunction(String projection)
    {
        functionAssertions.assertInvalidFunction(projection);
    }

    protected void assertInvalidFunction(String projection, ErrorCodeSupplier errorCode, String message)
    {
        functionAssertions.assertInvalidFunction(projection, errorCode, message);
    }

    protected void assertInvalidFunction(String projection, String message)
    {
        functionAssertions.assertInvalidFunction(projection, INVALID_FUNCTION_ARGUMENT, message);
    }

    protected void assertInvalidFunction(String projection, ErrorCodeSupplier expectedErrorCode)
    {
        functionAssertions.assertInvalidFunction(projection, expectedErrorCode);
    }

    protected void assertNumericOverflow(String projection, String message)
    {
        functionAssertions.assertNumericOverflow(projection, message);
    }

    protected void assertInvalidCast(String projection)
    {
        functionAssertions.assertInvalidCast(projection);
    }

    protected void assertInvalidCast(String projection, String message)
    {
        functionAssertions.assertInvalidCast(projection, message);
    }

    protected void assertCachedInstanceHasBoundedRetainedSize(String projection)
    {
        functionAssertions.assertCachedInstanceHasBoundedRetainedSize(projection);
    }

    protected void assertNotSupported(String projection, String message)
    {
        assertPrestoExceptionThrownBy(() -> functionAssertions.executeProjectionWithFullEngine(projection))
                .hasErrorCode(NOT_SUPPORTED)
                .hasMessage(message);
    }

    protected void tryEvaluateWithAll(String projection, Type expectedType)
    {
        functionAssertions.tryEvaluateWithAll(projection, expectedType);
    }

    protected void registerScalarFunction(SqlScalarFunction sqlScalarFunction)
    {
        functionAssertions.getMetadata().addFunctions(ImmutableList.of(sqlScalarFunction));
    }

    protected void registerScalar(Class<?> clazz)
    {
        functionAssertions.getMetadata().addFunctions(new FunctionListBuilder()
                .scalars(clazz)
                .getFunctions());
    }

    protected void registerParametricScalar(Class<?> clazz)
    {
        functionAssertions.getMetadata().addFunctions(new FunctionListBuilder()
                .scalar(clazz)
                .getFunctions());
    }

    protected void registerFunctions(Plugin plugin)
    {
        functionAssertions.getMetadata().addFunctions(extractFunctions(plugin.getFunctions()));
    }

    protected void registerTypes(Plugin plugin)
    {
        for (Type type : plugin.getTypes()) {
            functionAssertions.addType(type);
        }
    }

    protected static SqlDecimal decimal(String decimalString)
    {
        DecimalParseResult parseResult = Decimals.parseIncludeLeadingZerosInPrecision(decimalString);
        BigInteger unscaledValue;
        if (parseResult.getType().isShort()) {
            unscaledValue = BigInteger.valueOf((Long) parseResult.getObject());
        }
        else {
            unscaledValue = Decimals.decodeUnscaledValue((Slice) parseResult.getObject());
        }
        return new SqlDecimal(unscaledValue, parseResult.getType().getPrecision(), parseResult.getType().getScale());
    }

    protected static SqlDecimal maxPrecisionDecimal(long value)
    {
        final String maxPrecisionFormat = "%0" + (Decimals.MAX_PRECISION + (value < 0 ? 1 : 0)) + "d";
        return decimal(format(maxPrecisionFormat, value));
    }

    // this help function should only be used when the map contains null value
    // otherwise, use ImmutableMap.of()
    protected static <K, V> Map<K, V> asMap(List<K> keyList, List<V> valueList)
    {
        if (keyList.size() != valueList.size()) {
            fail("keyList should have same size with valueList");
        }
        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < keyList.size(); i++) {
            if (map.put(keyList.get(i), valueList.get(i)) != null) {
                fail("keyList should have same size with valueList");
            }
        }
        return map;
    }
}
