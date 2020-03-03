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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.SqlAggregationFunction;
import com.facebook.presto.operator.aggregation.state.MultiKeyValuePairStateSerializer;
import com.facebook.presto.operator.aggregation.state.MultiKeyValuePairsState;
import com.facebook.presto.operator.aggregation.state.MultiKeyValuePairsStateFactory;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignatureParameter;
import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.DynamicClassLoader;

import java.lang.invoke.MethodHandle;
import java.util.List;

import static com.facebook.presto.metadata.Signature.comparableTypeParameter;
import static com.facebook.presto.metadata.Signature.typeVariable;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.NULLABLE_BLOCK_INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static com.facebook.presto.operator.aggregation.AggregationUtils.generateAggregationName;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.util.Reflection.methodHandle;
import static com.google.common.collect.ImmutableList.toImmutableList;

public class MultimapAggregationFunction
        extends SqlAggregationFunction
{
    public static final MultimapAggregationFunction MULTIMAP_AGG = new MultimapAggregationFunction();
    public static final String NAME = "multimap_agg";
    private static final MethodHandle OUTPUT_FUNCTION = methodHandle(MultimapAggregationFunction.class, "output", MultiKeyValuePairsState.class, BlockBuilder.class);
    private static final MethodHandle INPUT_FUNCTION = methodHandle(MultimapAggregationFunction.class, "input", MultiKeyValuePairsState.class, Block.class, Block.class, int.class);
    private static final MethodHandle COMBINE_FUNCTION = methodHandle(MultimapAggregationFunction.class, "combine", MultiKeyValuePairsState.class, MultiKeyValuePairsState.class);

    public MultimapAggregationFunction()
    {
        super(NAME,
                ImmutableList.of(comparableTypeParameter("K"), typeVariable("V")),
                ImmutableList.of(),
                parseTypeSignature("map(K,array(V))"),
                ImmutableList.of(parseTypeSignature("K"), parseTypeSignature("V")));
    }

    @Override
    public String getDescription()
    {
        return "Aggregates all the rows (key/value pairs) into a single multimap";
    }

    @Override
    public InternalAggregationFunction specialize(BoundVariables boundVariables, int arity, TypeManager typeManager, FunctionRegistry functionRegistry)
    {
        Type keyType = boundVariables.getTypeVariable("K");
        Type valueType = boundVariables.getTypeVariable("V");
        Type outputType = typeManager.getParameterizedType(StandardTypes.MAP, ImmutableList.of(
                TypeSignatureParameter.of(keyType.getTypeSignature()),
                TypeSignatureParameter.of(new ArrayType(valueType).getTypeSignature())));
        return generateAggregation(keyType, valueType, outputType);
    }

    private static InternalAggregationFunction generateAggregation(Type keyType, Type valueType, Type outputType)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(MultimapAggregationFunction.class.getClassLoader());
        List<Type> inputTypes = ImmutableList.of(keyType, valueType);
        MultiKeyValuePairStateSerializer stateSerializer = new MultiKeyValuePairStateSerializer(keyType, valueType);
        Type intermediateType = stateSerializer.getSerializedType();

        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, outputType.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                createInputParameterMetadata(keyType, valueType),
                INPUT_FUNCTION,
                COMBINE_FUNCTION,
                OUTPUT_FUNCTION,
                MultiKeyValuePairsState.class,
                stateSerializer,
                new MultiKeyValuePairsStateFactory(keyType, valueType),
                outputType);

        GenericAccumulatorFactoryBinder factory = AccumulatorCompiler.generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(NAME, inputTypes, intermediateType, outputType, true, true, factory);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type keyType, Type valueType)
    {
        return ImmutableList.of(new ParameterMetadata(STATE),
                new ParameterMetadata(BLOCK_INPUT_CHANNEL, keyType),
                new ParameterMetadata(NULLABLE_BLOCK_INPUT_CHANNEL, valueType),
                new ParameterMetadata(BLOCK_INDEX));
    }

    public static void input(MultiKeyValuePairsState state, Block key, Block value, int position)
    {
        MultiKeyValuePairs pairs = state.get();
        if (pairs == null) {
            pairs = new MultiKeyValuePairs(state.getKeyType(), state.getValueType());
            state.set(pairs);
        }

        long startSize = pairs.estimatedInMemorySize();
        pairs.add(key, value, position, position);
        state.addMemoryUsage(pairs.estimatedInMemorySize() - startSize);
    }

    public static void combine(MultiKeyValuePairsState state, MultiKeyValuePairsState otherState)
    {
        if (state.get() != null && otherState.get() != null) {
            Block keys = otherState.get().getKeys();
            Block values = otherState.get().getValues();
            MultiKeyValuePairs pairs = state.get();
            long startSize = pairs.estimatedInMemorySize();
            for (int i = 0; i < keys.getPositionCount(); i++) {
                pairs.add(keys, values, i, i);
            }
            state.addMemoryUsage(pairs.estimatedInMemorySize() - startSize);
        }
        else if (state.get() == null) {
            state.set(otherState.get());
        }
    }

    public static void output(MultiKeyValuePairsState state, BlockBuilder out)
    {
        MultiKeyValuePairs pairs = state.get();
        if (pairs == null) {
            out.appendNull();
        }
        else {
            pairs.toMultimapNativeEncoding(out);
        }
    }
}
