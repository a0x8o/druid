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
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.analyzer.Analysis;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.CorrelatedJoinNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.tree.DefaultExpressionTraversalVisitor;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.ExistsPredicate;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.QuantifiedComparisonExpression;
import io.prestosql.sql.tree.QuantifiedComparisonExpression.Quantifier;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.SubqueryExpression;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.util.MorePredicates;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.sql.analyzer.SemanticExceptions.notSupportedException;
import static io.prestosql.sql.planner.ReferenceAwareExpressionNodeInliner.replaceExpression;
import static io.prestosql.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.EQUAL;
import static io.prestosql.sql.util.AstUtils.nodeContains;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

class SubqueryPlanner
{
    private final Analysis analysis;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaDeclarationToSymbolMap;
    private final Metadata metadata;
    private final Session session;

    SubqueryPlanner(
            Analysis analysis,
            SymbolAllocator symbolAllocator,
            PlanNodeIdAllocator idAllocator,
            Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaDeclarationToSymbolMap,
            Metadata metadata,
            Session session)
    {
        requireNonNull(analysis, "analysis is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");
        requireNonNull(lambdaDeclarationToSymbolMap, "lambdaDeclarationToSymbolMap is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(session, "session is null");

        this.analysis = analysis;
        this.symbolAllocator = symbolAllocator;
        this.idAllocator = idAllocator;
        this.lambdaDeclarationToSymbolMap = lambdaDeclarationToSymbolMap;
        this.metadata = metadata;
        this.session = session;
    }

    public PlanBuilder handleSubqueries(PlanBuilder builder, Collection<Expression> expressions, Node node)
    {
        for (Expression expression : expressions) {
            builder = handleSubqueries(builder, expression, node, true);
        }
        return builder;
    }

    public PlanBuilder handleUncorrelatedSubqueries(PlanBuilder builder, Collection<Expression> expressions, Node node)
    {
        for (Expression expression : expressions) {
            builder = handleSubqueries(builder, expression, node, false);
        }
        return builder;
    }

    public PlanBuilder handleSubqueries(PlanBuilder builder, Expression expression, Node node)
    {
        return handleSubqueries(builder, expression, node, true);
    }

    private PlanBuilder handleSubqueries(PlanBuilder builder, Expression expression, Node node, boolean correlationAllowed)
    {
        builder = appendInPredicateApplyNodes(builder, collectInPredicateSubqueries(expression, node), correlationAllowed, node);
        builder = appendScalarSubqueryCorrelatedJoins(builder, collectScalarSubqueries(expression, node), correlationAllowed);
        builder = appendExistsSubqueryApplyNodes(builder, collectExistsSubqueries(expression, node), correlationAllowed);
        builder = appendQuantifiedComparisonApplyNodes(builder, collectQuantifiedComparisonSubqueries(expression, node), correlationAllowed, node);
        return builder;
    }

    public Set<InPredicate> collectInPredicateSubqueries(Expression expression, Node node)
    {
        return analysis.getInPredicateSubqueries(node)
                .stream()
                .filter(inPredicate -> nodeContains(expression, inPredicate.getValueList()))
                .collect(toImmutableSet());
    }

    public Set<SubqueryExpression> collectScalarSubqueries(Expression expression, Node node)
    {
        return analysis.getScalarSubqueries(node)
                .stream()
                .filter(subquery -> nodeContains(expression, subquery))
                .collect(toImmutableSet());
    }

    public Set<ExistsPredicate> collectExistsSubqueries(Expression expression, Node node)
    {
        return analysis.getExistsSubqueries(node)
                .stream()
                .filter(subquery -> nodeContains(expression, subquery))
                .collect(toImmutableSet());
    }

    public Set<QuantifiedComparisonExpression> collectQuantifiedComparisonSubqueries(Expression expression, Node node)
    {
        return analysis.getQuantifiedComparisonSubqueries(node)
                .stream()
                .filter(quantifiedComparison -> nodeContains(expression, quantifiedComparison.getSubquery()))
                .collect(toImmutableSet());
    }

    private PlanBuilder appendInPredicateApplyNodes(PlanBuilder subPlan, Set<InPredicate> inPredicates, boolean correlationAllowed, Node node)
    {
        for (InPredicate inPredicate : inPredicates) {
            subPlan = appendInPredicateApplyNode(subPlan, inPredicate, correlationAllowed, node);
        }
        return subPlan;
    }

    private PlanBuilder appendInPredicateApplyNode(PlanBuilder subPlan, InPredicate inPredicate, boolean correlationAllowed, Node node)
    {
        if (subPlan.canTranslate(inPredicate)) {
            // given subquery is already appended
            return subPlan;
        }

        subPlan = handleSubqueries(subPlan, inPredicate.getValue(), node);

        subPlan = subPlan.appendProjections(ImmutableList.of(inPredicate.getValue()), symbolAllocator, idAllocator);

        checkState(inPredicate.getValueList() instanceof SubqueryExpression);
        SubqueryExpression valueListSubquery = (SubqueryExpression) inPredicate.getValueList();
        SubqueryExpression uncoercedValueListSubquery = uncoercedSubquery(valueListSubquery);
        PlanBuilder subqueryPlan = createPlanBuilder(uncoercedValueListSubquery);

        subqueryPlan = subqueryPlan.appendProjections(ImmutableList.of(valueListSubquery), symbolAllocator, idAllocator);
        SymbolReference valueList = subqueryPlan.translate(valueListSubquery).toSymbolReference();

        Symbol rewrittenValue = subPlan.translate(inPredicate.getValue());
        InPredicate inPredicateSubqueryExpression = new InPredicate(rewrittenValue.toSymbolReference(), valueList);
        Symbol inPredicateSubquerySymbol = symbolAllocator.newSymbol(inPredicateSubqueryExpression, BOOLEAN);

        subPlan.getTranslations().put(inPredicate, inPredicateSubquerySymbol);

        return appendApplyNode(subPlan, inPredicate, subqueryPlan.getRoot(), Assignments.of(inPredicateSubquerySymbol, inPredicateSubqueryExpression), correlationAllowed);
    }

    private PlanBuilder appendScalarSubqueryCorrelatedJoins(PlanBuilder builder, Set<SubqueryExpression> scalarSubqueries, boolean correlationAllowed)
    {
        for (SubqueryExpression scalarSubquery : scalarSubqueries) {
            builder = appendScalarSubqueryApplyNode(builder, scalarSubquery, correlationAllowed);
        }
        return builder;
    }

    private PlanBuilder appendScalarSubqueryApplyNode(PlanBuilder subPlan, SubqueryExpression scalarSubquery, boolean correlationAllowed)
    {
        if (subPlan.canTranslate(scalarSubquery)) {
            // given subquery is already appended
            return subPlan;
        }

        List<Expression> coercions = coercionsFor(scalarSubquery);

        SubqueryExpression uncoercedScalarSubquery = uncoercedSubquery(scalarSubquery);
        PlanBuilder subqueryPlan = createPlanBuilder(uncoercedScalarSubquery);
        subqueryPlan = subqueryPlan.withNewRoot(new EnforceSingleRowNode(idAllocator.getNextId(), subqueryPlan.getRoot()));
        subqueryPlan = subqueryPlan.appendProjections(coercions, symbolAllocator, idAllocator);

        Symbol uncoercedScalarSubquerySymbol = subqueryPlan.translate(uncoercedScalarSubquery);
        subPlan.getTranslations().put(uncoercedScalarSubquery, uncoercedScalarSubquerySymbol);

        for (Expression coercion : coercions) {
            Symbol coercionSymbol = subqueryPlan.translate(coercion);
            subPlan.getTranslations().put(coercion, coercionSymbol);
        }

        // The subquery's EnforceSingleRowNode always produces a row, so the join is effectively INNER
        return appendCorrelatedJoin(subPlan, subqueryPlan, scalarSubquery.getQuery(), correlationAllowed, CorrelatedJoinNode.Type.INNER, TRUE_LITERAL);
    }

    public PlanBuilder appendCorrelatedJoin(PlanBuilder subPlan, PlanBuilder subqueryPlan, Query query, boolean correlationAllowed, CorrelatedJoinNode.Type type, Expression filterCondition)
    {
        PlanNode subqueryNode = subqueryPlan.getRoot();
        Map<NodeRef<Expression>, Expression> correlation = extractCorrelation(subPlan, subqueryNode);
        if (!correlationAllowed && !correlation.isEmpty()) {
            throw notSupportedException(query, "Correlated subquery in given context");
        }
        subqueryNode = replaceExpressionsWithSymbols(subqueryNode, correlation);

        return new PlanBuilder(
                subPlan.copyTranslations(),
                new CorrelatedJoinNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        subqueryNode,
                        ImmutableList.copyOf(SymbolsExtractor.extractUnique(correlation.values())),
                        type,
                        filterCondition,
                        query));
    }

