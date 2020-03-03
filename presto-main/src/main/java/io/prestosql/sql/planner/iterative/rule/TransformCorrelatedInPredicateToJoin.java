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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.Signature;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.SymbolsExtractor;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.PlanVisitor;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.tree.BooleanLiteral;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.IsNotNullPredicate;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.SearchedCaseExpression;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.WhenClause;
import io.prestosql.sql.util.AstUtils;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.matching.Pattern.nonEmpty;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.sql.ExpressionUtils.and;
import static io.prestosql.sql.ExpressionUtils.or;
import static io.prestosql.sql.planner.plan.AggregationNode.singleGroupingSet;
import static io.prestosql.sql.planner.plan.Patterns.Apply.correlation;
import static io.prestosql.sql.planner.plan.Patterns.applyNode;
import static java.util.Objects.requireNonNull;

/**
 * Replaces correlated ApplyNode with InPredicate expression with SemiJoin
 * <p>
 * Transforms:
 * <pre>
 * - Apply (output: a in B.b)
 *    - input: some plan A producing symbol a
 *    - subquery: some plan B producing symbol b, using symbols from A
 * </pre>
 * Into:
 * <pre>
 * - Project (output: CASE WHEN (countmatches > 0) THEN true WHEN (countnullmatches > 0) THEN null ELSE false END)
 *   - Aggregate (countmatches=count(*) where a, b not null; countnullmatches where a,b null but buildSideKnownNonNull is not null)
 *     grouping by (A'.*)
 *     - LeftJoin on (A and B correlation condition)
 *       - AssignUniqueId (A')
 *         - A
 * </pre>
 * <p>
 *
 * @see TransformCorrelatedScalarAggregationToJoin
 */
