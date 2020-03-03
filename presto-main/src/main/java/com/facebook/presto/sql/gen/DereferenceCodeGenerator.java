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

import com.facebook.presto.bytecode.BytecodeBlock;
import com.facebook.presto.bytecode.BytecodeNode;
import com.facebook.presto.bytecode.Variable;
import com.facebook.presto.bytecode.control.IfStatement;
import com.facebook.presto.bytecode.expression.BytecodeExpression;
import com.facebook.presto.bytecode.instruction.LabelNode;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.type.Type;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.bytecode.expression.BytecodeExpressions.constantInt;
import static com.facebook.presto.sql.gen.SpecialFormBytecodeGenerator.generateWrite;
import static com.facebook.presto.sql.gen.SqlTypeBytecodeExpression.constantType;
import static com.google.common.base.Preconditions.checkArgument;

public class DereferenceCodeGenerator
        implements SpecialFormBytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(BytecodeGeneratorContext generator, Type returnType, List<RowExpression> arguments, Optional<Variable> outputBlockVariable)
    {
        checkArgument(arguments.size() == 2);
        CallSiteBinder callSiteBinder = generator.getCallSiteBinder();

        BytecodeBlock block = new BytecodeBlock().comment("DEREFERENCE").setDescription("DEREFERENCE");
        Variable wasNull = generator.wasNull();
        Variable rowBlock = generator.getScope().createTempVariable(Block.class);
        int index = ((Number) ((ConstantExpression) arguments.get(1)).getValue()).intValue();

        // clear the wasNull flag before evaluating the row value
        block.putVariable(wasNull, false);
        block.append(generator.generate(arguments.get(0), Optional.empty())).putVariable(rowBlock);

        IfStatement ifRowBlockIsNull = new IfStatement("if row block is null...")
                .condition(wasNull);

        Class<?> javaType = returnType.getJavaType();
        LabelNode end = new LabelNode("end");
        ifRowBlockIsNull.ifTrue()
                .comment("if row block is null, push null to the stack and goto 'end' label (return)")
                .putVariable(wasNull, true)
                .pushJavaDefault(javaType)
                .gotoLabel(end);

        block.append(ifRowBlockIsNull);

        IfStatement ifFieldIsNull = new IfStatement("if row field is null...");
        ifFieldIsNull.condition()
                .comment("call rowBlock.isNull(index)")
                .append(rowBlock)
                .push(index)
                .invokeInterface(Block.class, "isNull", boolean.class, int.class);

        ifFieldIsNull.ifTrue()
                .comment("if the field is null, push null to stack")
                .putVariable(wasNull, true)
                .pushJavaDefault(javaType);

        BytecodeExpression value = constantType(callSiteBinder, returnType).getValue(rowBlock, constantInt(index));

        ifFieldIsNull.ifFalse()
                .comment("otherwise call type.getTYPE(rowBlock, index)")
                .append(value)
                .putVariable(wasNull, false);

        block.append(ifFieldIsNull)
                .visitLabel(end);

        outputBlockVariable.ifPresent(output -> block.append(generateWrite(generator, returnType, output)));
        return block;
    }
}