    private PlanBuilder appendExistsSubqueryApplyNodes(PlanBuilder builder, Set<ExistsPredicate> existsPredicates, boolean correlationAllowed)
    {
        for (ExistsPredicate existsPredicate : existsPredicates) {
            builder = appendExistSubqueryApplyNode(builder, existsPredicate, correlationAllowed);
        }
        return builder;
    }

    /**
     * Exists is modeled as:
     * <pre>
     *     - Project($0 > 0)
     *       - Aggregation(COUNT(*))
     *         - Limit(1)
     *           -- subquery
     * </pre>
     */
    private PlanBuilder appendExistSubqueryApplyNode(PlanBuilder subPlan, ExistsPredicate existsPredicate, boolean correlationAllowed)
    {
        if (subPlan.canTranslate(existsPredicate)) {
            // given subquery is already appended
            return subPlan;
        }

        PlanBuilder subqueryPlan = createPlanBuilder(existsPredicate.getSubquery());

        PlanNode subqueryPlanRoot = subqueryPlan.getRoot();
        if (isAggregationWithEmptyGroupBy(subqueryPlanRoot)) {
            subPlan.getTranslations().put(existsPredicate, TRUE_LITERAL);
            return subPlan;
        }

        // add an explicit projection that removes all columns
        PlanNode subqueryNode = new ProjectNode(idAllocator.getNextId(), subqueryPlan.getRoot(), Assignments.of());

        Symbol exists = symbolAllocator.newSymbol("exists", BOOLEAN);
        subPlan.getTranslations().put(existsPredicate, exists);
        ExistsPredicate rewrittenExistsPredicate = new ExistsPredicate(TRUE_LITERAL);
        return appendApplyNode(
                subPlan,
                existsPredicate.getSubquery(),
                subqueryNode,
                Assignments.of(exists, rewrittenExistsPredicate),
                correlationAllowed);
    }

