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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Primitives;
import io.airlift.joni.Regex;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.prestosql.Session;
import io.prestosql.client.FailureInfo;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.scalar.ArraySubscriptOperator;
import io.prestosql.operator.scalar.ScalarFunctionImplementation;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.RowBlockBuilder;
import io.prestosql.spi.block.SingleRowBlock;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.RowType.Field;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.InterpretedFunctionInvoker;
import io.prestosql.sql.analyzer.ExpressionAnalyzer;
import io.prestosql.sql.analyzer.Scope;
import io.prestosql.sql.planner.iterative.rule.DesugarCurrentPath;
import io.prestosql.sql.planner.iterative.rule.DesugarCurrentUser;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.ArithmeticUnaryExpression;
import io.prestosql.sql.tree.ArrayConstructor;
import io.prestosql.sql.tree.AstVisitor;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.BindExpression;
import io.prestosql.sql.tree.BooleanLiteral;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.CurrentPath;
import io.prestosql.sql.tree.CurrentUser;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.ExistsPredicate;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FieldReference;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.IfExpression;
import io.prestosql.sql.tree.InListExpression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.IsNotNullPredicate;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.LambdaExpression;
import io.prestosql.sql.tree.LikePredicate;
import io.prestosql.sql.tree.Literal;
import io.prestosql.sql.tree.LogicalBinaryExpression;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.NullIfExpression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.QuantifiedComparisonExpression;
import io.prestosql.sql.tree.Row;
import io.prestosql.sql.tree.SearchedCaseExpression;
import io.prestosql.sql.tree.SimpleCaseExpression;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.sql.tree.SubqueryExpression;
import io.prestosql.sql.tree.SubscriptExpression;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.WhenClause;
import io.prestosql.type.FunctionType;
import io.prestosql.type.LikeFunctions;
import io.prestosql.type.TypeCoercion;
import io.prestosql.util.Failures;
import io.prestosql.util.FastutilSetHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.metadata.LiteralFunction.isSupportedLiteralType;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.TYPE_MISMATCH;
import static io.prestosql.spi.type.TypeUtils.readNativeValue;
import static io.prestosql.spi.type.TypeUtils.writeNativeValue;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.sql.analyzer.ConstantExpressionVerifier.verifyExpressionIsConstant;
import static io.prestosql.sql.analyzer.ExpressionAnalyzer.createConstantAnalyzer;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;
import static io.prestosql.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.prestosql.sql.gen.VarArgsToMapAdapterGenerator.generateVarArgsToMapAdapter;
import static io.prestosql.sql.planner.DeterminismEvaluator.isDeterministic;
import static io.prestosql.sql.planner.iterative.rule.CanonicalizeExpressionRewriter.canonicalizeExpression;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.type.LikeFunctions.isLikePattern;
import static io.prestosql.type.LikeFunctions.unescapeLiteralLikePattern;
import static io.prestosql.util.Failures.checkCondition;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class ExpressionInterpreter
{
    private final Expression expression;
    private final Metadata metadata;
    private final LiteralEncoder literalEncoder;
    private final ConnectorSession session;
    private final boolean optimize;
    private final Map<NodeRef<Expression>, Type> expressionTypes;
    private final InterpretedFunctionInvoker functionInvoker;
    private final TypeCoercion typeCoercion;

    private final Visitor visitor;

    // identity-based cache for LIKE expressions with constant pattern and escape char
    private final IdentityHashMap<LikePredicate, Regex> likePatternCache = new IdentityHashMap<>();
    private final IdentityHashMap<InListExpression, Set<?>> inListCache = new IdentityHashMap<>();

    public static ExpressionInterpreter expressionInterpreter(Expression expression, Metadata metadata, Session session, Map<NodeRef<Expression>, Type> expressionTypes)
    {
        return new ExpressionInterpreter(expression, metadata, session, expressionTypes, false);
    }

    public static ExpressionInterpreter expressionOptimizer(Expression expression, Metadata metadata, Session session, Map<NodeRef<Expression>, Type> expressionTypes)
    {
        requireNonNull(expression, "expression is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(session, "session is null");

        return new ExpressionInterpreter(expression, metadata, session, expressionTypes, true);
    }

    public static Object evaluateConstantExpression(Expression expression, Type expectedType, Metadata metadata, Session session, Map<NodeRef<Parameter>, Expression> parameters)
    {
        ExpressionAnalyzer analyzer = createConstantAnalyzer(metadata, session, parameters, WarningCollector.NOOP);
        analyzer.analyze(expression, Scope.create());

        Type actualType = analyzer.getExpressionTypes().get(NodeRef.of(expression));
        if (!new TypeCoercion(metadata::getType).canCoerce(actualType, expectedType)) {
            throw semanticException(TYPE_MISMATCH, expression, format("Cannot cast type %s to %s",
                    actualType.getTypeSignature(),
                    expectedType.getTypeSignature()));
        }

        Map<NodeRef<Expression>, Type> coercions = ImmutableMap.<NodeRef<Expression>, Type>builder()
                .putAll(analyzer.getExpressionCoercions())
                .put(NodeRef.of(expression), expectedType)
                .build();
        return evaluateConstantExpression(expression, coercions, analyzer.getTypeOnlyCoercions(), metadata, session, ImmutableSet.of(), parameters);
    }

    private static Object evaluateConstantExpression(
            Expression expression,
            Map<NodeRef<Expression>, Type> coercions,
            Set<NodeRef<Expression>> typeOnlyCoercions,
            Metadata metadata,
            Session session,
            Set<NodeRef<Expression>> columnReferences,
            Map<NodeRef<Parameter>, Expression> parameters)
    {
        requireNonNull(columnReferences, "columnReferences is null");

        verifyExpressionIsConstant(columnReferences, expression);

        // add coercions
        Expression rewrite = Coercer.addCoercions(expression, coercions, typeOnlyCoercions);

        // redo the analysis since above expression rewriter might create new expressions which do not have entries in the type map
        ExpressionAnalyzer analyzer = createConstantAnalyzer(metadata, session, parameters, WarningCollector.NOOP);
        analyzer.analyze(rewrite, Scope.create());

        // remove syntax sugar
        rewrite = DesugarAtTimeZoneRewriter.rewrite(rewrite, analyzer.getExpressionTypes(), metadata);

        // The optimization above may have rewritten the expression tree which breaks all the identity maps, so redo the analysis
        // to re-analyze coercions that might be necessary
        analyzer = createConstantAnalyzer(metadata, session, parameters, WarningCollector.NOOP);
        analyzer.analyze(rewrite, Scope.create());

        // expressionInterpreter/optimizer only understands a subset of expression types
        // TODO: remove this when the new expression tree is implemented
        Expression canonicalized = canonicalizeExpression(rewrite, analyzer.getExpressionTypes(), metadata);

        // The optimization above may have rewritten the expression tree which breaks all the identity maps, so redo the analysis
        // to re-analyze coercions that might be necessary
        analyzer = createConstantAnalyzer(metadata, session, parameters, WarningCollector.NOOP);
        analyzer.analyze(canonicalized, Scope.create());

        // evaluate the expression
        Object result = expressionInterpreter(canonicalized, metadata, session, analyzer.getExpressionTypes()).evaluate();
        verify(!(result instanceof Expression), "Expression interpreter returned an unresolved expression");
        return result;
    }

    private ExpressionInterpreter(Expression expression, Metadata metadata, Session session, Map<NodeRef<Expression>, Type> expressionTypes, boolean optimize)
    {
        this.expression = requireNonNull(expression, "expression is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.literalEncoder = new LiteralEncoder(metadata);
        this.session = requireNonNull(session, "session is null").toConnectorSession();
        this.expressionTypes = ImmutableMap.copyOf(requireNonNull(expressionTypes, "expressionTypes is null"));
        verify((expressionTypes.containsKey(NodeRef.of(expression))));
        this.optimize = optimize;
        this.functionInvoker = new InterpretedFunctionInvoker(metadata);
        this.typeCoercion = new TypeCoercion(metadata::getType);

        this.visitor = new Visitor();
    }

    public Type getType()
    {
        return expressionTypes.get(NodeRef.of(expression));
    }

    public Object evaluate()
    {
        checkState(!optimize, "evaluate() not allowed for optimizer");
        return visitor.process(expression, new NoPagePositionContext());
    }

    public Object evaluate(SymbolResolver inputs)
    {
        checkState(!optimize, "evaluate(int, Page) not allowed for optimizer");
        return visitor.process(expression, inputs);
    }

    public Object optimize(SymbolResolver inputs)
    {
        checkState(optimize, "evaluate(SymbolResolver) not allowed for interpreter");
        return visitor.process(expression, inputs);
    }

    @SuppressWarnings("FloatingPointEquality")
    private class Visitor
            extends AstVisitor<Object, Object>
    {
        @Override
        public Object visitFieldReference(FieldReference node, Object context)
        {
            throw new UnsupportedOperationException("Field references not supported in interpreter");
        }

        @Override
        protected Object visitDereferenceExpression(DereferenceExpression node, Object context)
        {
            Type type = type(node.getBase());
            // if there is no type for the base of Dereference, it must be QualifiedName
            if (type == null) {
                return node;
            }

            Object base = process(node.getBase(), context);
            // if the base part is evaluated to be null, the dereference expression should also be null
            if (base == null) {
                return null;
            }

            if (hasUnresolvedValue(base)) {
                return new DereferenceExpression(toExpression(base, type), node.getField());
            }

            RowType rowType = (RowType) type;
            Block row = (Block) base;
            Type returnType = type(node);
            String fieldName = node.getField().getValue();
            List<Field> fields = rowType.getFields();
            int index = -1;
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                if (field.getName().isPresent() && field.getName().get().equalsIgnoreCase(fieldName)) {
                    checkArgument(index < 0, "Ambiguous field %s in type %s", field, rowType.getDisplayName());
                    index = i;
                }
            }

            checkState(index >= 0, "could not find field name: %s", node.getField());
            return readNativeValue(returnType, row, index);
        }

        @Override
        protected Object visitIdentifier(Identifier node, Object context)
        {
            // Identifier only exists before planning.
            // ExpressionInterpreter should only be invoked after planning.
            // As a result, this method should be unreachable.
            // However, RelationPlanner.visitUnnest and visitValues invokes evaluateConstantExpression.
            return ((SymbolResolver) context).getValue(new Symbol(node.getValue()));
        }

        @Override
        protected Object visitParameter(Parameter node, Object context)
        {
            return node;
        }

        @Override
        protected Object visitSymbolReference(SymbolReference node, Object context)
        {
            return ((SymbolResolver) context).getValue(Symbol.from(node));
        }

        @Override
        protected Object visitLiteral(Literal node, Object context)
        {
            return LiteralInterpreter.evaluate(metadata, session, node);
        }

        @Override
        protected Object visitIsNullPredicate(IsNullPredicate node, Object context)
        {
            Object value = process(node.getValue(), context);

            if (value instanceof Expression) {
                return new IsNullPredicate(toExpression(value, type(node.getValue())));
            }

            return value == null;
        }

        @Override
        protected Object visitIsNotNullPredicate(IsNotNullPredicate node, Object context)
        {
            Object value = process(node.getValue(), context);

            if (value instanceof Expression) {
                return new IsNotNullPredicate(toExpression(value, type(node.getValue())));
            }

            return value != null;
        }

        @Override
        protected Object visitSearchedCaseExpression(SearchedCaseExpression node, Object context)
        {
            Object defaultResult = processWithExceptionHandling(node.getDefaultValue().orElse(null), context);

            List<WhenClause> whenClauses = new ArrayList<>();
            for (WhenClause whenClause : node.getWhenClauses()) {
                Object whenOperand = processWithExceptionHandling(whenClause.getOperand(), context);
                Object result = processWithExceptionHandling(whenClause.getResult(), context);

                if (whenOperand instanceof Expression) {
                    // cannot fully evaluate, add updated whenClause
                    whenClauses.add(new WhenClause(
                            toExpression(whenOperand, type(whenClause.getOperand())),
                            toExpression(result, type(whenClause.getResult()))));
                }
                else if (Boolean.TRUE.equals(whenOperand)) {
                    // condition is true, use this as defaultResult
                    defaultResult = result;
                    break;
                }
            }

            if (whenClauses.isEmpty()) {
                return defaultResult;
            }

            Expression resultExpression = (defaultResult == null) ? null : toExpression(defaultResult, type(node));
            return new SearchedCaseExpression(whenClauses, Optional.ofNullable(resultExpression));
        }

        @Override
        protected Object visitIfExpression(IfExpression node, Object context)
        {
            Object trueValue = processWithExceptionHandling(node.getTrueValue(), context);
            Object falseValue = processWithExceptionHandling(node.getFalseValue().orElse(null), context);
            Object condition = processWithExceptionHandling(node.getCondition(), context);

            if (condition instanceof Expression) {
                Expression falseValueExpression = (falseValue == null) ? null : toExpression(falseValue, type(node.getFalseValue().get()));
                return new IfExpression(
                        toExpression(condition, type(node.getCondition())),
                        toExpression(trueValue, type(node.getTrueValue())),
                        falseValueExpression);
            }
            else if (Boolean.TRUE.equals(condition)) {
                return trueValue;
            }
            else {
                return falseValue;
            }
        }

        private Object processWithExceptionHandling(Expression expression, Object context)
        {
            if (expression == null) {
                return null;
            }

            try {
                return process(expression, context);
            }
            catch (RuntimeException e) {
                // HACK
                // Certain operations like 0 / 0 or likeExpression may throw exceptions.
                // Wrap them a FunctionCall that will throw the exception if the expression is actually executed
                return createFailureFunction(e, type(expression), ExpressionInterpreter.this.metadata);
            }
        }

        @Override
        protected Object visitSimpleCaseExpression(SimpleCaseExpression node, Object context)
        {
            Object operand = processWithExceptionHandling(node.getOperand(), context);
            Type operandType = type(node.getOperand());

            // evaluate defaultClause
            Expression defaultClause = node.getDefaultValue().orElse(null);
            Object defaultResult = processWithExceptionHandling(defaultClause, context);

            // if operand is null, return defaultValue
            if (operand == null) {
                return defaultResult;
            }

            List<WhenClause> whenClauses = new ArrayList<>();
            for (WhenClause whenClause : node.getWhenClauses()) {
                Object whenOperand = processWithExceptionHandling(whenClause.getOperand(), context);
                Object result = processWithExceptionHandling(whenClause.getResult(), context);

                if (whenOperand instanceof Expression || operand instanceof Expression) {
                    // cannot fully evaluate, add updated whenClause
                    whenClauses.add(new WhenClause(
                            toExpression(whenOperand, type(whenClause.getOperand())),
                            toExpression(result, type(whenClause.getResult()))));
                }
                else if (whenOperand != null && isEqual(operand, operandType, whenOperand, type(whenClause.getOperand()))) {
                    // condition is true, use this as defaultResult
                    defaultResult = result;
                    break;
                }
            }

            if (whenClauses.isEmpty()) {
                return defaultResult;
            }

            Expression defaultExpression = (defaultResult == null) ? null : toExpression(defaultResult, type(node));
            return new SimpleCaseExpression(toExpression(operand, type(node.getOperand())), whenClauses, Optional.ofNullable(defaultExpression));
        }

        private boolean isEqual(Object operand1, Type type1, Object operand2, Type type2)
        {
            return Boolean.TRUE.equals(invokeOperator(OperatorType.EQUAL, ImmutableList.of(type1, type2), ImmutableList.of(operand1, operand2)));
        }

        private Type type(Expression expression)
        {
            return expressionTypes.get(NodeRef.of(expression));
        }

        @Override
        protected Object visitCoalesceExpression(CoalesceExpression node, Object context)
        {
            Type type = type(node);
            List<Object> values = node.getOperands().stream()
                    .map(value -> processWithExceptionHandling(value, context))
                    .filter(Objects::nonNull)
                    .flatMap(expression -> {
                        if (expression instanceof CoalesceExpression) {
                            return ((CoalesceExpression) expression).getOperands().stream();
                        }
                        return Stream.of(expression);
                    })
                    .collect(Collectors.toList());

            if ((!values.isEmpty() && !(values.get(0) instanceof Expression)) || values.size() == 1) {
                return values.get(0);
            }
            ImmutableList.Builder<Expression> operandsBuilder = ImmutableList.builder();
            Set<Expression> visitedExpression = new HashSet<>();
            for (Object value : values) {
                Expression expression = toExpression(value, type);
                if (!isDeterministic(expression) || visitedExpression.add(expression)) {
                    operandsBuilder.add(expression);
                }
                // TODO: Replace this logic with an anlyzer which specifies whether it evaluates to null
                if (expression instanceof Literal && !(expression instanceof NullLiteral)) {
                    break;
                }
            }
            List<Expression> expressions = operandsBuilder.build();

            if (expressions.isEmpty()) {
                return null;
            }

            if (expressions.size() == 1) {
                return getOnlyElement(expressions);
            }
            return new CoalesceExpression(expressions);
        }

        @Override
        protected Object visitInPredicate(InPredicate node, Object context)
        {
            Object value = process(node.getValue(), context);

            Expression valueListExpression = node.getValueList();
            if (!(valueListExpression instanceof InListExpression)) {
                if (!optimize) {
                    throw new UnsupportedOperationException("IN predicate value list type not yet implemented: " + valueListExpression.getClass().getName());
                }
                return node;
            }
            InListExpression valueList = (InListExpression) valueListExpression;
            verify(!valueList.getValues().isEmpty()); // `NULL IN ()` would be false, but is not possible
            if (value == null) {
                return null;
            }

            Set<?> set = inListCache.get(valueList);

            // We use the presence of the node in the map to indicate that we've already done
            // the analysis below. If the value is null, it means that we can't apply the HashSet
            // optimization
            if (!inListCache.containsKey(valueList)) {
                if (valueList.getValues().stream().allMatch(Literal.class::isInstance) &&
                        valueList.getValues().stream().noneMatch(NullLiteral.class::isInstance)) {
                    Set<Object> objectSet = valueList.getValues().stream().map(expression -> process(expression, context)).collect(Collectors.toSet());
                    set = FastutilSetHelper.toFastutilHashSet(objectSet, type(node.getValue()), metadata);
                }
                inListCache.put(valueList, set);
            }

            if (set != null && !(value instanceof Expression)) {
                return set.contains(value);
            }

            boolean hasUnresolvedValue = false;
            if (value instanceof Expression) {
                hasUnresolvedValue = true;
            }

            boolean hasNullValue = false;
            boolean found = false;
            List<Object> values = new ArrayList<>(valueList.getValues().size());
            List<Type> types = new ArrayList<>(valueList.getValues().size());
            for (Expression expression : valueList.getValues()) {
                Object inValue = process(expression, context);
                if (value instanceof Expression || inValue instanceof Expression) {
                    hasUnresolvedValue = true;
                    values.add(inValue);
                    types.add(type(expression));
                    continue;
                }

                if (inValue == null) {
                    hasNullValue = true;
                }
                else {
                    Boolean result = (Boolean) invokeOperator(OperatorType.EQUAL, types(node.getValue(), expression), ImmutableList.of(value, inValue));
                    if (result == null) {
                        hasNullValue = true;
                    }
                    else if (!found && result) {
                        // in does not short-circuit so we must evaluate all value in the list
                        found = true;
                    }
                }
            }
            if (found) {
                return true;
            }

            if (hasUnresolvedValue) {
                Type type = type(node.getValue());
                List<Expression> expressionValues = toExpressions(values, types);
                List<Expression> simplifiedExpressionValues = Stream.concat(
                        expressionValues.stream()
                                .filter(DeterminismEvaluator::isDeterministic)
                                .distinct(),
                        expressionValues.stream()
                                .filter((expression -> !isDeterministic(expression))))
                        .collect(toImmutableList());

                if (simplifiedExpressionValues.size() == 1) {
                    return new ComparisonExpression(ComparisonExpression.Operator.EQUAL, toExpression(value, type), simplifiedExpressionValues.get(0));
                }

                return new InPredicate(toExpression(value, type), new InListExpression(simplifiedExpressionValues));
            }
            if (hasNullValue) {
                return null;
            }
            return false;
        }

        @Override
        protected Object visitExists(ExistsPredicate node, Object context)
        {
            if (!optimize) {
                throw new UnsupportedOperationException("Exists subquery not yet implemented");
            }
            return node;
        }

        @Override
        protected Object visitSubqueryExpression(SubqueryExpression node, Object context)
        {
            if (!optimize) {
                throw new UnsupportedOperationException("Subquery not yet implemented");
            }
            return node;
        }

        @Override
        protected Object visitArithmeticUnary(ArithmeticUnaryExpression node, Object context)
        {
            Object value = process(node.getValue(), context);
            if (value == null) {
                return null;
            }
            if (value instanceof Expression) {
                return new ArithmeticUnaryExpression(node.getSign(), toExpression(value, type(node.getValue())));
            }

            switch (node.getSign()) {
                case PLUS:
                    return value;
                case MINUS:
                    Signature operatorSignature = metadata.resolveOperator(OperatorType.NEGATION, types(node.getValue()));
                    MethodHandle handle = metadata.getScalarFunctionImplementation(operatorSignature).getMethodHandle();

                    if (handle.type().parameterCount() > 0 && handle.type().parameterType(0) == ConnectorSession.class) {
                        handle = handle.bindTo(session);
                    }
                    try {
                        return handle.invokeWithArguments(value);
                    }
                    catch (Throwable throwable) {
                        throwIfInstanceOf(throwable, RuntimeException.class);
                        throwIfInstanceOf(throwable, Error.class);
                        throw new RuntimeException(throwable.getMessage(), throwable);
                    }
            }

            throw new UnsupportedOperationException("Unsupported unary operator: " + node.getSign());
        }

        @Override
        protected Object visitArithmeticBinary(ArithmeticBinaryExpression node, Object context)
        {
            Object left = process(node.getLeft(), context);
            if (left == null) {
                return null;
            }
            Object right = process(node.getRight(), context);
            if (right == null) {
                return null;
            }

            if (hasUnresolvedValue(left, right)) {
                return new ArithmeticBinaryExpression(node.getOperator(), toExpression(left, type(node.getLeft())), toExpression(right, type(node.getRight())));
            }

            return invokeOperator(OperatorType.valueOf(node.getOperator().name()), types(node.getLeft(), node.getRight()), ImmutableList.of(left, right));
        }

        @Override
        protected Object visitComparisonExpression(ComparisonExpression node, Object context)
        {
            ComparisonExpression.Operator operator = node.getOperator();

            if (operator == ComparisonExpression.Operator.IS_DISTINCT_FROM) {
                Object left = process(node.getLeft(), context);
                Object right = process(node.getRight(), context);

                if (left == null && right instanceof Expression) {
                    return new IsNotNullPredicate((Expression) right);
                }

                if (right == null && left instanceof Expression) {
                    return new IsNotNullPredicate((Expression) left);
                }

                if (left instanceof Expression || right instanceof Expression) {
                    return new ComparisonExpression(operator, toExpression(left, type(node.getLeft())), toExpression(right, type(node.getRight())));
                }

                return invokeOperator(OperatorType.valueOf(operator.name()), types(node.getLeft(), node.getRight()), Arrays.asList(left, right));
            }

            Object left = process(node.getLeft(), context);
            if (left == null) {
                return null;
            }

            Object right = process(node.getRight(), context);
            if (right == null) {
                return null;
            }

            if (left instanceof Expression || right instanceof Expression) {
                return new ComparisonExpression(operator, toExpression(left, type(node.getLeft())), toExpression(right, type(node.getRight())));
            }

            return invokeOperator(OperatorType.valueOf(operator.name()), types(node.getLeft(), node.getRight()), ImmutableList.of(left, right));
        }

        @Override
        protected Object visitBetweenPredicate(BetweenPredicate node, Object context)
        {
            Object value = process(node.getValue(), context);
            if (value == null) {
                return null;
            }
            Object min = process(node.getMin(), context);
            Object max = process(node.getMax(), context);

            if (value instanceof Expression || min instanceof Expression || max instanceof Expression) {
                return new BetweenPredicate(
                        toExpression(value, type(node.getValue())),
                        toExpression(min, type(node.getMin())),
                        toExpression(max, type(node.getMax())));
            }

            Boolean greaterOrEqualToMin = null;
            if (min != null) {
                greaterOrEqualToMin = (Boolean) invokeOperator(OperatorType.GREATER_THAN_OR_EQUAL, types(node.getValue(), node.getMin()), ImmutableList.of(value, min));
            }
            Boolean lessThanOrEqualToMax = null;
            if (max != null) {
                lessThanOrEqualToMax = (Boolean) invokeOperator(OperatorType.LESS_THAN_OR_EQUAL, types(node.getValue(), node.getMax()), ImmutableList.of(value, max));
            }

            if (greaterOrEqualToMin == null) {
                return Objects.equals(lessThanOrEqualToMax, Boolean.FALSE) ? false : null;
            }
            if (lessThanOrEqualToMax == null) {
                return Objects.equals(greaterOrEqualToMin, Boolean.FALSE) ? false : null;
            }
            return greaterOrEqualToMin && lessThanOrEqualToMax;
        }

        @Override
        protected Object visitNullIfExpression(NullIfExpression node, Object context)
        {
            Object first = process(node.getFirst(), context);
            if (first == null) {
                return null;
            }
            Object second = process(node.getSecond(), context);
            if (second == null) {
                return first;
            }

            Type firstType = type(node.getFirst());
            Type secondType = type(node.getSecond());

            if (hasUnresolvedValue(first, second)) {
                return new NullIfExpression(toExpression(first, firstType), toExpression(second, secondType));
            }

            Type commonType = typeCoercion.getCommonSuperType(firstType, secondType).get();

            Signature firstCast = metadata.getCoercion(firstType, commonType);
            Signature secondCast = metadata.getCoercion(secondType, commonType);

            // cast(first as <common type>) == cast(second as <common type>)
            boolean equal = Boolean.TRUE.equals(invokeOperator(
                    OperatorType.EQUAL,
                    ImmutableList.of(commonType, commonType),
                    ImmutableList.of(
                            functionInvoker.invoke(firstCast, session, ImmutableList.of(first)),
                            functionInvoker.invoke(secondCast, session, ImmutableList.of(second)))));

            if (equal) {
                return null;
            }
            else {
                return first;
            }
        }

        @Override
        protected Object visitNotExpression(NotExpression node, Object context)
        {
            Object value = process(node.getValue(), context);
            if (value == null) {
                return null;
            }

            if (value instanceof Expression) {
                return new NotExpression(toExpression(value, type(node.getValue())));
            }

            return !(Boolean) value;
        }

        @Override
        protected Object visitLogicalBinaryExpression(LogicalBinaryExpression node, Object context)
        {
            Object left = process(node.getLeft(), context);
            Object right;

            switch (node.getOperator()) {
                case AND: {
                    if (Boolean.FALSE.equals(left)) {
                        return false;
                    }

                    right = process(node.getRight(), context);

                    if (Boolean.FALSE.equals(left) || Boolean.TRUE.equals(right)) {
                        return left;
                    }

                    if (Boolean.FALSE.equals(right) || Boolean.TRUE.equals(left)) {
                        return right;
                    }
                    break;
                }
                case OR: {
                    if (Boolean.TRUE.equals(left)) {
                        return true;
                    }

                    right = process(node.getRight(), context);

                    if (Boolean.TRUE.equals(left) || Boolean.FALSE.equals(right)) {
                        return left;
                    }

                    if (Boolean.TRUE.equals(right) || Boolean.FALSE.equals(left)) {
                        return right;
                    }
                    break;
                }
                default:
                    throw new IllegalStateException("Unknown LogicalBinaryExpression#Type");
            }

            if (left == null && right == null) {
                return null;
            }

            return new LogicalBinaryExpression(node.getOperator(),
                    toExpression(left, type(node.getLeft())),
                    toExpression(right, type(node.getRight())));
        }

        @Override
        protected Object visitBooleanLiteral(BooleanLiteral node, Object context)
        {
            return node.equals(BooleanLiteral.TRUE_LITERAL);
        }

        @Override
        protected Object visitFunctionCall(FunctionCall node, Object context)
        {
            List<Type> argumentTypes = new ArrayList<>();
            List<Object> argumentValues = new ArrayList<>();
            for (Expression expression : node.getArguments()) {
                Object value = process(expression, context);
                Type type = type(expression);
                argumentValues.add(value);
                argumentTypes.add(type);
            }
            Signature functionSignature = metadata.resolveFunction(node.getName(), fromTypes(argumentTypes));
            ScalarFunctionImplementation function = metadata.getScalarFunctionImplementation(functionSignature);
            for (int i = 0; i < argumentValues.size(); i++) {
                Object value = argumentValues.get(i);
                if (value == null && function.getArgumentProperty(i).getNullConvention() == RETURN_NULL_ON_NULL) {
                    return null;
                }
            }

            // do not optimize non-deterministic functions
            if (optimize && (!function.isDeterministic() || hasUnresolvedValue(argumentValues) || node.getName().equals(QualifiedName.of("fail")))) {
                verify(!node.isDistinct(), "window does not support distinct");
                verify(!node.getOrderBy().isPresent(), "window does not support order by");
                verify(!node.getFilter().isPresent(), "window does not support filter");
                return new FunctionCallBuilder(metadata)
                        .setName(node.getName())
                        .setWindow(node.getWindow())
                        .setArguments(argumentTypes, toExpressions(argumentValues, argumentTypes))
                        .build();
            }
            return functionInvoker.invoke(functionSignature, session, argumentValues);
        }

        @Override
        protected Object visitLambdaExpression(LambdaExpression node, Object context)
        {
            if (optimize) {
                // TODO: enable optimization related to lambda expression
                // A mechanism to convert function type back into lambda expression need to exist to enable optimization
                return node;
            }

            Expression body = node.getBody();
            List<String> argumentNames = node.getArguments().stream()
                    .map(LambdaArgumentDeclaration::getName)
                    .map(Identifier::getValue)
                    .collect(toImmutableList());
            FunctionType functionType = (FunctionType) expressionTypes.get(NodeRef.<Expression>of(node));
            checkArgument(argumentNames.size() == functionType.getArgumentTypes().size());

            return generateVarArgsToMapAdapter(
                    Primitives.wrap(functionType.getReturnType().getJavaType()),
                    functionType.getArgumentTypes().stream()
                            .map(Type::getJavaType)
                            .map(Primitives::wrap)
                            .collect(toImmutableList()),
                    argumentNames,
                    map -> process(body, new LambdaSymbolResolver(map)));
        }

        @Override
        protected Object visitBindExpression(BindExpression node, Object context)
        {
            List<Object> values = node.getValues().stream()
                    .map(value -> process(value, context))
                    .collect(toImmutableList());
            Object function = process(node.getFunction(), context);

            if (hasUnresolvedValue(values) || hasUnresolvedValue(function)) {
                ImmutableList.Builder<Expression> builder = ImmutableList.builder();
                for (int i = 0; i < values.size(); i++) {
                    builder.add(toExpression(values.get(i), type(node.getValues().get(i))));
                }

                return new BindExpression(
                        builder.build(),
                        toExpression(function, type(node.getFunction())));
            }

            return MethodHandles.insertArguments((MethodHandle) function, 0, values.toArray());
        }

        @Override
        protected Object visitLikePredicate(LikePredicate node, Object context)
        {
            Object value = process(node.getValue(), context);

            if (value == null) {
                return null;
            }

            if (value instanceof Slice &&
                    node.getPattern() instanceof StringLiteral &&
                    (!node.getEscape().isPresent() || node.getEscape().get() instanceof StringLiteral)) {
                // fast path when we know the pattern and escape are constant
                return evaluateLikePredicate(node, (Slice) value, getConstantPattern(node));
            }

            Object pattern = process(node.getPattern(), context);

            if (pattern == null) {
                return null;
            }

            Object escape = null;
            if (node.getEscape().isPresent()) {
                escape = process(node.getEscape().get(), context);

                if (escape == null) {
                    return null;
                }
            }

            if (value instanceof Slice &&
                    pattern instanceof Slice &&
                    (escape == null || escape instanceof Slice)) {
                Regex regex;
                if (escape == null) {
                    regex = LikeFunctions.likePattern((Slice) pattern);
                }
                else {
                    regex = LikeFunctions.likePattern((Slice) pattern, (Slice) escape);
                }

                return evaluateLikePredicate(node, (Slice) value, regex);
            }

            // if pattern is a constant without % or _ replace with a comparison
            if (pattern instanceof Slice && (escape == null || escape instanceof Slice) && !isLikePattern((Slice) pattern, (Slice) escape)) {
                Slice unescapedPattern = unescapeLiteralLikePattern((Slice) pattern, (Slice) escape);
                Type valueType = type(node.getValue());
                Type patternType = createVarcharType(unescapedPattern.length());
                Optional<Type> commonSuperType = typeCoercion.getCommonSuperType(valueType, patternType);
                checkArgument(commonSuperType.isPresent(), "Missing super type when optimizing %s", node);
                Expression valueExpression = toExpression(value, valueType);
                Expression patternExpression = toExpression(unescapedPattern, patternType);
                Type superType = commonSuperType.get();
                if (!valueType.equals(superType)) {
                    valueExpression = new Cast(valueExpression, superType.getTypeSignature().toString(), false, typeCoercion.isTypeOnlyCoercion(valueType, superType));
                }
                if (!patternType.equals(superType)) {
                    patternExpression = new Cast(patternExpression, superType.getTypeSignature().toString(), false, typeCoercion.isTypeOnlyCoercion(patternType, superType));
                }
                return new ComparisonExpression(ComparisonExpression.Operator.EQUAL, valueExpression, patternExpression);
            }

            Optional<Expression> optimizedEscape = Optional.empty();
            if (node.getEscape().isPresent()) {
                optimizedEscape = Optional.of(toExpression(escape, type(node.getEscape().get())));
            }

            return new LikePredicate(
                    toExpression(value, type(node.getValue())),
                    toExpression(pattern, type(node.getPattern())),
                    optimizedEscape);
        }

        private boolean evaluateLikePredicate(LikePredicate node, Slice value, Regex regex)
        {
            if (type(node.getValue()) instanceof VarcharType) {
                return LikeFunctions.likeVarchar(value, regex);
            }

            Type type = type(node.getValue());
            checkState(type instanceof CharType, "LIKE value is neither VARCHAR or CHAR");
            return LikeFunctions.likeChar((long) ((CharType) type).getLength(), value, regex);
        }

        private Regex getConstantPattern(LikePredicate node)
        {
            Regex result = likePatternCache.get(node);

            if (result == null) {
                StringLiteral pattern = (StringLiteral) node.getPattern();

                if (node.getEscape().isPresent()) {
                    Slice escape = ((StringLiteral) node.getEscape().get()).getSlice();
                    result = LikeFunctions.likePattern(pattern.getSlice(), escape);
                }
                else {
                    result = LikeFunctions.likePattern(pattern.getSlice());
                }

                likePatternCache.put(node, result);
            }

            return result;
        }

        @Override
        public Object visitCast(Cast node, Object context)
        {
            Object value = process(node.getExpression(), context);
            Type targetType = metadata.fromSqlType(node.getType());
            Type sourceType = type(node.getExpression());
            if (value instanceof Expression) {
                if (targetType.equals(sourceType)) {
                    return value;
                }

                return new Cast((Expression) value, node.getType(), node.isSafe(), node.isTypeOnly());
            }

            if (node.isTypeOnly()) {
                return value;
            }

            // hack!!! don't optimize CASTs for types that cannot be represented in the SQL AST
            // TODO: this will not be an issue when we migrate to RowExpression tree for this, which allows arbitrary literals.
            if (optimize && !isSupportedLiteralType(type(node))) {
                return new Cast(toExpression(value, sourceType), node.getType(), node.isSafe(), node.isTypeOnly());
            }

            if (value == null) {
                return null;
            }

            Signature operator = metadata.getCoercion(sourceType, targetType);

            try {
                return functionInvoker.invoke(operator, session, ImmutableList.of(value));
            }
            catch (RuntimeException e) {
                if (node.isSafe()) {
                    return null;
                }
                throw e;
            }
        }

        @Override
        protected Object visitArrayConstructor(ArrayConstructor node, Object context)
        {
            Type elementType = ((ArrayType) type(node)).getElementType();
            BlockBuilder arrayBlockBuilder = elementType.createBlockBuilder(null, node.getValues().size());

            for (Expression expression : node.getValues()) {
                Object value = process(expression, context);
                if (value instanceof Expression) {
                    checkCondition(node.getValues().size() <= 254, NOT_SUPPORTED, "Too many arguments for array constructor");
                    return visitFunctionCall(
                            new FunctionCallBuilder(metadata)
                                    .setName(QualifiedName.of(ArrayConstructor.ARRAY_CONSTRUCTOR))
                                    .setArguments(types(node.getValues()), node.getValues())
                                    .build(),
                            context);
                }
                writeNativeValue(elementType, arrayBlockBuilder, value);
            }

            return arrayBlockBuilder.build();
        }

        @Override
        protected Object visitCurrentUser(CurrentUser node, Object context)
        {
            return visitFunctionCall(DesugarCurrentUser.getCall(node, metadata), context);
        }

        @Override
        protected Object visitCurrentPath(CurrentPath node, Object context)
        {
            return visitFunctionCall(DesugarCurrentPath.getCall(node, metadata), context);
        }

        @Override
        protected Object visitRow(Row node, Object context)
        {
            RowType rowType = (RowType) type(node);
            List<Type> parameterTypes = rowType.getTypeParameters();
            List<Expression> arguments = node.getItems();

            int cardinality = arguments.size();
            List<Object> values = new ArrayList<>(cardinality);
            for (Expression argument : arguments) {
                values.add(process(argument, context));
            }
            if (hasUnresolvedValue(values)) {
                return new Row(toExpressions(values, parameterTypes));
            }
            else {
                BlockBuilder blockBuilder = new RowBlockBuilder(parameterTypes, null, 1);
                BlockBuilder singleRowBlockWriter = blockBuilder.beginBlockEntry();
                for (int i = 0; i < cardinality; ++i) {
                    writeNativeValue(parameterTypes.get(i), singleRowBlockWriter, values.get(i));
                }
                blockBuilder.closeEntry();
                return rowType.getObject(blockBuilder, 0);
            }
        }

        @Override
        protected Object visitSubscriptExpression(SubscriptExpression node, Object context)
        {
            Object base = process(node.getBase(), context);
            if (base == null) {
                return null;
            }
            Object index = process(node.getIndex(), context);
            if (index == null) {
                return null;
            }
            if ((index instanceof Long) && isArray(type(node.getBase()))) {
                ArraySubscriptOperator.checkArrayIndex((Long) index);
            }

            if (hasUnresolvedValue(base, index)) {
                return new SubscriptExpression(toExpression(base, type(node.getBase())), toExpression(index, type(node.getIndex())));
            }

            // Subscript on Row hasn't got a dedicated operator. It is interpreted by hand.
            if (base instanceof SingleRowBlock) {
                SingleRowBlock row = (SingleRowBlock) base;
                int position = toIntExact((long) index - 1);
                if (position < 0 || position >= row.getPositionCount()) {
                    throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "ROW index out of bounds: " + (position + 1));
                }
                Type returnType = type(node.getBase()).getTypeParameters().get(position);
                return readNativeValue(returnType, row, position);
            }

            // Subscript on Array or Map is interpreted using operator.
            return invokeOperator(OperatorType.SUBSCRIPT, types(node.getBase(), node.getIndex()), ImmutableList.of(base, index));
        }

        @Override
        protected Object visitQuantifiedComparisonExpression(QuantifiedComparisonExpression node, Object context)
        {
            if (!optimize) {
                throw new UnsupportedOperationException("QuantifiedComparison not yet implemented");
            }
            return node;
        }

        @Override
        protected Object visitExpression(Expression node, Object context)
        {
            throw new PrestoException(NOT_SUPPORTED, "not yet implemented: " + node.getClass().getName());
        }

        @Override
        protected Object visitNode(Node node, Object context)
        {
            throw new UnsupportedOperationException("Evaluator visitor can only handle Expression nodes");
        }

        private List<Type> types(Expression... expressions)
        {
            return Stream.of(expressions)
                    .map(NodeRef::of)
                    .map(expressionTypes::get)
                    .collect(toImmutableList());
        }

        private List<Type> types(List<Expression> expressions)
        {
            return expressions.stream()
                    .map(NodeRef::of)
                    .map(expressionTypes::get)
                    .collect(toImmutableList());
        }

        private boolean hasUnresolvedValue(Object... values)
        {
            return hasUnresolvedValue(ImmutableList.copyOf(values));
        }

        private boolean hasUnresolvedValue(List<Object> values)
        {
            return values.stream().anyMatch(instanceOf(Expression.class)::apply);
        }

        private Object invokeOperator(OperatorType operatorType, List<? extends Type> argumentTypes, List<Object> argumentValues)
        {
            Signature operatorSignature = metadata.resolveOperator(operatorType, argumentTypes);
            return functionInvoker.invoke(operatorSignature, session, argumentValues);
        }

        private Expression toExpression(Object base, Type type)
        {
            return literalEncoder.toExpression(base, type);
        }

        private List<Expression> toExpressions(List<Object> values, List<Type> types)
        {
            return literalEncoder.toExpressions(values, types);
        }
    }

    private interface PagePositionContext
    {
        Block getBlock(int channel);

        int getPosition(int channel);
    }

    private static class NoPagePositionContext
            implements PagePositionContext
    {
        @Override
        public Block getBlock(int channel)
        {
            throw new IllegalArgumentException("Context does not contain any blocks");
        }

        @Override
        public int getPosition(int channel)
        {
            throw new IllegalArgumentException("Context does not have a position");
        }
    }

    private static Expression createFailureFunction(RuntimeException exception, Type type, Metadata metadata)
    {
        requireNonNull(exception, "Exception is null");

        String failureInfo = JsonCodec.jsonCodec(FailureInfo.class).toJson(Failures.toFailure(exception).toFailureInfo());
        FunctionCall jsonParse = new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of("json_parse"))
                .addArgument(VARCHAR, new StringLiteral(failureInfo))
                .build();
        FunctionCall failureFunction = new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of("fail"))
                .addArgument(JSON, jsonParse)
                .build();

        return new Cast(failureFunction, type.getTypeSignature().toString());
    }

    private static boolean isArray(Type type)
    {
        return type instanceof ArrayType;
    }

    private static class LambdaSymbolResolver
            implements SymbolResolver
    {
        private final Map<String, Object> values;

        public LambdaSymbolResolver(Map<String, Object> values)
        {
            this.values = requireNonNull(values, "values is null");
        }

        @Override
        public Object getValue(Symbol symbol)
        {
            checkState(values.containsKey(symbol.getName()), "values does not contain %s", symbol);
            return values.get(symbol.getName());
        }
    }
}