public class TransformCorrelatedInPredicateToJoin
        implements Rule<ApplyNode>
{
    private static final Pattern<ApplyNode> PATTERN = applyNode()
            .with(nonEmpty(correlation()));

    @Override
    public Pattern<ApplyNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ApplyNode apply, Captures captures, Context context)
    {
        Assignments subqueryAssignments = apply.getSubqueryAssignments();
        if (subqueryAssignments.size() != 1) {
            return Result.empty();
        }
        Expression assignmentExpression = getOnlyElement(subqueryAssignments.getExpressions());
        if (!(assignmentExpression instanceof InPredicate)) {
            return Result.empty();
        }

        InPredicate inPredicate = (InPredicate) assignmentExpression;
        Symbol inPredicateOutputSymbol = getOnlyElement(subqueryAssignments.getSymbols());

        return apply(apply, inPredicate, inPredicateOutputSymbol, context.getLookup(), context.getIdAllocator(), context.getSymbolAllocator());
    }

    private Result apply(
            ApplyNode apply,
            InPredicate inPredicate,
            Symbol inPredicateOutputSymbol,
            Lookup lookup,
            PlanNodeIdAllocator idAllocator,
            SymbolAllocator symbolAllocator)
    {
        Optional<Decorrelated> decorrelated = new DecorrelatingVisitor(lookup, apply.getCorrelation())
                .decorrelate(apply.getSubquery());

        if (!decorrelated.isPresent()) {
            return Result.empty();
        }

        PlanNode projection = buildInPredicateEquivalent(
                apply,
                inPredicate,
                inPredicateOutputSymbol,
                decorrelated.get(),
                idAllocator,
                symbolAllocator);

        return Result.ofPlanNode(projection);
    }

    private PlanNode buildInPredicateEquivalent(
            ApplyNode apply,
            InPredicate inPredicate,
            Symbol inPredicateOutputSymbol,
            Decorrelated decorrelated,
            PlanNodeIdAllocator idAllocator,
            SymbolAllocator symbolAllocator)
    {
        Expression correlationCondition = and(decorrelated.getCorrelatedPredicates());
        PlanNode decorrelatedBuildSource = decorrelated.getDecorrelatedNode();

        AssignUniqueId probeSide = new AssignUniqueId(
                idAllocator.getNextId(),
                apply.getInput(),
                symbolAllocator.newSymbol("unique", BIGINT));

        Symbol buildSideKnownNonNull = symbolAllocator.newSymbol("buildSideKnownNonNull", BIGINT);
        ProjectNode buildSide = new ProjectNode(
                idAllocator.getNextId(),
                decorrelatedBuildSource,
                Assignments.builder()
                        .putIdentities(decorrelatedBuildSource.getOutputSymbols())
                        .put(buildSideKnownNonNull, bigint(0))
                        .build());

        Symbol probeSideSymbol = Symbol.from(inPredicate.getValue());
        Symbol buildSideSymbol = Symbol.from(inPredicate.getValueList());

        Expression joinExpression = and(
                or(
                        new IsNullPredicate(probeSideSymbol.toSymbolReference()),
                        new ComparisonExpression(ComparisonExpression.Operator.EQUAL, probeSideSymbol.toSymbolReference(), buildSideSymbol.toSymbolReference()),
                        new IsNullPredicate(buildSideSymbol.toSymbolReference())),
                correlationCondition);

        JoinNode leftOuterJoin = leftOuterJoin(idAllocator, probeSide, buildSide, joinExpression);

        Symbol matchConditionSymbol = symbolAllocator.newSymbol("matchConditionSymbol", BOOLEAN);
        Expression matchCondition = and(
                isNotNull(probeSideSymbol),
                isNotNull(buildSideSymbol));

        Symbol nullMatchConditionSymbol = symbolAllocator.newSymbol("nullMatchConditionSymbol", BOOLEAN);
        Expression nullMatchCondition = and(
                isNotNull(buildSideKnownNonNull),
                not(matchCondition));

        ProjectNode preProjection = new ProjectNode(
                idAllocator.getNextId(),
                leftOuterJoin,
                Assignments.builder()
                        .putIdentities(leftOuterJoin.getOutputSymbols())
                        .put(matchConditionSymbol, matchCondition)
                        .put(nullMatchConditionSymbol, nullMatchCondition)
                        .build());

        Symbol countMatchesSymbol = symbolAllocator.newSymbol("countMatches", BIGINT);
        Symbol countNullMatchesSymbol = symbolAllocator.newSymbol("countNullMatches", BIGINT);

        AggregationNode aggregation = new AggregationNode(
                idAllocator.getNextId(),
                preProjection,
                ImmutableMap.<Symbol, AggregationNode.Aggregation>builder()
                        .put(countMatchesSymbol, countWithFilter(matchConditionSymbol))
                        .put(countNullMatchesSymbol, countWithFilter(nullMatchConditionSymbol))
                        .build(),
                singleGroupingSet(probeSide.getOutputSymbols()),
                ImmutableList.of(),
                AggregationNode.Step.SINGLE,
                Optional.empty(),
                Optional.empty());

        // TODO since we care only about "some count > 0", we could have specialized node instead of leftOuterJoin that does the job without materializing join results
        SearchedCaseExpression inPredicateEquivalent = new SearchedCaseExpression(
                ImmutableList.of(
                        new WhenClause(isGreaterThan(countMatchesSymbol, 0), booleanConstant(true)),
                        new WhenClause(isGreaterThan(countNullMatchesSymbol, 0), booleanConstant(null))),
                Optional.of(booleanConstant(false)));
        return new ProjectNode(
                idAllocator.getNextId(),
                aggregation,
                Assignments.builder()
                        .putIdentities(apply.getInput().getOutputSymbols())
                        .put(inPredicateOutputSymbol, inPredicateEquivalent)
                        .build());
    }

    private static JoinNode leftOuterJoin(PlanNodeIdAllocator idAllocator, AssignUniqueId probeSide, ProjectNode buildSide, Expression joinExpression)
    {
        return new JoinNode(
                idAllocator.getNextId(),
                JoinNode.Type.LEFT,
                probeSide,
                buildSide,
                ImmutableList.of(),
                ImmutableList.<Symbol>builder()
                        .addAll(probeSide.getOutputSymbols())
                        .addAll(buildSide.getOutputSymbols())
                        .build(),
                Optional.of(joinExpression),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of());
    }

    private static AggregationNode.Aggregation countWithFilter(Symbol filter)
    {
        return new AggregationNode.Aggregation(
                new Signature("count", FunctionKind.AGGREGATE, BIGINT.getTypeSignature()),
                ImmutableList.of(),
                false,
                Optional.of(filter),
                Optional.empty(),
                Optional.empty()); /* mask */
    }

    private static Expression isGreaterThan(Symbol symbol, long value)
    {
        return new ComparisonExpression(
                ComparisonExpression.Operator.GREATER_THAN,
                symbol.toSymbolReference(),
                bigint(value));
    }

    private static Expression not(Expression booleanExpression)
    {
        return new NotExpression(booleanExpression);
    }

    private static Expression isNotNull(Symbol symbol)
    {
        return new IsNotNullPredicate(symbol.toSymbolReference());
    }

    private static Expression bigint(long value)
    {
        return new Cast(new LongLiteral(String.valueOf(value)), BIGINT.toString());
    }

    private static Expression booleanConstant(@Nullable Boolean value)
    {
        if (value == null) {
            return new Cast(new NullLiteral(), BOOLEAN.toString());
        }
        return new BooleanLiteral(value.toString());
    }

    private static class DecorrelatingVisitor
            extends PlanVisitor<Optional<Decorrelated>, PlanNode>
    {
        private final Lookup lookup;
        private final Set<Symbol> correlation;

        public DecorrelatingVisitor(Lookup lookup, Iterable<Symbol> correlation)
        {
            this.lookup = requireNonNull(lookup, "lookup is null");
            this.correlation = ImmutableSet.copyOf(requireNonNull(correlation, "correlation is null"));
        }

        public Optional<Decorrelated> decorrelate(PlanNode reference)
        {
            return lookup.resolve(reference).accept(this, reference);
        }

        @Override
        public Optional<Decorrelated> visitProject(ProjectNode node, PlanNode reference)
        {
            if (isCorrelatedShallowly(node)) {
                // TODO: handle correlated projection
                return Optional.empty();
            }

            Optional<Decorrelated> result = decorrelate(node.getSource());
            return result.map(decorrelated -> {
                Assignments.Builder assignments = Assignments.builder()
                        .putAll(node.getAssignments());

                // Pull up all symbols used by a filter (except correlation)
                decorrelated.getCorrelatedPredicates().stream()
                        .flatMap(AstUtils::preOrder)
                        .filter(SymbolReference.class::isInstance)
                        .map(SymbolReference.class::cast)
                        .filter(symbolReference -> !correlation.contains(Symbol.from(symbolReference)))
                        .forEach(symbolReference -> assignments.putIdentity(Symbol.from(symbolReference)));

                return new Decorrelated(
                        decorrelated.getCorrelatedPredicates(),
                        new ProjectNode(
                                node.getId(),
                                decorrelated.getDecorrelatedNode(),
                                assignments.build()));
            });
        }

        @Override
        public Optional<Decorrelated> visitFilter(FilterNode node, PlanNode reference)
        {
            Optional<Decorrelated> result = decorrelate(node.getSource());
            return result.map(decorrelated ->
                    new Decorrelated(
                            ImmutableList.<Expression>builder()
                                    .addAll(decorrelated.getCorrelatedPredicates())
                                    // No need to retain uncorrelated conditions, predicate push down will push them back
                                    .add(node.getPredicate())
                                    .build(),
                            decorrelated.getDecorrelatedNode()));
        }

        @Override
        protected Optional<Decorrelated> visitPlan(PlanNode node, PlanNode reference)
        {
            if (isCorrelatedRecursively(node)) {
                return Optional.empty();
            }
            else {
                return Optional.of(new Decorrelated(ImmutableList.of(), reference));
            }
        }

        private boolean isCorrelatedRecursively(PlanNode node)
        {
            if (isCorrelatedShallowly(node)) {
                return true;
            }
            return node.getSources().stream()
                    .map(lookup::resolve)
                    .anyMatch(this::isCorrelatedRecursively);
        }

        private boolean isCorrelatedShallowly(PlanNode node)
        {
            return SymbolsExtractor.extractUniqueNonRecursive(node).stream().anyMatch(correlation::contains);
        }
    }

    private static class Decorrelated
    {
        private final List<Expression> correlatedPredicates;
        private final PlanNode decorrelatedNode;

        public Decorrelated(List<Expression> correlatedPredicates, PlanNode decorrelatedNode)
        {
            this.correlatedPredicates = ImmutableList.copyOf(requireNonNull(correlatedPredicates, "correlatedPredicates is null"));
            this.decorrelatedNode = requireNonNull(decorrelatedNode, "decorrelatedNode is null");
        }

        public List<Expression> getCorrelatedPredicates()
        {
            return correlatedPredicates;
        }

        public PlanNode getDecorrelatedNode()
        {
            return decorrelatedNode;
        }
    }
}