    private PlanBuilder appendQuantifiedComparisonApplyNodes(PlanBuilder subPlan, Set<QuantifiedComparisonExpression> quantifiedComparisons, boolean correlationAllowed, Node node)
    {
        for (QuantifiedComparisonExpression quantifiedComparison : quantifiedComparisons) {
            subPlan = appendQuantifiedComparisonApplyNode(subPlan, quantifiedComparison, correlationAllowed, node);
        }
        return subPlan;
    }

    private PlanBuilder appendQuantifiedComparisonApplyNode(PlanBuilder subPlan, QuantifiedComparisonExpression quantifiedComparison, boolean correlationAllowed, Node node)
    {
        if (subPlan.canTranslate(quantifiedComparison)) {
            // given subquery is already appended
            return subPlan;
        }
        switch (quantifiedComparison.getOperator()) {
            case EQUAL:
                switch (quantifiedComparison.getQuantifier()) {
                    case ALL:
                        return planQuantifiedApplyNode(subPlan, quantifiedComparison, correlationAllowed);
                    case ANY:
                    case SOME:
                        // A = ANY B <=> A IN B
                        InPredicate inPredicate = new InPredicate(quantifiedComparison.getValue(), quantifiedComparison.getSubquery());
                        subPlan = appendInPredicateApplyNode(subPlan, inPredicate, correlationAllowed, node);
                        subPlan.getTranslations().put(quantifiedComparison, subPlan.translate(inPredicate));
                        return subPlan;
                }
                break;

            case NOT_EQUAL:
                switch (quantifiedComparison.getQuantifier()) {
                    case ALL:
                        // A <> ALL B <=> !(A IN B) <=> !(A = ANY B)
                        QuantifiedComparisonExpression rewrittenAny = new QuantifiedComparisonExpression(
                                EQUAL,
                                Quantifier.ANY,
                                quantifiedComparison.getValue(),
                                quantifiedComparison.getSubquery());
                        Expression notAny = new NotExpression(rewrittenAny);
                        // "A <> ALL B" is equivalent to "NOT (A = ANY B)" so add a rewrite for the initial quantifiedComparison to notAny
                        subPlan.getTranslations().put(quantifiedComparison, subPlan.getTranslations().rewrite(notAny));
                        // now plan "A = ANY B" part by calling ourselves for rewrittenAny
                        return appendQuantifiedComparisonApplyNode(subPlan, rewrittenAny, correlationAllowed, node);
                    case ANY:
                    case SOME:
                        // A <> ANY B <=> min B <> max B || A <> min B <=> !(min B = max B && A = min B) <=> !(A = ALL B)
                        QuantifiedComparisonExpression rewrittenAll = new QuantifiedComparisonExpression(
                                EQUAL,
                                QuantifiedComparisonExpression.Quantifier.ALL,
                                quantifiedComparison.getValue(),
                                quantifiedComparison.getSubquery());
                        Expression notAll = new NotExpression(rewrittenAll);
                        // "A <> ANY B" is equivalent to "NOT (A = ALL B)" so add a rewrite for the initial quantifiedComparison to notAll
                        subPlan.getTranslations().put(quantifiedComparison, subPlan.getTranslations().rewrite(notAll));
                        // now plan "A = ALL B" part by calling ourselves for rewrittenAll
                        return appendQuantifiedComparisonApplyNode(subPlan, rewrittenAll, correlationAllowed, node);
                }
                break;

            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
                return planQuantifiedApplyNode(subPlan, quantifiedComparison, correlationAllowed);
        }
        // all cases are checked, so this exception should never be thrown
        throw new IllegalArgumentException(
                format("Unexpected quantified comparison: '%s %s'", quantifiedComparison.getOperator().getValue(), quantifiedComparison.getQuantifier()));
    }

