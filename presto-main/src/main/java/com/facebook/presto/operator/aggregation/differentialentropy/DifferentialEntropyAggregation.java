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
package com.facebook.presto.operator.aggregation.differentialentropy;

import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.AggregationFunction;
import com.facebook.presto.spi.function.AggregationState;
import com.facebook.presto.spi.function.CombineFunction;
import com.facebook.presto.spi.function.Description;
import com.facebook.presto.spi.function.InputFunction;
import com.facebook.presto.spi.function.OutputFunction;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.type.StandardTypes;
import com.google.common.annotations.VisibleForTesting;
import io.airlift.slice.Slice;

import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.google.common.base.Verify.verify;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

@AggregationFunction("differential_entropy")
@Description("Computes differential entropy based on random-variable samples")
public final class DifferentialEntropyAggregation
{
    @VisibleForTesting
    public static final String FIXED_HISTOGRAM_MLE_METHOD_NAME = "fixed_histogram_mle";
    @VisibleForTesting
    public static final String FIXED_HISTOGRAM_JACKNIFE_METHOD_NAME = "fixed_histogram_jacknife";

    private DifferentialEntropyAggregation() {}

    @InputFunction
    public static void input(
            @AggregationState DifferentialEntropyState state,
            @SqlType(StandardTypes.BIGINT) long size,
            @SqlType(StandardTypes.DOUBLE) double sample,
            @SqlType(StandardTypes.DOUBLE) double weight,
            @SqlType(StandardTypes.VARCHAR) Slice method,
            @SqlType(StandardTypes.DOUBLE) double min,
            @SqlType(StandardTypes.DOUBLE) double max)
    {
        String requestedMethod = method.toStringUtf8().toLowerCase(ENGLISH);
        DifferentialEntropyStateStrategy strategy = state.getStrategy();
        if (strategy == null) {
            switch (requestedMethod) {
                case FIXED_HISTOGRAM_MLE_METHOD_NAME:
                    strategy = new FixedHistogramMleStateStrategy(size, min, max);
                    break;
                case FIXED_HISTOGRAM_JACKNIFE_METHOD_NAME:
                    strategy = new FixedHistogramJacknifeStateStrategy(size, min, max);
                    break;
                default:
                    throw new PrestoException(
                            INVALID_FUNCTION_ARGUMENT,
                            format("In differential_entropy UDF, invalid method: %s", requestedMethod));
            }
            state.setStrategy(strategy);
        }
        else {
            switch (requestedMethod.toLowerCase(ENGLISH)) {
                case FIXED_HISTOGRAM_MLE_METHOD_NAME:
                    verify(strategy instanceof FixedHistogramMleStateStrategy,
                            format("Strategy class is not compatible with entropy method: %s %s", strategy.getClass().getSimpleName(), method));
                    break;
                case FIXED_HISTOGRAM_JACKNIFE_METHOD_NAME:
                    verify(strategy instanceof FixedHistogramJacknifeStateStrategy,
                            format("Strategy class is not compatible with entropy method: %s %s", strategy.getClass().getSimpleName(), method));
                    break;
                default:
                    verify(false, format("Unknown entropy method %s", method));
            }
        }
        strategy.validateParameters(size, sample, weight, min, max);
        strategy.add(sample, weight);
    }

    @InputFunction
    public static void input(
            @AggregationState DifferentialEntropyState state,
            @SqlType(StandardTypes.BIGINT) long size,
            @SqlType(StandardTypes.DOUBLE) double sample,
            @SqlType(StandardTypes.DOUBLE) double weight)
    {
        DifferentialEntropyStateStrategy strategy = state.getStrategy();
        if (state.getStrategy() == null) {
            strategy = new WeightedReservoirSampleStateStrategy(size);
            state.setStrategy(strategy);
        }
        else {
            verify(strategy instanceof WeightedReservoirSampleStateStrategy,
                    format("Expected WeightedReservoirSampleStateStrategy, got: %s", strategy.getClass().getSimpleName()));
        }
        strategy.validateParameters(size, sample, weight);
        strategy.add(sample, weight);
    }

    @InputFunction
    public static void input(
            @AggregationState DifferentialEntropyState state,
            @SqlType(StandardTypes.BIGINT) long size,
            @SqlType(StandardTypes.DOUBLE) double sample)
    {
        DifferentialEntropyStateStrategy strategy = state.getStrategy();
        if (state.getStrategy() == null) {
            strategy = new UnweightedReservoirSampleStateStrategy(size);
            state.setStrategy(strategy);
        }
        else {
            verify(strategy instanceof UnweightedReservoirSampleStateStrategy,
                    format("Expected UnweightedReservoirSampleStateStrategy, got: %s", strategy.getClass().getSimpleName()));
        }
        strategy.validateParameters(size, sample);
        strategy.add(sample, 1.0);
    }

    @CombineFunction
    public static void combine(
            @AggregationState DifferentialEntropyState state,
            @AggregationState DifferentialEntropyState otherState)
    {
        DifferentialEntropyStateStrategy strategy = state.getStrategy();
        DifferentialEntropyStateStrategy otherStrategy = otherState.getStrategy();
        if (strategy == null && otherStrategy != null) {
            state.setStrategy(otherStrategy);
            return;
        }
        if (otherStrategy == null) {
            return;
        }

        verify(strategy.getClass() == otherStrategy.getClass(),
                format("In combine, %s != %s", strategy.getClass().getSimpleName(), otherStrategy.getClass().getSimpleName()));

        strategy.mergeWith(otherStrategy);
    }

    @OutputFunction("double")
    public static void output(@AggregationState DifferentialEntropyState state, BlockBuilder out)
    {
        DifferentialEntropyStateStrategy strategy = state.getStrategy();
        DOUBLE.writeDouble(out, strategy == null ? Double.NaN : strategy.calculateEntropy());
    }
}
