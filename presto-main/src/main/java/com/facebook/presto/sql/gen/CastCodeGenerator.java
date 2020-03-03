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
package com.facebook.presto.sql.gen;

import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.relational.RowExpression;
import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Variable;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.spi.function.OperatorType.CAST;
import static com.facebook.presto.sql.gen.BytecodeGenerator.generateWrite;

public class CastCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext generatorContext, Type returnType, List<RowExpression> arguments, Optional<Variable> outputBlockVariable)
    {
        RowExpression argument = arguments.get(0);

        FunctionHandle function = generatorContext
                .getFunctionManager()
                .lookupCast(CAST, argument.getType().getTypeSignature(), returnType.getTypeSignature());

        BytecodeBlock block = new BytecodeBlock()
                .append(generatorContext.generateCall(
                        CAST.name(),
                        generatorContext.getFunctionManager().getScalarFunctionImplementation(function),
                        ImmutableList.of(generatorContext.generate(argument, Optional.empty()))));
        outputBlockVariable.ifPresent(output -> block.append(generateWrite(generatorContext, returnType, output)));
        return block;
    }
}
