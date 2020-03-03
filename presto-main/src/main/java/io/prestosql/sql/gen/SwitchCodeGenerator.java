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
package io.prestosql.sql.gen;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Scope;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.instruction.LabelNode;
import io.airlift.bytecode.instruction.VariableInstruction;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.relational.RowExpression;
import io.prestosql.sql.relational.SpecialForm;

import java.util.List;

import static io.airlift.bytecode.expression.BytecodeExpressions.constantFalse;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantTrue;
import static io.prestosql.sql.relational.SpecialForm.Form.WHEN;

public class SwitchCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext generatorContext, Type returnType, List<RowExpression> arguments)
    {
        // TODO: compile as
        /*
            hashCode = hashCode(<value>)

            // all constant expressions before a non-constant
            switch (hashCode) {
                case ...:
                    if (<value> == <constant1>) {
                       ...
                    }
                    else if (<value> == <constant2>) {
                       ...
                    }
                    else if (...) {
                    }
                case ...:
                    ...
            }

            if (<value> == <non-constant1>) {
                ...
            }
            else if (<value> == <non-constant2>) {
                ...
            }
            ...

            // repeat with next sequence of constant expressions
         */

        Scope scope = generatorContext.getScope();

        // process value, else, and all when clauses
        RowExpression value = arguments.get(0);
        BytecodeNode valueBytecode = generatorContext.generate(value);
        BytecodeNode elseValue;

        List<RowExpression> whenClauses;
        RowExpression last = arguments.get(arguments.size() - 1);
        if (last instanceof SpecialForm && ((SpecialForm) last).getForm() == WHEN) {
            whenClauses = arguments.subList(1, arguments.size());
            elseValue = new BytecodeBlock()
                    .append(generatorContext.wasNull().set(constantTrue()))
                    .pushJavaDefault(returnType.getJavaType());
        }
        else {
            whenClauses = arguments.subList(1, arguments.size() - 1);
            elseValue = generatorContext.generate(last);
        }

        // determine the type of the value and result
        Class<?> valueType = value.getType().getJavaType();

        // evaluate the value and store it in a variable
        LabelNode nullValue = new LabelNode("nullCondition");
        Variable tempVariable = scope.createTempVariable(valueType);
        BytecodeBlock block = new BytecodeBlock()
                .append(valueBytecode)
                .append(BytecodeUtils.ifWasNullClearPopAndGoto(scope, nullValue, void.class, valueType))
                .putVariable(tempVariable);

        BytecodeNode getTempVariableNode = VariableInstruction.loadVariable(tempVariable);

        // build the statements
        elseValue = new BytecodeBlock().visitLabel(nullValue).append(elseValue);
        // reverse list because current if statement builder doesn't support if/else so we need to build the if statements bottom up
        for (RowExpression clause : Lists.reverse(whenClauses)) {
            Preconditions.checkArgument(clause instanceof SpecialForm && ((SpecialForm) clause).getForm() == WHEN);

            RowExpression operand = ((SpecialForm) clause).getArguments().get(0);
            RowExpression result = ((SpecialForm) clause).getArguments().get(1);

            // call equals(value, operand)
            Signature equalsFunction = generatorContext.getMetadata().resolveOperator(OperatorType.EQUAL, ImmutableList.of(value.getType(), operand.getType()));

            // TODO: what if operand is null? It seems that the call will return "null" (which is cleared below)
            // and the code only does the right thing because the value in the stack for that scenario is
            // Java's default for boolean == false
            // This code should probably be checking for wasNull after the call and "failing" the equality
            // check if wasNull is true
            BytecodeNode equalsCall = generatorContext.generateCall(
                    equalsFunction.getName(),
                    generatorContext.getMetadata().getScalarFunctionImplementation(equalsFunction),
                    ImmutableList.of(generatorContext.generate(operand), getTempVariableNode));

            BytecodeBlock condition = new BytecodeBlock()
                    .append(equalsCall)
                    .append(generatorContext.wasNull().set(constantFalse()));

            elseValue = new IfStatement("when")
                    .condition(condition)
                    .ifTrue(generatorContext.generate(result))
                    .ifFalse(elseValue);
        }

        return block.append(elseValue);
    }
}
