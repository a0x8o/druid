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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.scalar.annotations.ScalarFromAnnotationsParser;
import io.prestosql.operator.window.WindowAnnotationsParser;
import io.prestosql.spi.function.WindowFunction;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class FunctionListBuilder
{
    private final List<SqlFunction> functions = new ArrayList<>();

    public FunctionListBuilder window(Class<? extends WindowFunction> clazz)
    {
        functions.addAll(WindowAnnotationsParser.parseFunctionDefinition(clazz));
        return this;
    }

    public FunctionListBuilder aggregate(Class<?> aggregationDefinition)
    {
        functions.addAll(SqlAggregationFunction.createFunctionByAnnotations(aggregationDefinition));
        return this;
    }

    public FunctionListBuilder aggregates(Class<?> aggregationDefinition)
    {
        functions.addAll(SqlAggregationFunction.createFunctionsByAnnotations(aggregationDefinition));
        return this;
    }

    public FunctionListBuilder scalar(Class<?> clazz)
    {
        functions.addAll(ScalarFromAnnotationsParser.parseFunctionDefinition(clazz));
        return this;
    }

    public FunctionListBuilder scalars(Class<?> clazz)
    {
        functions.addAll(ScalarFromAnnotationsParser.parseFunctionDefinitions(clazz));
        return this;
    }

    public FunctionListBuilder functions(SqlFunction... sqlFunctions)
    {
        for (SqlFunction sqlFunction : sqlFunctions) {
            function(sqlFunction);
        }
        return this;
    }

    public FunctionListBuilder function(SqlFunction sqlFunction)
    {
        requireNonNull(sqlFunction, "parametricFunction is null");
        functions.add(sqlFunction);
        return this;
    }

    public List<SqlFunction> getFunctions()
    {
        return ImmutableList.copyOf(functions);
    }
}
