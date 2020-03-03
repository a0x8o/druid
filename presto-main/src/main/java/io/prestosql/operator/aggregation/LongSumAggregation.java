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
package io.prestosql.operator.aggregation;

import io.prestosql.operator.aggregation.minmaxby.LongLongState;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.AggregationFunction;
import io.prestosql.spi.function.AggregationState;
import io.prestosql.spi.function.CombineFunction;
import io.prestosql.spi.function.InputFunction;
import io.prestosql.spi.function.OutputFunction;
import io.prestosql.spi.function.RemoveInputFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.type.BigintOperators;

@AggregationFunction("sum")
public final class LongSumAggregation
{
    private LongSumAggregation() {}

    @InputFunction
    public static void sum(@AggregationState LongLongState state, @SqlType(StandardTypes.BIGINT) long value)
    {
        state.setFirst(state.getFirst() + 1);
        state.setSecond(BigintOperators.add(state.getSecond(), value));
    }

    @RemoveInputFunction
    public static void removeInput(@AggregationState LongLongState state, @SqlType(StandardTypes.BIGINT) long value)
    {
        state.setFirst(state.getFirst() - 1);
        state.setSecond(BigintOperators.subtract(state.getSecond(), value));
    }

    @CombineFunction
    public static void combine(@AggregationState LongLongState state, @AggregationState LongLongState otherState)
    {
        state.setFirst(state.getFirst() + otherState.getFirst());
        state.setSecond(BigintOperators.add(state.getSecond(), otherState.getSecond()));
    }

    @OutputFunction(StandardTypes.BIGINT)
    public static void output(@AggregationState LongLongState state, BlockBuilder out)
    {
        if (state.getFirst() == 0) {
            out.appendNull();
        }
        else {
            BigintType.BIGINT.writeLong(out, state.getSecond());
        }
    }
}
