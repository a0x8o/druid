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

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.prestosql.annotation.UsedByGeneratedCode;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.SqlOperator;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.util.JsonUtil.JsonGeneratorWriter;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static io.prestosql.metadata.Signature.withVariadicBound;
import static io.prestosql.operator.scalar.JsonOperators.JSON_FACTORY;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.util.Failures.checkCondition;
import static io.prestosql.util.JsonUtil.JsonGeneratorWriter.createJsonGeneratorWriter;
import static io.prestosql.util.JsonUtil.canCastToJson;
import static io.prestosql.util.JsonUtil.createJsonGenerator;
import static io.prestosql.util.Reflection.methodHandle;

public class RowToJsonCast
        extends SqlOperator
{
    public static final RowToJsonCast ROW_TO_JSON = new RowToJsonCast();
    private static final MethodHandle METHOD_HANDLE = methodHandle(RowToJsonCast.class, "toJson", List.class, ConnectorSession.class, Block.class);

    private RowToJsonCast()
    {
        super(OperatorType.CAST,
                ImmutableList.of(withVariadicBound("T", "row")),
                ImmutableList.of(),
                JSON.getTypeSignature(),
                ImmutableList.of(new TypeSignature("T")));
    }

    @Override
    public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, Metadata metadata)
    {
        checkArgument(arity == 1, "Expected arity to be 1");
        Type type = boundVariables.getTypeVariable("T");
        checkCondition(canCastToJson(type), INVALID_CAST_ARGUMENT, "Cannot cast %s to JSON", type);

        List<Type> fieldTypes = type.getTypeParameters();
        List<JsonGeneratorWriter> fieldWriters = new ArrayList<>(fieldTypes.size());
        for (int i = 0; i < fieldTypes.size(); i++) {
            fieldWriters.add(createJsonGeneratorWriter(fieldTypes.get(i)));
        }
        MethodHandle methodHandle = METHOD_HANDLE.bindTo(fieldWriters);

        return new ScalarFunctionImplementation(
                false,
                ImmutableList.of(valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                methodHandle,
                isDeterministic());
    }

    @UsedByGeneratedCode
    public static Slice toJson(List<JsonGeneratorWriter> fieldWriters, ConnectorSession session, Block block)
    {
        try {
            SliceOutput output = new DynamicSliceOutput(40);
            try (JsonGenerator jsonGenerator = createJsonGenerator(JSON_FACTORY, output)) {
                jsonGenerator.writeStartArray();
                for (int i = 0; i < block.getPositionCount(); i++) {
                    fieldWriters.get(i).writeJsonValue(jsonGenerator, block, i, session);
                }
                jsonGenerator.writeEndArray();
            }
            return output.slice();
        }
        catch (IOException e) {
            throwIfUnchecked(e);
            throw new RuntimeException(e);
        }
    }
}