    private PlanBuilder planQuantifiedApplyNode(PlanBuilder subPlan, QuantifiedComparisonExpression quantifiedComparison, boolean correlationAllowed)
    {
        subPlan = subPlan.appendProjections(ImmutableList.of(quantifiedComparison.getValue()), symbolAllocator, idAllocator);

        checkState(quantifiedComparison.getSubquery() instanceof SubqueryExpression);
        SubqueryExpression quantifiedSubquery = (SubqueryExpression) quantifiedComparison.getSubquery();

        SubqueryExpression uncoercedQuantifiedSubquery = uncoercedSubquery(quantifiedSubquery);
        PlanBuilder subqueryPlan = createPlanBuilder(uncoercedQuantifiedSubquery);
        subqueryPlan = subqueryPlan.appendProjections(ImmutableList.of(quantifiedSubquery), symbolAllocator, idAllocator);

        QuantifiedComparisonExpression coercedQuantifiedComparison = new QuantifiedComparisonExpression(
                quantifiedComparison.getOperator(),
                quantifiedComparison.getQuantifier(),
                subPlan.translate(quantifiedComparison.getValue()).toSymbolReference(),
                subqueryPlan.translate(quantifiedSubquery).toSymbolReference());

        Symbol coercedQuantifiedComparisonSymbol = symbolAllocator.newSymbol(coercedQuantifiedComparison, BOOLEAN);
        subPlan.getTranslations().put(quantifiedComparison, coercedQuantifiedComparisonSymbol);

        return appendApplyNode(
                subPlan,
                quantifiedComparison.getSubquery(),
                subqueryPlan.getRoot(),
                Assignments.of(coercedQuantifiedComparisonSymbol, coercedQuantifiedComparison),
                correlationAllowed);
    }

