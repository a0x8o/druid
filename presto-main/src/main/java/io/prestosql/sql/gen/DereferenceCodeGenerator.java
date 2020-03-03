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

import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.expression.BytecodeExpression;
import io.airlift.bytecode.instruction.LabelNode;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.relational.ConstantExpression;
import io.prestosql.sql.relational.RowExpression;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantInt;
import static io.prestosql.sql.gen.SqlTypeBytecodeExpression.constantType;

public class DereferenceCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext generator, Type returnType, List<RowExpression> arguments)
    {
        checkArgument(arguments.size() == 2);
        CallSiteBinder callSiteBinder = generator.getCallSiteBinder();

        BytecodeBlock block = new BytecodeBlock().comment("DEREFERENCE").setDescription("DEREFERENCE");
        Variable wasNull = generator.wasNull();
        Variable rowBlock = generator.getScope().createTempVariable(Block.class);
        int index = (int) ((ConstantExpression) arguments.get(1)).getValue();

        // clear the wasNull flag before evaluating the row value
        block.putVariable(wasNull, false);
        block.append(generator.generate(arguments.get(0))).putVariable(rowBlock);

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

        return block;
    }
}
