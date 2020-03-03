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

import com.google.common.annotations.VisibleForTesting;
import io.prestosql.Session;
import io.prestosql.operator.PagesIndex;
import io.prestosql.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class GenericAccumulatorFactoryBinder
        implements AccumulatorFactoryBinder
{
    private final List<AccumulatorStateDescriptor> stateDescriptors;
    private final Constructor<? extends Accumulator> accumulatorConstructor;
    private final boolean accumulatorHasRemoveInput;
    private final Constructor<? extends GroupedAccumulator> groupedAccumulatorConstructor;

    public GenericAccumulatorFactoryBinder(
            List<AccumulatorStateDescriptor> stateDescriptors,
            Class<? extends Accumulator> accumulatorClass,
            boolean accumulatorHasRemoveInput,
            Class<? extends GroupedAccumulator> groupedAccumulatorClass)
    {
        this.stateDescriptors = requireNonNull(stateDescriptors, "stateDescriptors is null");

        try {
            accumulatorConstructor = accumulatorClass.getConstructor(
                    List.class,     /* List<AccumulatorStateDescriptor> stateDescriptors */
                    List.class,     /* List<Integer> inputChannel */
                    Optional.class, /* Optional<Integer> maskChannel */
                    List.class      /* List<LambdaProvider> lambdaProviders */);

            this.accumulatorHasRemoveInput = accumulatorHasRemoveInput;

            groupedAccumulatorConstructor = groupedAccumulatorClass.getConstructor(
                    List.class,     /* List<AccumulatorStateDescriptor> stateDescriptors */
                    List.class,     /* List<Integer> inputChannel */
                    Optional.class, /* Optional<Integer> maskChannel */
                    List.class      /* List<LambdaProvider> lambdaProviders */);
        }
        catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AccumulatorFactory bind(
            List<Integer> argumentChannels,
            Optional<Integer> maskChannel,
            List<Type> sourceTypes,
            List<Integer> orderByChannels,
            List<SortOrder> orderings,
            PagesIndex.Factory pagesIndexFactory,
            boolean distinct,
            JoinCompiler joinCompiler,
            List<LambdaProvider> lambdaProviders,
            Session session)
    {
        return new GenericAccumulatorFactory(
                stateDescriptors,
                accumulatorConstructor,
                accumulatorHasRemoveInput,
                groupedAccumulatorConstructor,
                lambdaProviders,
                argumentChannels,
                maskChannel,
                sourceTypes,
                orderByChannels,
                orderings,
                pagesIndexFactory,
                joinCompiler,
                session,
                distinct);
    }

    @VisibleForTesting
    public List<AccumulatorStateDescriptor> getStateDescriptors()
    {
        return stateDescriptors;
    }
}
