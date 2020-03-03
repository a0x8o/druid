package com.facebook.presto.operator.scalar;
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

import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.function.BlockIndex;
import com.facebook.presto.spi.function.BlockPosition;
import com.facebook.presto.spi.function.Convention;
import com.facebook.presto.spi.function.IsNull;
import com.facebook.presto.spi.function.OperatorDependency;
import com.facebook.presto.spi.function.ScalarOperator;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.function.TypeParameter;
import com.facebook.presto.spi.type.MapType;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;

import java.lang.invoke.MethodHandle;

import static com.facebook.presto.spi.function.InvocationConvention.InvocationArgumentConvention.BLOCK_POSITION;
import static com.facebook.presto.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static com.facebook.presto.spi.function.OperatorType.IS_DISTINCT_FROM;

@ScalarOperator(IS_DISTINCT_FROM)
public final class MapDistinctFromOperator
{
    private MapDistinctFromOperator() {}

    @TypeParameter("K")
    @TypeParameter("V")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean isDistinctFrom(
            @OperatorDependency(operator = IS_DISTINCT_FROM, argumentTypes = {"V", "V"}, convention = @Convention(arguments = {BLOCK_POSITION, BLOCK_POSITION}, result = FAIL_ON_NULL))
                    MethodHandle valueDistinctFromFunction,
            @TypeParameter("map(K, V)") Type mapType,
            @SqlType("map(K,V)") Block leftMapBlock,
            @IsNull boolean leftMapNull,
            @SqlType("map(K,V)") Block rightMapBlock,
            @IsNull boolean rightMapNull)
    {
        if (leftMapNull != rightMapNull) {
            return true;
        }
        if (leftMapNull) {
            return false;
        }
        // Note that we compare to NOT distinct here and so negate the result.
        return !MapGenericEquality.genericEqual(
                ((MapType) mapType).getKeyType(),
                leftMapBlock,
                rightMapBlock,
                (leftMapIndex, rightMapIndex) -> !(boolean) valueDistinctFromFunction.invokeExact(leftMapBlock, leftMapIndex, rightMapBlock, rightMapIndex));
    }

    @TypeParameter("K")
    @TypeParameter("V")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean isDistinctFrom(
            @OperatorDependency(operator = IS_DISTINCT_FROM, argumentTypes = {"V", "V"}, convention = @Convention(arguments = {BLOCK_POSITION, BLOCK_POSITION}, result = FAIL_ON_NULL))
                    MethodHandle valueDistinctFromFunction,
            @TypeParameter("map(K, V)") Type mapType,
            @BlockPosition @SqlType(value = "map(K,V)", nativeContainerType = Block.class) Block left,
            @BlockIndex int leftPosition,
            @BlockPosition @SqlType(value = "map(K,V)", nativeContainerType = Block.class) Block right,
            @BlockIndex int rightPosition)
    {
        return isDistinctFrom(
                valueDistinctFromFunction,
                mapType,
                (Block) mapType.getObject(left, leftPosition),
                left.isNull(leftPosition),
                (Block) mapType.getObject(right, rightPosition),
                right.isNull(rightPosition));
    }
}
