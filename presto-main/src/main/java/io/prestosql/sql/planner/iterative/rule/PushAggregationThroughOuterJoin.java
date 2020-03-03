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
import io.prestosql.Session;
import io.prestosql.matching.Capture;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.AggregationNode.Aggregation;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.SymbolReference;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.SystemSessionProperties.shouldPushAggregationThroughJoin;
import static io.prestosql.matching.Capture.newCapture;
import static io.prestosql.sql.planner.ExpressionSymbolInliner.inlineSymbols;
import static io.prestosql.sql.planner.optimizations.DistinctOutputQueryUtil.isDistinct;
import static io.prestosql.sql.planner.plan.AggregationNode.globalAggregation;
import static io.prestosql.sql.planner.plan.AggregationNode.singleGroupingSet;
import static io.prestosql.sql.planner.plan.Patterns.aggregation;
import static io.prestosql.sql.planner.plan.Patterns.join;
import static io.prestosql.sql.planner.plan.Patterns.source;

/**
 * This optimizer pushes aggregations below outer joins when: the aggregation
 * is on top of the outer join, it groups by all columns in the outer table, and
 * the outer rows are guaranteed to be distinct.
 * <p>
 * When the aggregation is pushed down, we still need to perform aggregations
 * on the null values that come out of the absent values in an outer
 * join. We add a cross join with a row of aggregations on null literals,
 * and coalesce the aggregation that results from the left outer join with
 * the result of the aggregation over nulls.
 * <p>
 * Example:
 * <pre>
 * - Filter ("nationkey" > "avg")
 *  - Aggregate(Group by: all columns from the left table, aggregation:
 *    avg("n2.nationkey"))
 *      - LeftJoin("regionkey" = "regionkey")
 *          - AssignUniqueId (nation)
 *              - Tablescan (nation)
 *          - Tablescan (nation)
 * </pre>
 * </p>
 * Is rewritten to:
 * <pre>
 * - Filter ("nationkey" > "avg")
 *  - project(regionkey, coalesce("avg", "avg_over_null")
 *      - CrossJoin
 *          - LeftJoin("regionkey" = "regionkey")
 *              - AssignUniqueId (nation)
 *                  - Tablescan (nation)
 *              - Aggregate(Group by: regionkey, aggregation:
 *                avg(nationkey))
 *                  - Tablescan (nation)
 *          - Aggregate
 *            avg(null_literal)
 *              - Values (null_literal)
 * </pre>
 */
