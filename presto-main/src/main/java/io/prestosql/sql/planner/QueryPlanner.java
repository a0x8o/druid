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
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.TableHandle;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.analyzer.Analysis;
import io.prestosql.sql.analyzer.Field;
import io.prestosql.sql.analyzer.FieldId;
import io.prestosql.sql.analyzer.RelationId;
import io.prestosql.sql.analyzer.RelationType;
import io.prestosql.sql.analyzer.Scope;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.AggregationNode.Aggregation;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.GroupIdNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.OffsetNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.planner.plan.TableWriterNode.DeleteTarget;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.planner.plan.WindowNode;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.Delete;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FetchFirst;
import io.prestosql.sql.tree.FieldReference;
import io.prestosql.sql.tree.FrameBound;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.FunctionCall.NullTreatment;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.LambdaExpression;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Offset;
import io.prestosql.sql.tree.OrderBy;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.QuerySpecification;
import io.prestosql.sql.tree.SortItem;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.Window;
import io.prestosql.sql.tree.WindowFrame;
import io.prestosql.type.TypeCoercion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Streams.stream;
import static io.prestosql.SystemSessionProperties.isSkipRedundantSort;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.sql.NodeUtils.getSortItemsFromOrderBy;
import static io.prestosql.sql.planner.OrderingScheme.sortItemToSortOrder;
import static io.prestosql.sql.planner.plan.AggregationNode.groupingSets;
import static io.prestosql.sql.planner.plan.AggregationNode.singleGroupingSet;
import static java.util.Objects.requireNonNull;

class QueryPlanner
{
    private final Analysis analysis;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaDeclarationToSymbolMap;
    private final Metadata metadata;
    private final TypeCoercion typeCoercion;
    private final Session session;
    private final SubqueryPlanner subqueryPlanner;

