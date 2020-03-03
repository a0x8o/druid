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

import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.LongVariableConstraint;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.TypeVariableConstraint;
import io.prestosql.operator.ParametricImplementation;
import io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType;
import io.prestosql.operator.annotations.FunctionsParserHelper;
import io.prestosql.operator.annotations.ImplementationDependency;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.AggregationState;
import io.prestosql.spi.function.BlockIndex;
import io.prestosql.spi.function.BlockPosition;
import io.prestosql.spi.function.OutputFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.util.Reflection;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static io.prestosql.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.inputChannelParameterType;
import static io.prestosql.operator.annotations.FunctionsParserHelper.containsAnnotation;
import static io.prestosql.operator.annotations.FunctionsParserHelper.createTypeVariableConstraints;
import static io.prestosql.operator.annotations.FunctionsParserHelper.parseLiteralParameters;
import static io.prestosql.operator.annotations.ImplementationDependency.Factory.createDependency;
import static io.prestosql.operator.annotations.ImplementationDependency.getImplementationDependencyAnnotation;
import static io.prestosql.operator.annotations.ImplementationDependency.isImplementationDependencyAnnotation;
import static io.prestosql.operator.annotations.ImplementationDependency.validateImplementationDependencyAnnotation;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.util.Reflection.methodHandle;
import static java.util.Objects.requireNonNull;