public class PushAggregationThroughOuterJoin
        implements Rule<AggregationNode>
{
    private static final Capture<JoinNode> JOIN = newCapture();

    private static final Pattern<AggregationNode> PATTERN = aggregation()
            .with(source().matching(join().capturedAs(JOIN)));

    @Override
    public Pattern<AggregationNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return shouldPushAggregationThroughJoin(session);
    }

    @Override
    public Result apply(AggregationNode aggregation, Captures captures, Context context)
    {
        JoinNode join = captures.get(JOIN);

        if (join.getFilter().isPresent()
                || !(join.getType() == JoinNode.Type.LEFT || join.getType() == JoinNode.Type.RIGHT)
                || !groupsOnAllColumns(aggregation, getOuterTable(join).getOutputSymbols())
                || !isDistinct(context.getLookup().resolve(getOuterTable(join)), context.getLookup()::resolve)) {
            return Result.empty();
        }

        List<Symbol> groupingKeys = join.getCriteria().stream()
                .map(join.getType() == JoinNode.Type.RIGHT ? JoinNode.EquiJoinClause::getLeft : JoinNode.EquiJoinClause::getRight)
                .collect(toImmutableList());
        AggregationNode rewrittenAggregation = new AggregationNode(
                aggregation.getId(),
                getInnerTable(join),
                aggregation.getAggregations(),
                singleGroupingSet(groupingKeys),
                ImmutableList.of(),
                aggregation.getStep(),
                aggregation.getHashSymbol(),
                aggregation.getGroupIdSymbol());

        JoinNode rewrittenJoin;
        if (join.getType() == JoinNode.Type.LEFT) {
            rewrittenJoin = new JoinNode(
                    join.getId(),
                    join.getType(),
                    join.getLeft(),
                    rewrittenAggregation,
                    join.getCriteria(),
                    ImmutableList.<Symbol>builder()
                            .addAll(join.getLeft().getOutputSymbols())
                            .addAll(rewrittenAggregation.getAggregations().keySet())
                            .build(),
                    join.getFilter(),
                    join.getLeftHashSymbol(),
                    join.getRightHashSymbol(),
                    join.getDistributionType(),
                    join.isSpillable(),
                    join.getDynamicFilters());
        }
        else {
            rewrittenJoin = new JoinNode(
                    join.getId(),
                    join.getType(),
                    rewrittenAggregation,
                    join.getRight(),
                    join.getCriteria(),
                    ImmutableList.<Symbol>builder()
                            .addAll(rewrittenAggregation.getAggregations().keySet())
                            .addAll(join.getRight().getOutputSymbols())
                            .build(),
                    join.getFilter(),
                    join.getLeftHashSymbol(),
                    join.getRightHashSymbol(),
                    join.getDistributionType(),
                    join.isSpillable(),
                    join.getDynamicFilters());
        }

        Optional<PlanNode> resultNode = coalesceWithNullAggregation(rewrittenAggregation, rewrittenJoin, context.getSymbolAllocator(), context.getIdAllocator(), context.getLookup());
        if (!resultNode.isPresent()) {
            return Result.empty();
        }

        return Result.ofPlanNode(resultNode.get());
    }

    private static PlanNode getInnerTable(JoinNode join)
    {
        checkState(join.getType() == JoinNode.Type.LEFT || join.getType() == JoinNode.Type.RIGHT, "expected LEFT or RIGHT JOIN");
        PlanNode innerNode;
        if (join.getType().equals(JoinNode.Type.LEFT)) {
            innerNode = join.getRight();
        }
        else {
            innerNode = join.getLeft();
        }
        return innerNode;
    }

    private static PlanNode getOuterTable(JoinNode join)
    {
        checkState(join.getType() == JoinNode.Type.LEFT || join.getType() == JoinNode.Type.RIGHT, "expected LEFT or RIGHT JOIN");
        PlanNode outerNode;
        if (join.getType().equals(JoinNode.Type.LEFT)) {
            outerNode = join.getLeft();
        }
        else {
            outerNode = join.getRight();
        }
        return outerNode;
    }

    private static boolean groupsOnAllColumns(AggregationNode node, List<Symbol> columns)
    {
        return new HashSet<>(node.getGroupingKeys()).equals(new HashSet<>(columns));
    }

    // When the aggregation is done after the join, there will be a null value that gets aggregated over
    // where rows did not exist in the inner table.  For some aggregate functions, such as count, the result
    // of an aggregation over a single null row is one or zero rather than null. In order to ensure correct results,
    // we add a coalesce function with the output of the new outer join and the agggregation performed over a single
    // null row.
    private Optional<PlanNode> coalesceWithNullAggregation(AggregationNode aggregationNode, PlanNode outerJoin, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, Lookup lookup)
    {
        // Create an aggregation node over a row of nulls.
        Optional<MappedAggregationInfo> aggregationOverNullInfoResultNode = createAggregationOverNull(
                aggregationNode,
                symbolAllocator,
                idAllocator,
                lookup);

        if (!aggregationOverNullInfoResultNode.isPresent()) {
            return Optional.empty();
        }

        MappedAggregationInfo aggregationOverNullInfo = aggregationOverNullInfoResultNode.get();

        AggregationNode aggregationOverNull = aggregationOverNullInfo.getAggregation();
        Map<Symbol, Symbol> sourceAggregationToOverNullMapping = aggregationOverNullInfo.getSymbolMapping();

        // Do a cross join with the aggregation over null
        JoinNode crossJoin = new JoinNode(
                idAllocator.getNextId(),
                JoinNode.Type.INNER,
                outerJoin,
                aggregationOverNull,
                ImmutableList.of(),
                ImmutableList.<Symbol>builder()
                        .addAll(outerJoin.getOutputSymbols())
                        .addAll(aggregationOverNull.getOutputSymbols())
                        .build(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of());

        // Add coalesce expressions for all aggregation functions
        Assignments.Builder assignmentsBuilder = Assignments.builder();
        for (Symbol symbol : outerJoin.getOutputSymbols()) {
            if (aggregationNode.getAggregations().containsKey(symbol)) {
                assignmentsBuilder.put(symbol, new CoalesceExpression(symbol.toSymbolReference(), sourceAggregationToOverNullMapping.get(symbol).toSymbolReference()));
            }
            else {
                assignmentsBuilder.put(symbol, symbol.toSymbolReference());
            }
        }
        return Optional.of(new ProjectNode(idAllocator.getNextId(), crossJoin, assignmentsBuilder.build()));
    }

    private Optional<MappedAggregationInfo> createAggregationOverNull(AggregationNode referenceAggregation, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, Lookup lookup)
    {
        // Create a values node that consists of a single row of nulls.
        // Map the output symbols from the referenceAggregation's source
        // to symbol references for the new values node.
        NullLiteral nullLiteral = new NullLiteral();
        ImmutableList.Builder<Symbol> nullSymbols = ImmutableList.builder();
        ImmutableList.Builder<Expression> nullLiterals = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, SymbolReference> sourcesSymbolMappingBuilder = ImmutableMap.builder();
        for (Symbol sourceSymbol : referenceAggregation.getSource().getOutputSymbols()) {
            nullLiterals.add(nullLiteral);
            Symbol nullSymbol = symbolAllocator.newSymbol(nullLiteral, symbolAllocator.getTypes().get(sourceSymbol));
            nullSymbols.add(nullSymbol);
            sourcesSymbolMappingBuilder.put(sourceSymbol, nullSymbol.toSymbolReference());
        }
        ValuesNode nullRow = new ValuesNode(
                idAllocator.getNextId(),
                nullSymbols.build(),
                ImmutableList.of(nullLiterals.build()));
        Map<Symbol, SymbolReference> sourcesSymbolMapping = sourcesSymbolMappingBuilder.build();

        // For each aggregation function in the reference node, create a corresponding aggregation function
        // that points to the nullRow. Map the symbols from the aggregations in referenceAggregation to the
        // symbols in these new aggregations.
        ImmutableMap.Builder<Symbol, Symbol> aggregationsSymbolMappingBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, AggregationNode.Aggregation> aggregationsOverNullBuilder = ImmutableMap.builder();
        for (Map.Entry<Symbol, AggregationNode.Aggregation> entry : referenceAggregation.getAggregations().entrySet()) {
            Symbol aggregationSymbol = entry.getKey();
            AggregationNode.Aggregation aggregation = entry.getValue();

            if (!isUsingSymbols(aggregation, sourcesSymbolMapping.keySet())) {
                return Optional.empty();
            }

            Aggregation overNullAggregation = new Aggregation(
                    aggregation.getSignature(),
                    aggregation.getArguments().stream()
                            .map(argument -> inlineSymbols(sourcesSymbolMapping, argument))
                            .collect(toImmutableList()),
                    aggregation.isDistinct(),
                    aggregation.getFilter(),
                    aggregation.getOrderingScheme(),
                    aggregation.getMask());
            Symbol overNullSymbol = symbolAllocator.newSymbol(overNullAggregation.getSignature().getName(), symbolAllocator.getTypes().get(aggregationSymbol));
            aggregationsOverNullBuilder.put(overNullSymbol, overNullAggregation);
            aggregationsSymbolMappingBuilder.put(aggregationSymbol, overNullSymbol);
        }
        Map<Symbol, Symbol> aggregationsSymbolMapping = aggregationsSymbolMappingBuilder.build();

        // create an aggregation node whose source is the null row.
        AggregationNode aggregationOverNullRow = new AggregationNode(
                idAllocator.getNextId(),
                nullRow,
                aggregationsOverNullBuilder.build(),
                globalAggregation(),
                ImmutableList.of(),
                AggregationNode.Step.SINGLE,
                Optional.empty(),
                Optional.empty());

        return Optional.of(new MappedAggregationInfo(aggregationOverNullRow, aggregationsSymbolMapping));
    }

    private static boolean isUsingSymbols(AggregationNode.Aggregation aggregation, Set<Symbol> sourceSymbols)
    {
        List<Expression> functionArguments = aggregation.getArguments();
        return sourceSymbols.stream()
                .map(Symbol::toSymbolReference)
                .anyMatch(functionArguments::contains);
    }

    private static class MappedAggregationInfo
    {
        private final AggregationNode aggregationNode;
        private final Map<Symbol, Symbol> symbolMapping;

        public MappedAggregationInfo(AggregationNode aggregationNode, Map<Symbol, Symbol> symbolMapping)
        {
            this.aggregationNode = aggregationNode;
            this.symbolMapping = symbolMapping;
        }

        public Map<Symbol, Symbol> getSymbolMapping()
        {
            return symbolMapping;
        }

        public AggregationNode getAggregation()
        {
            return aggregationNode;
        }
    }
}