    QueryPlanner(
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
        this.typeCoercion = new TypeCoercion(metadata::getType);
        this.session = session;
        this.subqueryPlanner = new SubqueryPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, session);
    }

    public RelationPlan plan(Query query)
    {
        PlanBuilder builder = planQueryBody(query);

        List<Expression> orderBy = analysis.getOrderByExpressions(query);
        builder = handleSubqueries(builder, query, orderBy);
        List<Expression> outputs = analysis.getOutputExpressions(query);
        builder = handleSubqueries(builder, query, outputs);
        builder = project(builder, Iterables.concat(orderBy, outputs));

        Optional<OrderingScheme> orderingScheme = orderingScheme(builder, query.getOrderBy(), analysis.getOrderByExpressions(query));
        builder = sort(builder, orderingScheme);
        builder = offset(builder, query.getOffset());
        builder = limit(builder, query.getLimit(), orderingScheme);
        builder = project(builder, analysis.getOutputExpressions(query));

        return new RelationPlan(
                builder.getRoot(),
                analysis.getScope(query),
                computeOutputs(builder, analysis.getOutputExpressions(query)));
    }

    public RelationPlan plan(QuerySpecification node)
    {
        PlanBuilder builder = planFrom(node);
        RelationPlan fromRelationPlan = builder.getRelationPlan();

        builder = filter(builder, analysis.getWhere(node), node);
        builder = aggregate(builder, node);
        builder = filter(builder, analysis.getHaving(node), node);

        builder = window(builder, node);

        List<Expression> outputs = analysis.getOutputExpressions(node);
        builder = handleSubqueries(builder, node, outputs);

        if (node.getOrderBy().isPresent()) {
            if (!analysis.isAggregation(node)) {
                // ORDER BY requires both output and source fields to be visible if there are no aggregations
                builder = project(builder, outputs, fromRelationPlan);
                outputs = toSymbolReferences(computeOutputs(builder, outputs));
                builder = planBuilderFor(builder, analysis.getScope(node.getOrderBy().get()));
            }
            else {
                // ORDER BY requires output fields, groups and translated aggregations to be visible for queries with aggregation
                List<Expression> orderByAggregates = analysis.getOrderByAggregates(node.getOrderBy().get());
                builder = project(builder, Iterables.concat(outputs, orderByAggregates));
                outputs = toSymbolReferences(computeOutputs(builder, outputs));
                List<Expression> complexOrderByAggregatesToRemap = orderByAggregates.stream()
                        .filter(expression -> !analysis.isColumnReference(expression))
                        .collect(toImmutableList());
                builder = planBuilderFor(builder, analysis.getScope(node.getOrderBy().get()), complexOrderByAggregatesToRemap);
            }

            builder = window(builder, node.getOrderBy().get());
        }

        List<Expression> orderBy = analysis.getOrderByExpressions(node);
        builder = handleSubqueries(builder, node, orderBy);
        builder = project(builder, Iterables.concat(orderBy, outputs));

        builder = distinct(builder, node);
        Optional<OrderingScheme> orderingScheme = orderingScheme(builder, node.getOrderBy(), analysis.getOrderByExpressions(node));
        builder = sort(builder, orderingScheme);
        builder = offset(builder, node.getOffset());
        builder = limit(builder, node.getLimit(), orderingScheme);
        builder = project(builder, outputs);

        return new RelationPlan(
                builder.getRoot(),
                analysis.getScope(node),
                computeOutputs(builder, outputs));
    }

    public DeleteNode plan(Delete node)
    {
        RelationType descriptor = analysis.getOutputDescriptor(node.getTable());
        TableHandle handle = analysis.getTableHandle(node.getTable());
        ColumnHandle rowIdHandle = metadata.getUpdateRowIdColumnHandle(session, handle);
        Type rowIdType = metadata.getColumnMetadata(session, handle, rowIdHandle).getType();

        // add table columns
        ImmutableList.Builder<Symbol> outputSymbols = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, ColumnHandle> columns = ImmutableMap.builder();
        ImmutableList.Builder<Field> fields = ImmutableList.builder();
        for (Field field : descriptor.getAllFields()) {
            Symbol symbol = symbolAllocator.newSymbol(field.getName().get(), field.getType());
            outputSymbols.add(symbol);
            columns.put(symbol, analysis.getColumn(field));
            fields.add(field);
        }

        // add rowId column
        Field rowIdField = Field.newUnqualified(Optional.empty(), rowIdType);
        Symbol rowIdSymbol = symbolAllocator.newSymbol("$rowId", rowIdField.getType());
        outputSymbols.add(rowIdSymbol);
        columns.put(rowIdSymbol, rowIdHandle);
        fields.add(rowIdField);

        // create table scan
        PlanNode tableScan = TableScanNode.newInstance(idAllocator.getNextId(), handle, outputSymbols.build(), columns.build());
        Scope scope = Scope.builder().withRelationType(RelationId.anonymous(), new RelationType(fields.build())).build();
        RelationPlan relationPlan = new RelationPlan(tableScan, scope, outputSymbols.build());

        TranslationMap translations = new TranslationMap(relationPlan, analysis, lambdaDeclarationToSymbolMap);
        translations.setFieldMappings(relationPlan.getFieldMappings());

        PlanBuilder builder = new PlanBuilder(translations, relationPlan.getRoot());

        if (node.getWhere().isPresent()) {
            builder = filter(builder, node.getWhere().get(), node);
        }

        // create delete node
        Symbol rowId = builder.translate(new FieldReference(relationPlan.getDescriptor().indexOf(rowIdField)));
        List<Symbol> outputs = ImmutableList.of(
                symbolAllocator.newSymbol("partialrows", BIGINT),
                symbolAllocator.newSymbol("fragment", VARBINARY));

        return new DeleteNode(idAllocator.getNextId(), builder.getRoot(), new DeleteTarget(handle, metadata.getTableMetadata(session, handle).getTable()), rowId, outputs);
    }

    private static List<Symbol> computeOutputs(PlanBuilder builder, List<Expression> outputExpressions)
    {
        ImmutableList.Builder<Symbol> outputSymbols = ImmutableList.builder();
        for (Expression expression : outputExpressions) {
            outputSymbols.add(builder.translate(expression));
        }
        return outputSymbols.build();
    }

    private PlanBuilder planQueryBody(Query query)
    {
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, session)
                .process(query.getQueryBody(), null);

        return planBuilderFor(relationPlan);
    }

    private PlanBuilder planFrom(QuerySpecification node)
    {
        RelationPlan relationPlan;

        if (node.getFrom().isPresent()) {
            relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, session)
                    .process(node.getFrom().get(), null);
        }
        else {
            relationPlan = planImplicitTable();
        }

        return planBuilderFor(relationPlan);
    }

    private PlanBuilder planBuilderFor(PlanBuilder builder, Scope scope, Iterable<? extends Expression> expressionsToRemap)
    {
        Map<Expression, Symbol> expressionsToSymbols = symbolsForExpressions(builder, expressionsToRemap);
        PlanBuilder newBuilder = planBuilderFor(builder, scope);
        expressionsToSymbols.entrySet()
                .forEach(entry -> newBuilder.getTranslations().put(entry.getKey(), entry.getValue()));
        return newBuilder;
    }

    private PlanBuilder planBuilderFor(PlanBuilder builder, Scope scope)
    {
        return planBuilderFor(new RelationPlan(builder.getRoot(), scope, builder.getRoot().getOutputSymbols()));
    }

    private PlanBuilder planBuilderFor(RelationPlan relationPlan)
    {
        TranslationMap translations = new TranslationMap(relationPlan, analysis, lambdaDeclarationToSymbolMap);

        // Make field->symbol mapping from underlying relation plan available for translations
        // This makes it possible to rewrite FieldOrExpressions that reference fields from the FROM clause directly
        translations.setFieldMappings(relationPlan.getFieldMappings());

        return new PlanBuilder(translations, relationPlan.getRoot());
    }

    private RelationPlan planImplicitTable()
    {
        List<Expression> emptyRow = ImmutableList.of();
        Scope scope = Scope.create();
        return new RelationPlan(
                new ValuesNode(idAllocator.getNextId(), ImmutableList.of(), ImmutableList.of(emptyRow)),
                scope,
                ImmutableList.of());
    }

    private PlanBuilder filter(PlanBuilder subPlan, Expression predicate, Node node)
    {
        if (predicate == null) {
            return subPlan;
        }

        // rewrite expressions which contain already handled subqueries
        Expression rewrittenBeforeSubqueries = subPlan.rewrite(predicate);
        subPlan = subqueryPlanner.handleSubqueries(subPlan, rewrittenBeforeSubqueries, node);
        Expression rewrittenAfterSubqueries = subPlan.rewrite(predicate);

        return subPlan.withNewRoot(new FilterNode(idAllocator.getNextId(), subPlan.getRoot(), rewrittenAfterSubqueries));
    }

    private PlanBuilder project(PlanBuilder subPlan, Iterable<Expression> expressions, RelationPlan parentRelationPlan)
    {
        return project(subPlan, Iterables.concat(expressions, toSymbolReferences(parentRelationPlan.getFieldMappings())));
    }

    private PlanBuilder project(PlanBuilder subPlan, Iterable<Expression> expressions)
    {
        TranslationMap outputTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis, lambdaDeclarationToSymbolMap);

        Assignments.Builder projections = Assignments.builder();
        for (Expression expression : expressions) {
            if (expression instanceof SymbolReference) {
                Symbol symbol = Symbol.from(expression);
                projections.put(symbol, expression);
                outputTranslations.put(expression, symbol);
                continue;
            }

            Symbol symbol = symbolAllocator.newSymbol(expression, analysis.getTypeWithCoercions(expression));
            projections.put(symbol, subPlan.rewrite(expression));
            outputTranslations.put(expression, symbol);
        }

        return new PlanBuilder(outputTranslations, new ProjectNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                projections.build()));
    }

    private Map<Symbol, Expression> coerce(Iterable<? extends Expression> expressions, PlanBuilder subPlan, TranslationMap translations)
    {
        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        for (Expression expression : expressions) {
            Type type = analysis.getType(expression);
            Type coercion = analysis.getCoercion(expression);
            Symbol symbol = symbolAllocator.newSymbol(expression, firstNonNull(coercion, type));
            Expression rewritten = subPlan.rewrite(expression);
            if (coercion != null) {
                rewritten = new Cast(
                        rewritten,
                        coercion.getTypeSignature().toString(),
                        false,
                        typeCoercion.isTypeOnlyCoercion(type, coercion));
            }
            projections.put(symbol, rewritten);
            translations.put(expression, symbol);
        }

        return projections.build();
    }

    private PlanBuilder explicitCoercionFields(PlanBuilder subPlan, Iterable<Expression> alreadyCoerced, Iterable<? extends Expression> uncoerced)
    {
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis, lambdaDeclarationToSymbolMap);
        Assignments.Builder projections = Assignments.builder();

        projections.putAll(coerce(uncoerced, subPlan, translations));

        for (Expression expression : alreadyCoerced) {
            if (expression instanceof SymbolReference) {
                // If this is an identity projection, no need to rewrite it
                // This is needed because certain synthetic identity expressions such as "group id" introduced when planning GROUPING
                // don't have a corresponding analysis, so the code below doesn't work for them
                projections.put(Symbol.from(expression), expression);
                continue;
            }

            Symbol symbol = symbolAllocator.newSymbol(expression, analysis.getType(expression));
            Expression rewritten = subPlan.rewrite(expression);
            projections.put(symbol, rewritten);
            translations.put(expression, symbol);
        }

        return new PlanBuilder(translations, new ProjectNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                projections.build()));
    }

    private PlanBuilder explicitCoercionSymbols(PlanBuilder subPlan, Iterable<Symbol> alreadyCoerced, Iterable<? extends Expression> uncoerced)
    {
        TranslationMap translations = subPlan.copyTranslations();

        Assignments assignments = Assignments.builder()
                .putAll(coerce(uncoerced, subPlan, translations))
                .putIdentities(alreadyCoerced)
                .build();

        return new PlanBuilder(translations, new ProjectNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                assignments));
    }

    private PlanBuilder aggregate(PlanBuilder subPlan, QuerySpecification node)
    {
        if (!analysis.isAggregation(node)) {
            return subPlan;
        }

        // 1. Pre-project all scalar inputs (arguments and non-trivial group by expressions)
        Set<Expression> groupByExpressions = ImmutableSet.copyOf(analysis.getGroupByExpressions(node));

        ImmutableList.Builder<Expression> arguments = ImmutableList.builder();
        analysis.getAggregates(node).stream()
                .map(FunctionCall::getArguments)
                .flatMap(List::stream)
                .filter(exp -> !(exp instanceof LambdaExpression)) // lambda expression is generated at execution time
                .forEach(arguments::add);

        analysis.getAggregates(node).stream()
                .map(FunctionCall::getOrderBy)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(OrderBy::getSortItems)
                .flatMap(List::stream)
                .map(SortItem::getSortKey)
                .forEach(arguments::add);

        // filter expressions need to be projected first
        analysis.getAggregates(node).stream()
                .map(FunctionCall::getFilter)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(arguments::add);

        Iterable<Expression> inputs = Iterables.concat(groupByExpressions, arguments.build());
        subPlan = handleSubqueries(subPlan, node, inputs);

        if (!Iterables.isEmpty(inputs)) { // avoid an empty projection if the only aggregation is COUNT (which has no arguments)
            subPlan = project(subPlan, inputs);
        }

        // 2. Aggregate

        // 2.a. Rewrite aggregate arguments
        TranslationMap argumentTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis, lambdaDeclarationToSymbolMap);

        ImmutableList.Builder<Symbol> aggregationArgumentsBuilder = ImmutableList.builder();
        for (Expression argument : arguments.build()) {
            Symbol symbol = subPlan.translate(argument);
            argumentTranslations.put(argument, symbol);
            aggregationArgumentsBuilder.add(symbol);
        }
        List<Symbol> aggregationArguments = aggregationArgumentsBuilder.build();

        // 2.b. Rewrite grouping columns
        TranslationMap groupingTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis, lambdaDeclarationToSymbolMap);
        Map<Symbol, Symbol> groupingSetMappings = new LinkedHashMap<>();

        for (Expression expression : groupByExpressions) {
            Symbol input = subPlan.translate(expression);
            Symbol output = symbolAllocator.newSymbol(expression, analysis.getTypeWithCoercions(expression), "gid");
            groupingTranslations.put(expression, output);
            groupingSetMappings.put(output, input);
        }

        // This tracks the grouping sets before complex expressions are considered (see comments below)
        // It's also used to compute the descriptors needed to implement grouping()
        List<Set<FieldId>> columnOnlyGroupingSets = ImmutableList.of(ImmutableSet.of());
        List<List<Symbol>> groupingSets = ImmutableList.of(ImmutableList.of());

        if (node.getGroupBy().isPresent()) {
            // For the purpose of "distinct", we need to canonicalize column references that may have varying
            // syntactic forms (e.g., "t.a" vs "a"). Thus we need to enumerate grouping sets based on the underlying
            // fieldId associated with each column reference expression.

            // The catch is that simple group-by expressions can be arbitrary expressions (this is a departure from the SQL specification).
            // But, they don't affect the number of grouping sets or the behavior of "distinct" . We can compute all the candidate
            // grouping sets in terms of fieldId, dedup as appropriate and then cross-join them with the complex expressions.
            Analysis.GroupingSetAnalysis groupingSetAnalysis = analysis.getGroupingSets(node);
            columnOnlyGroupingSets = enumerateGroupingSets(groupingSetAnalysis);

            if (node.getGroupBy().get().isDistinct()) {
                columnOnlyGroupingSets = columnOnlyGroupingSets.stream()
                        .distinct()
                        .collect(toImmutableList());
            }

            // add in the complex expressions an turn materialize the grouping sets in terms of plan columns
            ImmutableList.Builder<List<Symbol>> groupingSetBuilder = ImmutableList.builder();
            for (Set<FieldId> groupingSet : columnOnlyGroupingSets) {
                ImmutableList.Builder<Symbol> columns = ImmutableList.builder();
                groupingSetAnalysis.getComplexExpressions().stream()
                        .map(groupingTranslations::get)
                        .forEach(columns::add);

                groupingSet.stream()
                        .map(field -> groupingTranslations.get(new FieldReference(field.getFieldIndex())))
                        .forEach(columns::add);

                groupingSetBuilder.add(columns.build());
            }

            groupingSets = groupingSetBuilder.build();
        }

        // 2.c. Generate GroupIdNode (multiple grouping sets) or ProjectNode (single grouping set)
        Optional<Symbol> groupIdSymbol = Optional.empty();
        if (groupingSets.size() > 1) {
            groupIdSymbol = Optional.of(symbolAllocator.newSymbol("groupId", BIGINT));
            GroupIdNode groupId = new GroupIdNode(idAllocator.getNextId(), subPlan.getRoot(), groupingSets, groupingSetMappings, aggregationArguments, groupIdSymbol.get());
            subPlan = new PlanBuilder(groupingTranslations, groupId);
        }
        else {
            Assignments.Builder assignments = Assignments.builder();
            aggregationArguments.forEach(assignments::putIdentity);
            groupingSetMappings.forEach((key, value) -> assignments.put(key, value.toSymbolReference()));

            ProjectNode project = new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), assignments.build());
            subPlan = new PlanBuilder(groupingTranslations, project);
        }

        TranslationMap aggregationTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis, lambdaDeclarationToSymbolMap);
        aggregationTranslations.copyMappingsFrom(groupingTranslations);

        // 2.d. Rewrite aggregates
        ImmutableMap.Builder<Symbol, Aggregation> aggregationsBuilder = ImmutableMap.builder();
        boolean needPostProjectionCoercion = false;
        for (FunctionCall aggregate : analysis.getAggregates(node)) {
            Expression rewritten = argumentTranslations.rewrite(aggregate);
            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(aggregate));

            // TODO: this is a hack, because we apply coercions to the output of expressions, rather than the arguments to expressions.
            // Therefore we can end up with this implicit cast, and have to move it into a post-projection
            if (rewritten instanceof Cast) {
                rewritten = ((Cast) rewritten).getExpression();
                needPostProjectionCoercion = true;
            }
            aggregationTranslations.put(aggregate, newSymbol);

            FunctionCall functionCall = (FunctionCall) rewritten;
            aggregationsBuilder.put(newSymbol, new Aggregation(
                    analysis.getFunctionSignature(aggregate),
                    functionCall.getArguments(),
                    functionCall.isDistinct(),
                    functionCall.getFilter().map(Symbol::from),
                    functionCall.getOrderBy().map(OrderingScheme::fromOrderBy),
                    Optional.empty()));
        }
        Map<Symbol, Aggregation> aggregations = aggregationsBuilder.build();

        ImmutableSet.Builder<Integer> globalGroupingSets = ImmutableSet.builder();
        for (int i = 0; i < groupingSets.size(); i++) {
            if (groupingSets.get(i).isEmpty()) {
                globalGroupingSets.add(i);
            }
        }

        ImmutableList.Builder<Symbol> groupingKeys = ImmutableList.builder();
        groupingSets.stream()
                .flatMap(List::stream)
                .distinct()
                .forEach(groupingKeys::add);
        groupIdSymbol.ifPresent(groupingKeys::add);

        AggregationNode aggregationNode = new AggregationNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                aggregations,
                groupingSets(
                        groupingKeys.build(),
                        groupingSets.size(),
                        globalGroupingSets.build()),
                ImmutableList.of(),
                AggregationNode.Step.SINGLE,
                Optional.empty(),
                groupIdSymbol);

        subPlan = new PlanBuilder(aggregationTranslations, aggregationNode);

        // 3. Post-projection
        // Add back the implicit casts that we removed in 2.a
        // TODO: this is a hack, we should change type coercions to coerce the inputs to functions/operators instead of coercing the output
        if (needPostProjectionCoercion) {
            ImmutableList.Builder<Expression> alreadyCoerced = ImmutableList.builder();
            alreadyCoerced.addAll(groupByExpressions);
            groupIdSymbol.map(Symbol::toSymbolReference).ifPresent(alreadyCoerced::add);

            subPlan = explicitCoercionFields(subPlan, alreadyCoerced.build(), analysis.getAggregates(node));
        }

        // 4. Project and re-write all grouping functions
        return handleGroupingOperations(subPlan, node, groupIdSymbol, columnOnlyGroupingSets);
    }

    private List<Set<FieldId>> enumerateGroupingSets(Analysis.GroupingSetAnalysis groupingSetAnalysis)
    {
        List<List<Set<FieldId>>> partialSets = new ArrayList<>();

        for (Set<FieldId> cube : groupingSetAnalysis.getCubes()) {
            partialSets.add(ImmutableList.copyOf(Sets.powerSet(cube)));
        }

        for (List<FieldId> rollup : groupingSetAnalysis.getRollups()) {
            List<Set<FieldId>> sets = IntStream.rangeClosed(0, rollup.size())
                    .mapToObj(i -> ImmutableSet.copyOf(rollup.subList(0, i)))
                    .collect(toImmutableList());

            partialSets.add(sets);
        }

        partialSets.addAll(groupingSetAnalysis.getOrdinarySets());

        if (partialSets.isEmpty()) {
            return ImmutableList.of(ImmutableSet.of());
        }

        // compute the cross product of the partial sets
        List<Set<FieldId>> allSets = new ArrayList<>();
        partialSets.get(0)
                .stream()
                .map(ImmutableSet::copyOf)
                .forEach(allSets::add);

        for (int i = 1; i < partialSets.size(); i++) {
            List<Set<FieldId>> groupingSets = partialSets.get(i);
            List<Set<FieldId>> oldGroupingSetsCrossProduct = ImmutableList.copyOf(allSets);
            allSets.clear();
            for (Set<FieldId> existingSet : oldGroupingSetsCrossProduct) {
                for (Set<FieldId> groupingSet : groupingSets) {
                    Set<FieldId> concatenatedSet = ImmutableSet.<FieldId>builder()
                            .addAll(existingSet)
                            .addAll(groupingSet)
                            .build();
                    allSets.add(concatenatedSet);
                }
            }
        }

        return allSets;
    }

    private PlanBuilder handleGroupingOperations(PlanBuilder subPlan, QuerySpecification node, Optional<Symbol> groupIdSymbol, List<Set<FieldId>> groupingSets)
    {
        if (analysis.getGroupingOperations(node).isEmpty()) {
            return subPlan;
        }

        TranslationMap newTranslations = subPlan.copyTranslations();

        Assignments.Builder projections = Assignments.builder();
        projections.putIdentities(subPlan.getRoot().getOutputSymbols());

        List<Set<Integer>> descriptor = groupingSets.stream()
                .map(set -> set.stream()
                        .map(FieldId::getFieldIndex)
                        .collect(toImmutableSet()))
                .collect(toImmutableList());

        for (GroupingOperation groupingOperation : analysis.getGroupingOperations(node)) {
            Expression rewritten = GroupingOperationRewriter.rewriteGroupingOperation(groupingOperation, descriptor, analysis.getColumnReferenceFields(), groupIdSymbol);
            Type coercion = analysis.getCoercion(groupingOperation);
            Symbol symbol = symbolAllocator.newSymbol(rewritten, analysis.getTypeWithCoercions(groupingOperation));
            if (coercion != null) {
                rewritten = new Cast(
                        rewritten,
                        coercion.getTypeSignature().toString(),
                        false,
                        typeCoercion.isTypeOnlyCoercion(analysis.getType(groupingOperation), coercion));
            }
            projections.put(symbol, rewritten);
            newTranslations.put(groupingOperation, symbol);
        }

        return new PlanBuilder(newTranslations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()));
    }

    private PlanBuilder window(PlanBuilder subPlan, OrderBy node)
    {
        return window(subPlan, ImmutableList.copyOf(analysis.getOrderByWindowFunctions(node)));
    }

    private PlanBuilder window(PlanBuilder subPlan, QuerySpecification node)
    {
        return window(subPlan, ImmutableList.copyOf(analysis.getWindowFunctions(node)));
    }

    private PlanBuilder window(PlanBuilder subPlan, List<FunctionCall> windowFunctions)
    {
        if (windowFunctions.isEmpty()) {
            return subPlan;
        }

        for (FunctionCall windowFunction : windowFunctions) {
            Window window = windowFunction.getWindow().get();

            // Extract frame
            WindowFrame.Type frameType = WindowFrame.Type.RANGE;
            FrameBound.Type frameStartType = FrameBound.Type.UNBOUNDED_PRECEDING;
            FrameBound.Type frameEndType = FrameBound.Type.CURRENT_ROW;
            Expression frameStart = null;
            Expression frameEnd = null;

            if (window.getFrame().isPresent()) {
                WindowFrame frame = window.getFrame().get();
                frameType = frame.getType();

                frameStartType = frame.getStart().getType();
                frameStart = frame.getStart().getValue().orElse(null);

                if (frame.getEnd().isPresent()) {
                    frameEndType = frame.getEnd().get().getType();
                    frameEnd = frame.getEnd().get().getValue().orElse(null);
                }
            }

            // Pre-project inputs
            ImmutableList.Builder<Expression> inputs = ImmutableList.<Expression>builder()
                    .addAll(windowFunction.getArguments())
                    .addAll(window.getPartitionBy())
                    .addAll(getSortItemsFromOrderBy(window.getOrderBy()).stream()
                            .map(SortItem::getSortKey)
                            .iterator());

            if (frameStart != null) {
                inputs.add(frameStart);
            }
            if (frameEnd != null) {
                inputs.add(frameEnd);
            }

            subPlan = subPlan.appendProjections(inputs.build(), symbolAllocator, idAllocator);

            // Rewrite PARTITION BY in terms of pre-projected inputs
            ImmutableList.Builder<Symbol> partitionBySymbols = ImmutableList.builder();
            for (Expression expression : window.getPartitionBy()) {
                partitionBySymbols.add(subPlan.translate(expression));
            }

            // Rewrite ORDER BY in terms of pre-projected inputs
            LinkedHashMap<Symbol, SortOrder> orderings = new LinkedHashMap<>();
            for (SortItem item : getSortItemsFromOrderBy(window.getOrderBy())) {
                Symbol symbol = subPlan.translate(item.getSortKey());
                // don't override existing keys, i.e. when "ORDER BY a ASC, a DESC" is specified
                orderings.putIfAbsent(symbol, sortItemToSortOrder(item));
            }

            // Rewrite frame bounds in terms of pre-projected inputs
            Optional<Symbol> frameStartSymbol = Optional.empty();
            Optional<Symbol> frameEndSymbol = Optional.empty();
            if (frameStart != null) {
                frameStartSymbol = Optional.of(subPlan.translate(frameStart));
            }
            if (frameEnd != null) {
                frameEndSymbol = Optional.of(subPlan.translate(frameEnd));
            }

            WindowNode.Frame frame = new WindowNode.Frame(
                    frameType,
                    frameStartType,
                    frameStartSymbol,
                    frameEndType,
                    frameEndSymbol,
                    Optional.ofNullable(frameStart),
                    Optional.ofNullable(frameEnd));

            TranslationMap outputTranslations = subPlan.copyTranslations();

            // Rewrite function call in terms of pre-projected inputs
            Expression rewritten = subPlan.rewrite(windowFunction);

            boolean needCoercion = rewritten instanceof Cast;
            // Strip out the cast and add it back as a post-projection
            if (rewritten instanceof Cast) {
                rewritten = ((Cast) rewritten).getExpression();
            }

            // If refers to existing symbol, don't create another PlanNode
            if (rewritten instanceof SymbolReference) {
                if (needCoercion) {
                    subPlan = explicitCoercionSymbols(subPlan, subPlan.getRoot().getOutputSymbols(), ImmutableList.of(windowFunction));
                }

                continue;
            }

            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(windowFunction));
            outputTranslations.put(windowFunction, newSymbol);

            NullTreatment nullTreatment = windowFunction.getNullTreatment()
                    .orElse(NullTreatment.RESPECT);

            WindowNode.Function function = new WindowNode.Function(
                    analysis.getFunctionSignature(windowFunction),
                    ((FunctionCall) rewritten).getArguments(),
                    frame,
                    nullTreatment == NullTreatment.IGNORE);

            List<Symbol> sourceSymbols = subPlan.getRoot().getOutputSymbols();
            ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
            orderBySymbols.addAll(orderings.keySet());
            Optional<OrderingScheme> orderingScheme = Optional.empty();
            if (!orderings.isEmpty()) {
                orderingScheme = Optional.of(new OrderingScheme(orderBySymbols.build(), orderings));
            }

            // create window node
            subPlan = new PlanBuilder(outputTranslations,
                    new WindowNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            new WindowNode.Specification(
                                    partitionBySymbols.build(),
                                    orderingScheme),
                            ImmutableMap.of(newSymbol, function),
                            Optional.empty(),
                            ImmutableSet.of(),
                            0));

            if (needCoercion) {
                subPlan = explicitCoercionSymbols(subPlan, sourceSymbols, ImmutableList.of(windowFunction));
            }
        }

        return subPlan;
    }

    private PlanBuilder handleSubqueries(PlanBuilder subPlan, Node node, Iterable<Expression> inputs)
    {
        for (Expression input : inputs) {
            subPlan = subqueryPlanner.handleSubqueries(subPlan, subPlan.rewrite(input), node);
        }
        return subPlan;
    }

    private PlanBuilder distinct(PlanBuilder subPlan, QuerySpecification node)
    {
        if (node.getSelect().isDistinct()) {
            return subPlan.withNewRoot(
                    new AggregationNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            ImmutableMap.of(),
                            singleGroupingSet(subPlan.getRoot().getOutputSymbols()),
                            ImmutableList.of(),
                            AggregationNode.Step.SINGLE,
                            Optional.empty(),
                            Optional.empty()));
        }

        return subPlan;
    }

    private Optional<OrderingScheme> orderingScheme(PlanBuilder subPlan, Optional<OrderBy> orderBy, List<Expression> orderByExpressions)
    {
        if (!orderBy.isPresent() || (isSkipRedundantSort(session)) && analysis.isOrderByRedundant(orderBy.get())) {
            return Optional.empty();
        }

        Iterator<SortItem> sortItems = orderBy.get().getSortItems().iterator();

        ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
        Map<Symbol, SortOrder> orderings = new HashMap<>();
        for (Expression fieldOrExpression : orderByExpressions) {
            Symbol symbol = subPlan.translate(fieldOrExpression);

            SortItem sortItem = sortItems.next();
            if (!orderings.containsKey(symbol)) {
                orderBySymbols.add(symbol);
                orderings.put(symbol, sortItemToSortOrder(sortItem));
            }
        }
        return Optional.of(new OrderingScheme(orderBySymbols.build(), orderings));
    }

    private PlanBuilder sort(PlanBuilder subPlan, Optional<OrderingScheme> orderingScheme)
    {
        if (!orderingScheme.isPresent()) {
            return subPlan;
        }

        return subPlan.withNewRoot(
                new SortNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        orderingScheme.get(),
                        false));
    }

    private PlanBuilder offset(PlanBuilder subPlan, Optional<Offset> offset)
    {
        if (!offset.isPresent()) {
            return subPlan;
        }

        return subPlan.withNewRoot(
                new OffsetNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        analysis.getOffset(offset.get())));
    }

    private PlanBuilder limit(PlanBuilder subPlan, Optional<Node> limit, Optional<OrderingScheme> orderingScheme)
    {
        if (limit.isPresent() && analysis.getLimit(limit.get()).isPresent()) {
            Optional<OrderingScheme> tiesResolvingScheme = Optional.empty();
            if (limit.get() instanceof FetchFirst && ((FetchFirst) limit.get()).isWithTies()) {
                tiesResolvingScheme = orderingScheme;
            }
            return subPlan.withNewRoot(
                    new LimitNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            analysis.getLimit(limit.get()).getAsLong(),
                            tiesResolvingScheme,
                            false));
        }
        return subPlan;
    }

    private static List<Expression> toSymbolReferences(List<Symbol> symbols)
    {
        return symbols.stream()
                .map(Symbol::toSymbolReference)
                .collect(toImmutableList());
    }

    private static Map<Expression, Symbol> symbolsForExpressions(PlanBuilder builder, Iterable<? extends Expression> expressions)
    {
        return stream(expressions)
                .distinct()
                .collect(toImmutableMap(expression -> expression, builder::translate));
    }
}