public class AggregationImplementation
        implements ParametricImplementation
{
    public static class AggregateNativeContainerType
    {
        private final Class<?> javaType;
        private final boolean isBlockPosition;

        public AggregateNativeContainerType(Class<?> javaType, boolean isBlockPosition)
        {
            this.javaType = javaType;
            this.isBlockPosition = isBlockPosition;
        }

        public Class<?> getJavaType()
        {
            return javaType;
        }

        public boolean isBlockPosition()
        {
            return isBlockPosition;
        }
    }

    private final Signature signature;

    private final Class<?> definitionClass;
    private final Class<?> stateClass;
    private final MethodHandle inputFunction;
    private final Optional<MethodHandle> removeInputFunction;
    private final MethodHandle outputFunction;
    private final MethodHandle combineFunction;
    private final Optional<MethodHandle> stateSerializerFactory;
    private final List<AggregateNativeContainerType> argumentNativeContainerTypes;
    private final List<ImplementationDependency> inputDependencies;
    private final List<ImplementationDependency> removeInputDependencies;
    private final List<ImplementationDependency> combineDependencies;
    private final List<ImplementationDependency> outputDependencies;
    private final List<ImplementationDependency> stateSerializerFactoryDependencies;
    private final List<ParameterType> inputParameterMetadataTypes;

    public AggregationImplementation(
            Signature signature,
            Class<?> definitionClass,
            Class<?> stateClass,
            MethodHandle inputFunction,
            Optional<MethodHandle> removeInputFunction,
            MethodHandle outputFunction,
            MethodHandle combineFunction,
            Optional<MethodHandle> stateSerializerFactory,
            List<AggregateNativeContainerType> argumentNativeContainerTypes,
            List<ImplementationDependency> inputDependencies,
            List<ImplementationDependency> removeInputDependencies,
            List<ImplementationDependency> combineDependencies,
            List<ImplementationDependency> outputDependencies,
            List<ImplementationDependency> stateSerializerFactoryDependencies,
            List<ParameterType> inputParameterMetadataTypes)
    {
        this.signature = requireNonNull(signature, "signature cannot be null");
        this.definitionClass = requireNonNull(definitionClass, "definition class cannot be null");
        this.stateClass = requireNonNull(stateClass, "stateClass cannot be null");
        this.inputFunction = requireNonNull(inputFunction, "inputFunction cannot be null");
        this.removeInputFunction = requireNonNull(removeInputFunction, "removeInputFunction cannot be null");
        this.outputFunction = requireNonNull(outputFunction, "outputFunction cannot be null");
        this.combineFunction = requireNonNull(combineFunction, "combineFunction cannot be null");
        this.stateSerializerFactory = requireNonNull(stateSerializerFactory, "stateSerializerFactory cannot be null");
        this.argumentNativeContainerTypes = requireNonNull(argumentNativeContainerTypes, "argumentNativeContainerTypes cannot be null");
        this.inputDependencies = requireNonNull(inputDependencies, "inputDependencies cannot be null");
        this.removeInputDependencies = requireNonNull(removeInputDependencies, "removeInputDependencies cannot be null");
        this.outputDependencies = requireNonNull(outputDependencies, "outputDependencies cannot be null");
        this.combineDependencies = requireNonNull(combineDependencies, "combineDependencies cannot be null");
        this.stateSerializerFactoryDependencies = requireNonNull(stateSerializerFactoryDependencies, "stateSerializerFactoryDependencies cannot be null");
        this.inputParameterMetadataTypes = requireNonNull(inputParameterMetadataTypes, "inputParameterMetadataTypes cannot be null");
    }

    @Override
    public Signature getSignature()
    {
        return signature;
    }

    @Override
    public boolean hasSpecializedTypeParameters()
    {
        return false;
    }

    public Class<?> getDefinitionClass()
    {
        return definitionClass;
    }

    public Class<?> getStateClass()
    {
        return stateClass;
    }

    public MethodHandle getInputFunction()
    {
        return inputFunction;
    }

    public Optional<MethodHandle> getRemoveInputFunction()
    {
        return removeInputFunction;
    }

    public MethodHandle getOutputFunction()
    {
        return outputFunction;
    }

    public MethodHandle getCombineFunction()
    {
        return combineFunction;
    }

    public Optional<MethodHandle> getStateSerializerFactory()
    {
        return stateSerializerFactory;
    }

    public List<ImplementationDependency> getInputDependencies()
    {
        return inputDependencies;
    }

    public List<ImplementationDependency> getRemoveInputDependencies()
    {
        return removeInputDependencies;
    }

    public List<ImplementationDependency> getOutputDependencies()
    {
        return outputDependencies;
    }

    public List<ImplementationDependency> getCombineDependencies()
    {
        return combineDependencies;
    }

    public List<ImplementationDependency> getStateSerializerFactoryDependencies()
    {
        return stateSerializerFactoryDependencies;
    }

    public List<ParameterType> getInputParameterMetadataTypes()
    {
        return inputParameterMetadataTypes;
    }

    public boolean areTypesAssignable(Signature boundSignature, BoundVariables variables, Metadata metadata)
    {
        checkState(argumentNativeContainerTypes.size() == boundSignature.getArgumentTypes().size(), "Number of argument assigned to AggregationImplementation is different than number parsed from annotations.");

        // TODO specialized functions variants support is missing here
        for (int i = 0; i < boundSignature.getArgumentTypes().size(); i++) {
            Class<?> argumentType = metadata.getType(boundSignature.getArgumentTypes().get(i)).getJavaType();
            Class<?> methodDeclaredType = argumentNativeContainerTypes.get(i).getJavaType();
            boolean isCurrentBlockPosition = argumentNativeContainerTypes.get(i).isBlockPosition();

            if (isCurrentBlockPosition && Block.class.isAssignableFrom(methodDeclaredType)) {
                continue;
            }
            if (!isCurrentBlockPosition && argumentType.isAssignableFrom(methodDeclaredType)) {
                continue;
            }
            return false;
        }

        return true;
    }

    public static final class Parser
    {
        private final Class<?> aggregationDefinition;
        private final Class<?> stateClass;
        private final MethodHandle inputHandle;
        private final Optional<MethodHandle> removeInputHandle;
        private final MethodHandle outputHandle;
        private final MethodHandle combineHandle;
        private final Optional<MethodHandle> stateSerializerFactoryHandle;
        private final List<AggregateNativeContainerType> argumentNativeContainerTypes;
        private final List<ImplementationDependency> inputDependencies;
        private final List<ImplementationDependency> removeInputDependencies;
        private final List<ImplementationDependency> combineDependencies;
        private final List<ImplementationDependency> outputDependencies;
        private final List<ImplementationDependency> stateSerializerFactoryDependencies;
        private final List<ParameterType> parameterMetadataTypes;

        private final List<LongVariableConstraint> longVariableConstraints;
        private final List<TypeVariableConstraint> typeVariableConstraints;
        private final List<TypeSignature> inputTypes;
        private final TypeSignature returnType;

        private final AggregationHeader header;
        private final Set<String> literalParameters;
        private final List<TypeParameter> typeParameters;

        private Parser(
                Class<?> aggregationDefinition,
                AggregationHeader header,
                Class<?> stateClass,
                Method inputFunction,
                Optional<Method> removeInputFunction,
                Method outputFunction,
                Method combineFunction,
                Optional<Method> stateSerializerFactoryFunction)
        {
            // rewrite data passed directly
            this.aggregationDefinition = aggregationDefinition;
            this.header = header;
            this.stateClass = stateClass;

            // parse declared literal and type parameters
            // it is required to declare all literal and type parameters in input function
            literalParameters = parseLiteralParameters(inputFunction);
            typeParameters = Arrays.asList(inputFunction.getAnnotationsByType(TypeParameter.class));

            // parse dependencies
            inputDependencies = parseImplementationDependencies(inputFunction);
            removeInputDependencies = removeInputFunction.map(this::parseImplementationDependencies).orElse(ImmutableList.of());
            outputDependencies = parseImplementationDependencies(outputFunction);
            combineDependencies = parseImplementationDependencies(combineFunction);
            stateSerializerFactoryDependencies = stateSerializerFactoryFunction.map(this::parseImplementationDependencies).orElse(ImmutableList.of());

            // parse metadata types
            parameterMetadataTypes = parseParameterMetadataTypes(inputFunction);

            // parse constraints
            longVariableConstraints = FunctionsParserHelper.parseLongVariableConstraints(inputFunction);
            List<ImplementationDependency> allDependencies =
                    Stream.of(
                            inputDependencies.stream(),
                            removeInputDependencies.stream(),
                            outputDependencies.stream(),
                            combineDependencies.stream())
                    .reduce(Stream::concat)
                    .orElseGet(Stream::empty)
                    .collect(toImmutableList());
            typeVariableConstraints = createTypeVariableConstraints(typeParameters, allDependencies);

            // parse native types of arguments
            argumentNativeContainerTypes = parseSignatureArgumentsTypes(inputFunction);

            // determine TypeSignatures of function declaration
            inputTypes = getInputTypesSignatures(inputFunction);
            returnType = parseTypeSignature(outputFunction.getAnnotation(OutputFunction.class).value(), literalParameters);

            // unreflect methods for further use
            if (stateSerializerFactoryFunction.isPresent()) {
                stateSerializerFactoryHandle = Optional.of(methodHandle(stateSerializerFactoryFunction.get()));
            }
            else {
                stateSerializerFactoryHandle = Optional.empty();
            }

            inputHandle = methodHandle(inputFunction);
            removeInputHandle = removeInputFunction.map(Reflection::methodHandle);
            combineHandle = methodHandle(combineFunction);
            outputHandle = methodHandle(outputFunction);
        }

        private AggregationImplementation get()
        {
            Signature signature = new Signature(
                    header.getName(),
                    FunctionKind.AGGREGATE,
                    typeVariableConstraints,
                    longVariableConstraints,
                    returnType,
                    inputTypes,
                    false);

            return new AggregationImplementation(signature,
                    aggregationDefinition,
                    stateClass,
                    inputHandle,
                    removeInputHandle,
                    outputHandle,
                    combineHandle,
                    stateSerializerFactoryHandle,
                    argumentNativeContainerTypes,
                    inputDependencies,
                    removeInputDependencies,
                    combineDependencies,
                    outputDependencies,
                    stateSerializerFactoryDependencies,
                    parameterMetadataTypes);
        }

        public static AggregationImplementation parseImplementation(
                Class<?> aggregationDefinition,
                AggregationHeader header,
                Class<?> stateClass,
                Method inputFunction,
                Optional<Method> removeInputFunction,
                Method outputFunction,
                Method combineFunction,
                Optional<Method> stateSerializerFactoryFunction)
        {
            return new Parser(aggregationDefinition, header, stateClass, inputFunction, removeInputFunction, outputFunction, combineFunction, stateSerializerFactoryFunction).get();
        }

        private static List<ParameterType> parseParameterMetadataTypes(Method method)
        {
            ImmutableList.Builder<ParameterType> builder = ImmutableList.builder();

            Annotation[][] annotations = method.getParameterAnnotations();
            String methodName = method.getDeclaringClass() + "." + method.getName();

            checkArgument(method.getParameterCount() > 0, "At least @AggregationState argument is required for each of aggregation functions.");

            int i = 0;
            if (annotations[0].length == 0) {
                // Backward compatibility - first argument without annotations is interpreted as State argument
                builder.add(STATE);
                i++;
            }

            for (; i < annotations.length; ++i) {
                Annotation baseTypeAnnotation = baseTypeAnnotation(annotations[i], methodName);
                if (isImplementationDependencyAnnotation(baseTypeAnnotation)) {
                    // Implementation dependencies are bound in specializing phase.
                    // For that reason there are omitted in parameter metadata, as they
                    // are no longer visible while processing aggregations.
                }
                else if (baseTypeAnnotation instanceof AggregationState) {
                    builder.add(STATE);
                }
                else if (baseTypeAnnotation instanceof SqlType) {
                    boolean isParameterBlock = isParameterBlock(annotations[i]);
                    boolean isParameterNullable = isParameterNullable(annotations[i]);
                    builder.add(inputChannelParameterType(isParameterNullable, isParameterBlock, methodName));
                }
                else if (baseTypeAnnotation instanceof BlockIndex) {
                    builder.add(BLOCK_INDEX);
                }
                else {
                    throw new IllegalArgumentException("Unsupported annotation: " + annotations[i]);
                }
            }
            return builder.build();
        }

        private static Annotation baseTypeAnnotation(Annotation[] annotations, String methodName)
        {
            List<Annotation> baseTypes = Arrays.asList(annotations).stream()
                    .filter(annotation -> isAggregationMetaAnnotation(annotation) || annotation instanceof SqlType)
                    .collect(toImmutableList());

            checkArgument(baseTypes.size() == 1, "Parameter of %s must have exactly one of @SqlType, @BlockIndex", methodName);

            boolean nullable = isParameterNullable(annotations);
            boolean isBlock = isParameterBlock(annotations);

            Annotation annotation = baseTypes.get(0);
            checkArgument((!isBlock && !nullable) || (annotation instanceof SqlType),
                    "%s contains a parameter with @BlockPosition and/or @NullablePosition that is not @SqlType", methodName);

            return annotation;
        }

        public static List<AggregateNativeContainerType> parseSignatureArgumentsTypes(Method inputFunction)
        {
            ImmutableList.Builder<AggregateNativeContainerType> builder = ImmutableList.builder();

            for (int i = 0; i < inputFunction.getParameterCount(); i++) {
                Class<?> parameterType = inputFunction.getParameterTypes()[i];
                Annotation[] annotations = inputFunction.getParameterAnnotations()[i];

                // Skip injected parameters
                if (parameterType == ConnectorSession.class) {
                    continue;
                }

                if (containsAnnotation(annotations, Parser::isAggregationMetaAnnotation)) {
                    continue;
                }

                builder.add(new AggregateNativeContainerType(inputFunction.getParameterTypes()[i], isParameterBlock(annotations)));
            }

            return builder.build();
        }

        public List<ImplementationDependency> parseImplementationDependencies(Method inputFunction)
        {
            ImmutableList.Builder<ImplementationDependency> builder = ImmutableList.builder();

            for (Parameter parameter : inputFunction.getParameters()) {
                Class<?> parameterType = parameter.getType();

                // Skip injected parameters
                if (parameterType == ConnectorSession.class) {
                    continue;
                }

                getImplementationDependencyAnnotation(parameter).ifPresent(annotation -> {
                    // check if only declared typeParameters and literalParameters are used
                    validateImplementationDependencyAnnotation(
                            inputFunction,
                            annotation,
                            typeParameters.stream()
                                    .map(TypeParameter::value)
                                    .collect(toImmutableSet()),
                            literalParameters);
                    builder.add(createDependency(annotation, literalParameters));
                });
            }
            return builder.build();
        }

        public static boolean isParameterNullable(Annotation[] annotations)
        {
            return containsAnnotation(annotations, annotation -> annotation instanceof NullablePosition);
        }

        public static boolean isParameterBlock(Annotation[] annotations)
        {
            return containsAnnotation(annotations, annotation -> annotation instanceof BlockPosition);
        }

        public List<TypeSignature> getInputTypesSignatures(Method inputFunction)
        {
            ImmutableList.Builder<TypeSignature> builder = ImmutableList.builder();

            Annotation[][] parameterAnnotations = inputFunction.getParameterAnnotations();
            for (Annotation[] annotations : parameterAnnotations) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof SqlType) {
                        String typeName = ((SqlType) annotation).value();
                        builder.add(parseTypeSignature(typeName, literalParameters));
                    }
                }
            }

            return builder.build();
        }

        public static Class<?> findAggregationStateParamType(Method inputFunction)
        {
            return inputFunction.getParameterTypes()[findAggregationStateParamId(inputFunction)];
        }

        public static int findAggregationStateParamId(Method method)
        {
            return findAggregationStateParamId(method, 0);
        }

        public static int findAggregationStateParamId(Method method, int id)
        {
            int currentParamId = 0;
            int found = 0;
            for (Annotation[] annotations : method.getParameterAnnotations()) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof AggregationState) {
                        if (found++ == id) {
                            return currentParamId;
                        }
                    }
                }
                currentParamId++;
            }

            // backward compatibility @AggregationState annotation didn't exists before
            // some third party aggregates may assume that State will be id-th parameter
            return id;
        }

        private static boolean isAggregationMetaAnnotation(Annotation annotation)
        {
            return annotation instanceof BlockIndex || annotation instanceof AggregationState || isImplementationDependencyAnnotation(annotation);
        }
    }
}