    private static boolean isAggregationWithEmptyGroupBy(PlanNode planNode)
    {
        return searchFrom(planNode)
                .recurseOnlyWhen(MorePredicates.isInstanceOfAny(ProjectNode.class))
                .where(AggregationNode.class::isInstance)
                .findFirst()
                .map(AggregationNode.class::cast)
                .map(aggregation -> aggregation.getGroupingKeys().isEmpty())
                .orElse(false);
    }

    /**
     * Implicit coercions are added when mapping an expression to symbol in {@link TranslationMap}. Coercions
     * for expression are obtained from {@link Analysis} by identity comparison. Create a copy of subquery
     * in order to get a subquery expression that does not have any coercion assigned to it {@link Analysis}.
     */
    private SubqueryExpression uncoercedSubquery(SubqueryExpression subquery)
    {
        return new SubqueryExpression(subquery.getQuery());
    }

    private List<Expression> coercionsFor(Expression expression)
    {
        return analysis.getCoercions().keySet().stream()
                .map(NodeRef::getNode)
                .filter(coercionExpression -> coercionExpression.equals(expression))
                .collect(toImmutableList());
    }

    private PlanBuilder appendApplyNode(
            PlanBuilder subPlan,
            Node subquery,
            PlanNode subqueryNode,
            Assignments subqueryAssignments,
            boolean correlationAllowed)
    {
        Map<NodeRef<Expression>, Expression> correlation = extractCorrelation(subPlan, subqueryNode);
        if (!correlationAllowed && !correlation.isEmpty()) {
            throw notSupportedException(subquery, "Correlated subquery in given context");
        }
        subPlan = subPlan.appendProjections(
                correlation.keySet().stream().map(NodeRef::getNode).collect(toImmutableSet()),
                symbolAllocator,
                idAllocator);
        subqueryNode = replaceExpressionsWithSymbols(subqueryNode, correlation);

        TranslationMap translations = subPlan.copyTranslations();
        PlanNode root = subPlan.getRoot();
        return new PlanBuilder(translations,
                new ApplyNode(idAllocator.getNextId(),
                        root,
                        subqueryNode,
                        subqueryAssignments,
                        ImmutableList.copyOf(SymbolsExtractor.extractUnique(correlation.values())),
                        subquery));
    }

    private Map<NodeRef<Expression>, Expression> extractCorrelation(PlanBuilder subPlan, PlanNode subquery)
    {
        Set<NodeRef<Expression>> missingReferences = extractOuterColumnReferences(subquery);
        ImmutableMap.Builder<NodeRef<Expression>, Expression> correlation = ImmutableMap.builder();
        for (NodeRef<Expression> missingReference : missingReferences) {
            // missing reference expression can be solved within current subPlan,
            // or within outer plans in case of multiple nesting levels of subqueries.
            tryResolveMissingExpression(subPlan, missingReference.getNode())
                    .ifPresent(symbolReference -> correlation.put(missingReference, symbolReference));
        }
        return correlation.build();
    }

    /**
     * Checks if given reference expression can be resolved within given plan.
     */
    private static Optional<Expression> tryResolveMissingExpression(PlanBuilder subPlan, Expression expression)
    {
        Expression rewritten = subPlan.rewrite(expression);
        if (rewritten != expression) {
            return Optional.of(rewritten);
        }
        return Optional.empty();
    }

    private PlanBuilder createPlanBuilder(Node node)
    {
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, session)
                .process(node, null);
        TranslationMap translations = new TranslationMap(relationPlan, analysis, lambdaDeclarationToSymbolMap);

        // Make field->symbol mapping from underlying relation plan available for translations
        // This makes it possible to rewrite FieldOrExpressions that reference fields from the FROM clause directly
        translations.setFieldMappings(relationPlan.getFieldMappings());

        if (node instanceof Expression && relationPlan.getFieldMappings().size() == 1) {
            translations.put((Expression) node, getOnlyElement(relationPlan.getFieldMappings()));
        }

