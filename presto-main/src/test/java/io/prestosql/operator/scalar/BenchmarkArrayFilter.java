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
package io.prestosql.operator.scalar;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.FunctionListBuilder;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.operator.DriverYieldSignal;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.gen.ExpressionCompiler;
import io.prestosql.sql.gen.PageFunctionCompiler;
import io.prestosql.sql.relational.CallExpression;
import io.prestosql.sql.relational.LambdaDefinitionExpression;
import io.prestosql.sql.relational.RowExpression;
import io.prestosql.sql.relational.VariableReferenceExpression;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Throwables.throwIfUnchecked;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.metadata.Signature.typeVariable;
import static io.prestosql.operator.scalar.BenchmarkArrayFilter.ExactArrayFilterFunction.EXACT_ARRAY_FILTER_FUNCTION;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.TypeSignature.arrayType;
import static io.prestosql.spi.type.TypeSignature.functionType;
import static io.prestosql.spi.type.TypeUtils.readNativeValue;
import static io.prestosql.sql.relational.Expressions.constant;
import static io.prestosql.sql.relational.Expressions.field;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static io.prestosql.util.Reflection.methodHandle;
import static java.lang.Boolean.TRUE;

@SuppressWarnings("MethodMayBeStatic")
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkArrayFilter
{
    private static final int POSITIONS = 100_000;
    private static final int ARRAY_SIZE = 4;
    private static final int NUM_TYPES = 1;
    private static final List<Type> TYPES = ImmutableList.of(BIGINT);

    static {
        Verify.verify(NUM_TYPES == TYPES.size());
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS * ARRAY_SIZE * NUM_TYPES)
    public List<Optional<Page>> benchmark(BenchmarkData data)
    {
        return ImmutableList.copyOf(
                data.getPageProcessor().process(
                        SESSION,
                        new DriverYieldSignal(),
                        newSimpleAggregatedMemoryContext().newLocalMemoryContext(PageProcessor.class.getSimpleName()),
                        data.getPage()));
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"filter", "exact_filter"})
        private String name = "filter";

        private Page page;
        private PageProcessor pageProcessor;

        @Setup
        public void setup()
        {
            Metadata metadata = createTestMetadataManager();
            metadata.addFunctions(new FunctionListBuilder().function(EXACT_ARRAY_FILTER_FUNCTION).getFunctions());
            ExpressionCompiler compiler = new ExpressionCompiler(metadata, new PageFunctionCompiler(metadata, 0));
            ImmutableList.Builder<RowExpression> projectionsBuilder = ImmutableList.builder();
            Block[] blocks = new Block[TYPES.size()];
            for (int i = 0; i < TYPES.size(); i++) {
                Type elementType = TYPES.get(i);
                ArrayType arrayType = new ArrayType(elementType);
                Signature signature = new Signature(
                        name,
                        FunctionKind.SCALAR,
                        arrayType.getTypeSignature(),
                        arrayType.getTypeSignature(),
                        functionType(BIGINT.getTypeSignature(), BOOLEAN.getTypeSignature()));
                Signature greaterThan = new Signature("$operator$" + GREATER_THAN.name(), FunctionKind.SCALAR, BOOLEAN.getTypeSignature(), BIGINT.getTypeSignature(), BIGINT.getTypeSignature());
                projectionsBuilder.add(new CallExpression(signature, arrayType, ImmutableList.of(
                        field(0, arrayType),
                        new LambdaDefinitionExpression(
                                ImmutableList.of(BIGINT),
                                ImmutableList.of("x"),
                                new CallExpression(greaterThan, BOOLEAN, ImmutableList.of(new VariableReferenceExpression("x", BIGINT), constant(0L, BIGINT)))))));
                blocks[i] = createChannel(POSITIONS, ARRAY_SIZE, arrayType);
            }

            ImmutableList<RowExpression> projections = projectionsBuilder.build();
            pageProcessor = compiler.compilePageProcessor(Optional.empty(), projections).get();
            page = new Page(blocks);
        }

        private static Block createChannel(int positionCount, int arraySize, ArrayType arrayType)
        {
            BlockBuilder blockBuilder = arrayType.createBlockBuilder(null, positionCount);
            for (int position = 0; position < positionCount; position++) {
                BlockBuilder entryBuilder = blockBuilder.beginBlockEntry();
                for (int i = 0; i < arraySize; i++) {
                    if (arrayType.getElementType().getJavaType() == long.class) {
                        arrayType.getElementType().writeLong(entryBuilder, ThreadLocalRandom.current().nextLong());
                    }
                    else {
                        throw new UnsupportedOperationException();
                    }
                }
                blockBuilder.closeEntry();
            }
            return blockBuilder.build();
        }

        public PageProcessor getPageProcessor()
        {
            return pageProcessor;
        }

        public Page getPage()
        {
            return page;
        }
    }

    public static void main(String[] args)
            throws Throwable
    {
        // assure the benchmarks are valid before running
        BenchmarkData data = new BenchmarkData();
        data.setup();
        new BenchmarkArrayFilter().benchmark(data);

        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkArrayFilter.class.getSimpleName() + ".*")
                .build();
        new Runner(options).run();
    }

    public static final class ExactArrayFilterFunction
            extends SqlScalarFunction
    {
        public static final ExactArrayFilterFunction EXACT_ARRAY_FILTER_FUNCTION = new ExactArrayFilterFunction();

        private static final MethodHandle METHOD_HANDLE = methodHandle(ExactArrayFilterFunction.class, "filter", Type.class, Block.class, MethodHandle.class);

        private ExactArrayFilterFunction()
        {
            super(new Signature(
                    "exact_filter",
                    FunctionKind.SCALAR,
                    ImmutableList.of(typeVariable("T")),
                    ImmutableList.of(),
                    arrayType(new TypeSignature("T")),
                    ImmutableList.of(
                            arrayType(new TypeSignature("T")),
                            functionType(new TypeSignature("T"), BOOLEAN.getTypeSignature())),
                    false));
        }

        @Override
        public boolean isHidden()
        {
            return false;
        }

        @Override
        public boolean isDeterministic()
        {
            return false;
        }

        @Override
        public String getDescription()
        {
            return "return array containing elements that match the given predicate";
        }

        @Override
        public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, Metadata metadata)
        {
            Type type = boundVariables.getTypeVariable("T");
            return new ScalarFunctionImplementation(
                    false,
                    ImmutableList.of(
                            valueTypeArgumentProperty(RETURN_NULL_ON_NULL),
                            valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                    METHOD_HANDLE.bindTo(type),
                    isDeterministic());
        }

        public static Block filter(Type type, Block block, MethodHandle function)
        {
            int positionCount = block.getPositionCount();
            BlockBuilder resultBuilder = type.createBlockBuilder(null, positionCount);
            for (int position = 0; position < positionCount; position++) {
                Long input = (Long) readNativeValue(type, block, position);
                Boolean keep;
                try {
                    keep = (Boolean) function.invokeExact(input);
                }
                catch (Throwable t) {
                    throwIfUnchecked(t);
                    throw new RuntimeException(t);
                }
                if (TRUE.equals(keep)) {
                    block.writePositionTo(position, resultBuilder);
                }
            }
            return resultBuilder.build();
        }
    }
}