        return new PlanBuilder(translations, relationPlan.getRoot());
    }

    /**
     * @return a set of reference expressions which cannot be resolved within this plan. For plan representing:
     * SELECT a, b FROM (VALUES 1) T(a). It will return a set containing single expression reference to 'b'.
     */
    private Set<NodeRef<Expression>> extractOuterColumnReferences(PlanNode planNode)
    {
        // at this point all the column references are already rewritten to SymbolReference
        // when reference expression is not rewritten that means it cannot be satisfied within given PlanNode
        // see that TranslationMap only resolves (local) fields in current scope
        return ExpressionExtractor.extractExpressions(planNode).stream()
                .flatMap(expression -> extractColumnReferences(expression, analysis.getColumnReferences()).stream())
                .collect(toImmutableSet());
    }

    private static Set<NodeRef<Expression>> extractColumnReferences(Expression expression, Set<NodeRef<Expression>> columnReferences)
    {
        ImmutableSet.Builder<NodeRef<Expression>> expressionColumnReferences = ImmutableSet.builder();
        new ColumnReferencesExtractor(columnReferences).process(expression, expressionColumnReferences);
        return expressionColumnReferences.build();
    }

    private PlanNode replaceExpressionsWithSymbols(PlanNode planNode, Map<NodeRef<Expression>, Expression> mapping)
    {
        if (mapping.isEmpty()) {
            return planNode;
        }

        return SimplePlanRewriter.rewriteWith(new ExpressionReplacer(mapping), planNode, null);
    }

    private static class ColumnReferencesExtractor
            extends DefaultExpressionTraversalVisitor<Void, ImmutableSet.Builder<NodeRef<Expression>>>
    {
        private final Set<NodeRef<Expression>> columnReferences;

        private ColumnReferencesExtractor(Set<NodeRef<Expression>> columnReferences)
        {
            this.columnReferences = requireNonNull(columnReferences, "columnReferences is null");
        }

        @Override
        protected Void visitDereferenceExpression(DereferenceExpression node, ImmutableSet.Builder<NodeRef<Expression>> builder)
        {
            if (columnReferences.contains(NodeRef.<Expression>of(node))) {
                builder.add(NodeRef.of(node));
            }
            else {
                process(node.getBase(), builder);
            }
            return null;
        }

        @Override
        protected Void visitIdentifier(Identifier node, ImmutableSet.Builder<NodeRef<Expression>> builder)
        {
            builder.add(NodeRef.of(node));
            return null;
        }
    }

    private static class ExpressionReplacer
            extends SimplePlanRewriter<Void>
    {
        private final Map<NodeRef<Expression>, Expression> mapping;

        public ExpressionReplacer(Map<NodeRef<Expression>, Expression> mapping)
        {
            this.mapping = requireNonNull(mapping, "mapping is null");
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<Void> context)
        {
            ProjectNode rewrittenNode = (ProjectNode) context.defaultRewrite(node);

            Assignments assignments = rewrittenNode.getAssignments()
                    .rewrite(expression -> replaceExpression(expression, mapping));

            return new ProjectNode(node.getId(), rewrittenNode.getSource(), assignments);
        }

        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<Void> context)
        {
            FilterNode rewrittenNode = (FilterNode) context.defaultRewrite(node);
            return new FilterNode(node.getId(), rewrittenNode.getSource(), replaceExpression(rewrittenNode.getPredicate(), mapping));
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<Void> context)
        {
            UnnestNode rewrittenNode = (UnnestNode) context.defaultRewrite(node);
            return new UnnestNode(
                    node.getId(),
                    rewrittenNode.getSource(),
                    rewrittenNode.getReplicateSymbols(),
                    rewrittenNode.getUnnestSymbols(),
                    rewrittenNode.getOrdinalitySymbol(),
                    rewrittenNode.getJoinType(),
                    rewrittenNode.getFilter().map(expression -> replaceExpression(expression, mapping)));
        }

        @Override
        public PlanNode visitValues(ValuesNode node, RewriteContext<Void> context)
        {
            ValuesNode rewrittenNode = (ValuesNode) context.defaultRewrite(node);
            List<List<Expression>> rewrittenRows = rewrittenNode.getRows().stream()
                    .map(row -> row.stream()
                            .map(column -> replaceExpression(column, mapping))
                            .collect(toImmutableList()))
                    .collect(toImmutableList());
            return new ValuesNode(
                    node.getId(),
                    rewrittenNode.getOutputSymbols(),
                    rewrittenRows);
        }
    }
}
