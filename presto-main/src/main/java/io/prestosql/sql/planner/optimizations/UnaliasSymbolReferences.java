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
package io.prestosql.sql.planner.optimizations;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.sql.planner.DeterminismEvaluator;
import io.prestosql.sql.planner.OrderingScheme;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.CorrelatedJoinNode;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.ExceptNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.ExplainAnalyzeNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.GroupIdNode;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.IndexSourceNode;
import io.prestosql.sql.planner.plan.IntersectNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.MarkDistinctNode;
import io.prestosql.sql.planner.plan.OffsetNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.RemoteSourceNode;
import io.prestosql.sql.planner.plan.RowNumberNode;
import io.prestosql.sql.planner.plan.SampleNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SetOperationNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableDeleteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.TopNNode;
import io.prestosql.sql.planner.plan.TopNRowNumberNode;
import io.prestosql.sql.planner.plan.UnionNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.planner.plan.WindowNode;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.SymbolReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static java.util.Objects.requireNonNull;

/**
 * Re-maps symbol references that are just aliases of each other (e.g., due to projections like {@code $0 := $1})
 * <p/>
 * E.g.,
 * <p/>
 * {@code Output[$0, $1] -> Project[$0 := $2, $1 := $3 * 100] -> Aggregate[$2, $3 := sum($4)] -> ...}
 * <p/>
 * gets rewritten as
 * <p/>
 * {@code Output[$2, $1] -> Project[$2, $1 := $3 * 100] -> Aggregate[$2, $3 := sum($4)] -> ...}
 */
public class UnaliasSymbolReferences
        implements PlanOptimizer
{
    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return SimplePlanRewriter.rewriteWith(new Rewriter(types), plan);
    }

    private static class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private final Map<Symbol, Symbol> mapping = new HashMap<>();
        private final TypeProvider types;

        private Rewriter(TypeProvider types)
        {
            this.types = types;
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            //TODO: use mapper in other methods
            SymbolMapper mapper = new SymbolMapper(mapping);
            return mapper.map(node, source);
        }

        @Override
        public PlanNode visitGroupId(GroupIdNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            Map<Symbol, Symbol> newGroupingMappings = new HashMap<>();
            ImmutableList.Builder<List<Symbol>> newGroupingSets = ImmutableList.builder();

            for (List<Symbol> groupingSet : node.getGroupingSets()) {
                ImmutableList.Builder<Symbol> newGroupingSet = ImmutableList.builder();
                for (Symbol output : groupingSet) {
                    newGroupingMappings.putIfAbsent(canonicalize(output), canonicalize(node.getGroupingColumns().get(output)));
                    newGroupingSet.add(canonicalize(output));
                }
                newGroupingSets.add(newGroupingSet.build());
            }

            return new GroupIdNode(node.getId(), source, newGroupingSets.build(), newGroupingMappings, canonicalizeAndDistinct(node.getAggregationArguments()), canonicalize(node.getGroupIdSymbol()));
        }

        @Override
        public PlanNode visitExplainAnalyze(ExplainAnalyzeNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            return new ExplainAnalyzeNode(node.getId(), source, canonicalize(node.getOutputSymbol()), node.isVerbose());
        }

        @Override
        public PlanNode visitMarkDistinct(MarkDistinctNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            List<Symbol> symbols = canonicalizeAndDistinct(node.getDistinctSymbols());
            return new MarkDistinctNode(node.getId(), source, canonicalize(node.getMarkerSymbol()), symbols, canonicalize(node.getHashSymbol()));
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            ImmutableMap.Builder<Symbol, List<Symbol>> builder = ImmutableMap.builder();
            for (Map.Entry<Symbol, List<Symbol>> entry : node.getUnnestSymbols().entrySet()) {
                builder.put(canonicalize(entry.getKey()), entry.getValue());
            }
            return new UnnestNode(
                    node.getId(),
                    source,
                    canonicalizeAndDistinct(node.getReplicateSymbols()),
                    builder.build(),
                    node.getOrdinalitySymbol(),
                    node.getJoinType(),
                    node.getFilter().map(this::canonicalize));
        }

        @Override
        public PlanNode visitWindow(WindowNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            ImmutableMap.Builder<Symbol, WindowNode.Function> functions = ImmutableMap.builder();
            node.getWindowFunctions().forEach((symbol, function) -> {
                Signature signature = function.getSignature();
                List<Expression> arguments = canonicalize(function.getArguments());
                WindowNode.Frame canonicalFrame = canonicalize(function.getFrame());

                functions.put(canonicalize(symbol), new WindowNode.Function(signature, arguments, canonicalFrame, function.isIgnoreNulls()));
            });

            return new WindowNode(
                    node.getId(),
                    source,
                    canonicalizeAndDistinct(node.getSpecification()),
                    functions.build(),
                    canonicalize(node.getHashSymbol()),
                    canonicalize(node.getPrePartitionedInputs()),
                    node.getPreSortedOrderPrefix());
        }

        private WindowNode.Frame canonicalize(WindowNode.Frame frame)
        {
            return new WindowNode.Frame(
                    frame.getType(),
                    frame.getStartType(),
                    canonicalize(frame.getStartValue()),
                    frame.getEndType(),
                    canonicalize(frame.getEndValue()),
                    frame.getOriginalStartValue(),
                    frame.getOriginalEndValue());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<Void> context)
        {
            return node;
        }

        @Override
        public PlanNode visitExchange(ExchangeNode node, RewriteContext<Void> context)
        {
            List<PlanNode> sources = node.getSources().stream()
                    .map(context::rewrite)
                    .collect(toImmutableList());

            mapExchangeNodeSymbols(node);

            List<List<Symbol>> inputs = new ArrayList<>();
            for (int i = 0; i < node.getInputs().size(); i++) {
                inputs.add(new ArrayList<>());
            }
            Set<Symbol> addedOutputs = new HashSet<>();
            ImmutableList.Builder<Symbol> outputs = ImmutableList.builder();
            for (int symbolIndex = 0; symbolIndex < node.getOutputSymbols().size(); symbolIndex++) {
                Symbol canonicalOutput = canonicalize(node.getOutputSymbols().get(symbolIndex));
                if (addedOutputs.add(canonicalOutput)) {
                    outputs.add(canonicalOutput);
                    for (int i = 0; i < node.getInputs().size(); i++) {
                        List<Symbol> input = node.getInputs().get(i);
                        inputs.get(i).add(canonicalize(input.get(symbolIndex)));
                    }
                }
            }

            PartitioningScheme partitioningScheme = new PartitioningScheme(
                    node.getPartitioningScheme().getPartitioning().translate(this::canonicalize),
                    outputs.build(),
                    canonicalize(node.getPartitioningScheme().getHashColumn()),
                    node.getPartitioningScheme().isReplicateNullsAndAny(),
                    node.getPartitioningScheme().getBucketToPartition());

            Optional<OrderingScheme> orderingScheme = node.getOrderingScheme().map(this::canonicalizeAndDistinct);

            return new ExchangeNode(node.getId(), node.getType(), node.getScope(), partitioningScheme, sources, inputs, orderingScheme);
        }

        private void mapExchangeNodeSymbols(ExchangeNode node)
        {
            if (node.getInputs().size() == 1) {
                mapExchangeNodeOutputToInputSymbols(node);
                return;
            }

            // Mapping from list [node.getInput(0).get(symbolIndex), node.getInput(1).get(symbolIndex), ...] to node.getOutputSymbols(symbolIndex).
            // All symbols are canonical.
            Map<List<Symbol>, Symbol> inputsToOutputs = new HashMap<>();
            // Map each same list of input symbols [I1, I2, ..., In] to the same output symbol O
            for (int symbolIndex = 0; symbolIndex < node.getOutputSymbols().size(); symbolIndex++) {
                Symbol canonicalOutput = canonicalize(node.getOutputSymbols().get(symbolIndex));
                List<Symbol> canonicalInputs = canonicalizeExchangeNodeInputs(node, symbolIndex);
                Symbol output = inputsToOutputs.get(canonicalInputs);

                if (output == null || canonicalOutput.equals(output)) {
                    inputsToOutputs.put(canonicalInputs, canonicalOutput);
                }
                else {
                    map(canonicalOutput, output);
                }
            }
        }

        private void mapExchangeNodeOutputToInputSymbols(ExchangeNode node)
        {
            checkState(node.getInputs().size() == 1);

            for (int symbolIndex = 0; symbolIndex < node.getOutputSymbols().size(); symbolIndex++) {
                Symbol canonicalOutput = canonicalize(node.getOutputSymbols().get(symbolIndex));
                Symbol canonicalInput = canonicalize(node.getInputs().get(0).get(symbolIndex));

                if (!canonicalOutput.equals(canonicalInput)) {
                    map(canonicalOutput, canonicalInput);
                }
            }
        }

        private List<Symbol> canonicalizeExchangeNodeInputs(ExchangeNode node, int symbolIndex)
        {
            return node.getInputs().stream()
                    .map(input -> canonicalize(input.get(symbolIndex)))
                    .collect(toImmutableList());
        }

        @Override
        public PlanNode visitRemoteSource(RemoteSourceNode node, RewriteContext<Void> context)
        {
            return new RemoteSourceNode(
                    node.getId(),
                    node.getSourceFragmentIds(),
                    canonicalizeAndDistinct(node.getOutputSymbols()),
                    node.getOrderingScheme().map(this::canonicalizeAndDistinct),
                    node.getExchangeType());
        }

        @Override
        public PlanNode visitOffset(OffsetNode node, RewriteContext<Void> context)
        {
            return context.defaultRewrite(node);
        }

        @Override
        public PlanNode visitLimit(LimitNode node, RewriteContext<Void> context)
        {
            if (node.isWithTies()) {
                PlanNode source = context.rewrite(node.getSource());
                return new LimitNode(node.getId(), source, node.getCount(), node.getTiesResolvingScheme().map(this::canonicalizeAndDistinct), node.isPartial());
            }
            return context.defaultRewrite(node);
        }

        @Override
        public PlanNode visitDistinctLimit(DistinctLimitNode node, RewriteContext<Void> context)
        {
            return new DistinctLimitNode(node.getId(), context.rewrite(node.getSource()), node.getLimit(), node.isPartial(), canonicalizeAndDistinct(node.getDistinctSymbols()), canonicalize(node.getHashSymbol()));
        }

        @Override
        public PlanNode visitSample(SampleNode node, RewriteContext<Void> context)
        {
            return new SampleNode(node.getId(), context.rewrite(node.getSource()), node.getSampleRatio(), node.getSampleType());
        }

        @Override
        public PlanNode visitValues(ValuesNode node, RewriteContext<Void> context)
        {
            List<List<Expression>> canonicalizedRows = node.getRows().stream()
                    .map(this::canonicalize)
                    .collect(toImmutableList());
            List<Symbol> canonicalizedOutputSymbols = canonicalizeAndDistinct(node.getOutputSymbols());
            checkState(node.getOutputSymbols().size() == canonicalizedOutputSymbols.size(), "Values output symbols were pruned");
            return new ValuesNode(
                    node.getId(),
                    canonicalizedOutputSymbols,
                    canonicalizedRows);
        }

        @Override
        public PlanNode visitTableDelete(TableDeleteNode node, RewriteContext<Void> context)
        {
            return node;
        }

        @Override
        public PlanNode visitDelete(DeleteNode node, RewriteContext<Void> context)
        {
            return new DeleteNode(node.getId(), context.rewrite(node.getSource()), node.getTarget(), canonicalize(node.getRowId()), node.getOutputSymbols());
        }

        @Override
        public PlanNode visitStatisticsWriterNode(StatisticsWriterNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            SymbolMapper mapper = new SymbolMapper(mapping);
            return mapper.map(node, source);
        }

        @Override
        public PlanNode visitTableFinish(TableFinishNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            SymbolMapper mapper = new SymbolMapper(mapping);
            return mapper.map(node, source);
        }

        @Override
        public PlanNode visitRowNumber(RowNumberNode node, RewriteContext<Void> context)
        {
            return new RowNumberNode(node.getId(), context.rewrite(node.getSource()), canonicalizeAndDistinct(node.getPartitionBy()), canonicalize(node.getRowNumberSymbol()), node.getMaxRowCountPerPartition(), canonicalize(node.getHashSymbol()));
        }

        @Override
        public PlanNode visitTopNRowNumber(TopNRowNumberNode node, RewriteContext<Void> context)
        {
            return new TopNRowNumberNode(
                    node.getId(),
                    context.rewrite(node.getSource()),
                    canonicalizeAndDistinct(node.getSpecification()),
                    canonicalize(node.getRowNumberSymbol()),
                    node.getMaxRowCountPerPartition(),
                    node.isPartial(),
                    canonicalize(node.getHashSymbol()));
        }

        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            return new FilterNode(node.getId(), source, canonicalize(node.getPredicate()));
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            return new ProjectNode(node.getId(), source, canonicalize(node.getAssignments()));
        }

        @Override
        public PlanNode visitOutput(OutputNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            List<Symbol> canonical = Lists.transform(node.getOutputSymbols(), this::canonicalize);
            return new OutputNode(node.getId(), source, node.getColumnNames(), canonical);
        }

        @Override
        public PlanNode visitEnforceSingleRow(EnforceSingleRowNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            return new EnforceSingleRowNode(node.getId(), source);
        }

        @Override
        public PlanNode visitAssignUniqueId(AssignUniqueId node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            return new AssignUniqueId(node.getId(), source, node.getIdColumn());
        }

        @Override
        public PlanNode visitApply(ApplyNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getInput());
            PlanNode subquery = context.rewrite(node.getSubquery());
            List<Symbol> canonicalCorrelation = Lists.transform(node.getCorrelation(), this::canonicalize);

            return new ApplyNode(node.getId(), source, subquery, canonicalize(node.getSubqueryAssignments()), canonicalCorrelation, node.getOriginSubquery());
        }

        @Override
        public PlanNode visitCorrelatedJoin(CorrelatedJoinNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getInput());
            PlanNode subquery = context.rewrite(node.getSubquery());
            List<Symbol> canonicalCorrelation = canonicalizeAndDistinct(node.getCorrelation());

            return new CorrelatedJoinNode(node.getId(), source, subquery, canonicalCorrelation, node.getType(), canonicalize(node.getFilter()), node.getOriginSubquery());
        }

        @Override
        public PlanNode visitTopN(TopNNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            SymbolMapper mapper = new SymbolMapper(mapping);
            return mapper.map(node, source, node.getId());
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());

            return new SortNode(node.getId(), source, canonicalizeAndDistinct(node.getOrderingScheme()), node.isPartial());
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Void> context)
        {
            PlanNode left = context.rewrite(node.getLeft());
            PlanNode right = context.rewrite(node.getRight());

            List<JoinNode.EquiJoinClause> canonicalCriteria = canonicalizeJoinCriteria(node.getCriteria());
            Optional<Expression> canonicalFilter = node.getFilter().map(this::canonicalize);
            Optional<Symbol> canonicalLeftHashSymbol = canonicalize(node.getLeftHashSymbol());
            Optional<Symbol> canonicalRightHashSymbol = canonicalize(node.getRightHashSymbol());

            Map<String, Symbol> canonicalDynamicFilters = canonicalizeAndDistinct(node.getDynamicFilters());

            if (node.getType().equals(INNER)) {
                canonicalCriteria.stream()
                        .filter(clause -> types.get(clause.getLeft()).equals(types.get(clause.getRight())))
                        .filter(clause -> node.getOutputSymbols().contains(clause.getLeft()))
                        .forEach(clause -> map(clause.getRight(), clause.getLeft()));
            }

            return new JoinNode(
                    node.getId(),
                    node.getType(),
                    left,
                    right,
                    canonicalCriteria,
                    canonicalizeAndDistinct(node.getOutputSymbols()),
                    canonicalFilter,
                    canonicalLeftHashSymbol,
                    canonicalRightHashSymbol,
                    node.getDistributionType(),
                    node.isSpillable(),
                    canonicalDynamicFilters);
        }

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            PlanNode filteringSource = context.rewrite(node.getFilteringSource());

            return new SemiJoinNode(
                    node.getId(),
                    source,
                    filteringSource,
                    canonicalize(node.getSourceJoinSymbol()),
                    canonicalize(node.getFilteringSourceJoinSymbol()),
                    canonicalize(node.getSemiJoinOutput()),
                    canonicalize(node.getSourceHashSymbol()),
                    canonicalize(node.getFilteringSourceHashSymbol()),
                    node.getDistributionType());
        }

        @Override
        public PlanNode visitSpatialJoin(SpatialJoinNode node, RewriteContext<Void> context)
        {
            PlanNode left = context.rewrite(node.getLeft());
            PlanNode right = context.rewrite(node.getRight());

            return new SpatialJoinNode(node.getId(), node.getType(), left, right, canonicalizeAndDistinct(node.getOutputSymbols()), canonicalize(node.getFilter()), canonicalize(node.getLeftPartitionSymbol()), canonicalize(node.getRightPartitionSymbol()), node.getKdbTree());
        }

        @Override
        public PlanNode visitIndexSource(IndexSourceNode node, RewriteContext<Void> context)
        {
            return new IndexSourceNode(node.getId(), node.getIndexHandle(), node.getTableHandle(), canonicalize(node.getLookupSymbols()), node.getOutputSymbols(), node.getAssignments(), node.getCurrentConstraint());
        }

        @Override
        public PlanNode visitIndexJoin(IndexJoinNode node, RewriteContext<Void> context)
        {
            PlanNode probeSource = context.rewrite(node.getProbeSource());
            PlanNode indexSource = context.rewrite(node.getIndexSource());

            return new IndexJoinNode(node.getId(), node.getType(), probeSource, indexSource, canonicalizeIndexJoinCriteria(node.getCriteria()), canonicalize(node.getProbeHashSymbol()), canonicalize(node.getIndexHashSymbol()));
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<Void> context)
        {
            return new UnionNode(node.getId(), rewriteSources(node, context).build(), canonicalizeSetOperationSymbolMap(node.getSymbolMapping()), canonicalizeAndDistinct(node.getOutputSymbols()));
        }

        @Override
        public PlanNode visitIntersect(IntersectNode node, RewriteContext<Void> context)
        {
            return new IntersectNode(node.getId(), rewriteSources(node, context).build(), canonicalizeSetOperationSymbolMap(node.getSymbolMapping()), canonicalizeAndDistinct(node.getOutputSymbols()));
        }

        @Override
        public PlanNode visitExcept(ExceptNode node, RewriteContext<Void> context)
        {
            return new ExceptNode(node.getId(), rewriteSources(node, context).build(), canonicalizeSetOperationSymbolMap(node.getSymbolMapping()), canonicalizeAndDistinct(node.getOutputSymbols()));
        }

        private static ImmutableList.Builder<PlanNode> rewriteSources(SetOperationNode node, RewriteContext<Void> context)
        {
            ImmutableList.Builder<PlanNode> rewrittenSources = ImmutableList.builder();
            for (PlanNode source : node.getSources()) {
                rewrittenSources.add(context.rewrite(source));
            }
            return rewrittenSources;
        }

        @Override
        public PlanNode visitTableWriter(TableWriterNode node, RewriteContext<Void> context)
        {
            PlanNode source = context.rewrite(node.getSource());
            SymbolMapper mapper = new SymbolMapper(mapping);
            return mapper.map(node, source);
        }

        @Override
        protected PlanNode visitPlan(PlanNode node, RewriteContext<Void> context)
        {
            throw new UnsupportedOperationException("Unsupported plan node " + node.getClass().getSimpleName());
        }

        private void map(Symbol symbol, Symbol canonical)
        {
            Preconditions.checkArgument(!symbol.equals(canonical), "Can't map symbol to itself: %s", symbol);
            mapping.put(symbol, canonical);
        }

        private Assignments canonicalize(Assignments oldAssignments)
        {
            Map<Expression, Symbol> computedExpressions = new HashMap<>();
            Assignments.Builder assignments = Assignments.builder();
            for (Map.Entry<Symbol, Expression> entry : oldAssignments.getMap().entrySet()) {
                Expression expression = canonicalize(entry.getValue());

                if (expression instanceof SymbolReference) {
                    // Always map a trivial symbol projection
                    Symbol symbol = Symbol.from(expression);
                    if (!symbol.equals(entry.getKey())) {
                        map(entry.getKey(), symbol);
                    }
                }
                else if (DeterminismEvaluator.isDeterministic(expression) && !(expression instanceof NullLiteral)) {
                    // Try to map same deterministic expressions within a projection into the same symbol
                    // Omit NullLiterals since those have ambiguous types
                    Symbol computedSymbol = computedExpressions.get(expression);
                    if (computedSymbol == null) {
                        // If we haven't seen the expression before in this projection, record it
                        computedExpressions.put(expression, entry.getKey());
                    }
                    else {
                        // If we have seen the expression before and if it is deterministic
                        // then we can rewrite references to the current symbol in terms of the parallel computedSymbol in the projection
                        map(entry.getKey(), computedSymbol);
                    }
                }

                Symbol canonical = canonicalize(entry.getKey());
                assignments.put(canonical, expression);
            }
            return assignments.build();
        }

        private Optional<Symbol> canonicalize(Optional<Symbol> symbol)
        {
            if (symbol.isPresent()) {
                return Optional.of(canonicalize(symbol.get()));
            }
            return Optional.empty();
        }

        private Symbol canonicalize(Symbol symbol)
        {
            Symbol canonical = symbol;
            while (mapping.containsKey(canonical)) {
                canonical = mapping.get(canonical);
            }
            return canonical;
        }

        private List<Expression> canonicalize(List<Expression> values)
        {
            return values.stream()
                    .map(this::canonicalize)
                    .collect(toImmutableList());
        }

        private Expression canonicalize(Expression value)
        {
            return ExpressionTreeRewriter.rewriteWith(new ExpressionRewriter<Void>()
            {
                @Override
                public Expression rewriteSymbolReference(SymbolReference node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
                {
                    Symbol canonical = canonicalize(Symbol.from(node));
                    return canonical.toSymbolReference();
                }
            }, value);
        }

        private List<Symbol> canonicalizeAndDistinct(List<Symbol> outputs)
        {
            Set<Symbol> added = new HashSet<>();
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
            for (Symbol symbol : outputs) {
                Symbol canonical = canonicalize(symbol);
                if (added.add(canonical)) {
                    builder.add(canonical);
                }
            }
            return builder.build();
        }

        private Map<String, Symbol> canonicalizeAndDistinct(Map<String, Symbol> dynamicFilters)
        {
            Set<Symbol> added = new HashSet<>();
            ImmutableMap.Builder<String, Symbol> builder = ImmutableMap.builder();
            for (Map.Entry<String, Symbol> entry : dynamicFilters.entrySet()) {
                Symbol canonical = canonicalize(entry.getValue());
                if (added.add(canonical)) {
                    builder.put(entry.getKey(), canonical);
                }
            }
            return builder.build();
        }

        private WindowNode.Specification canonicalizeAndDistinct(WindowNode.Specification specification)
        {
            return new WindowNode.Specification(
                    canonicalizeAndDistinct(specification.getPartitionBy()),
                    specification.getOrderingScheme().map(this::canonicalizeAndDistinct));
        }

        private OrderingScheme canonicalizeAndDistinct(OrderingScheme orderingScheme)
        {
            Set<Symbol> added = new HashSet<>();
            ImmutableList.Builder<Symbol> symbols = ImmutableList.builder();
            ImmutableMap.Builder<Symbol, SortOrder> orderings = ImmutableMap.builder();
            for (Symbol symbol : orderingScheme.getOrderBy()) {
                Symbol canonical = canonicalize(symbol);
                if (added.add(canonical)) {
                    symbols.add(canonical);
                    orderings.put(canonical, orderingScheme.getOrdering(symbol));
                }
            }

            return new OrderingScheme(symbols.build(), orderings.build());
        }

        private Set<Symbol> canonicalize(Set<Symbol> symbols)
        {
            return symbols.stream()
                    .map(this::canonicalize)
                    .collect(toImmutableSet());
        }

        private List<JoinNode.EquiJoinClause> canonicalizeJoinCriteria(List<JoinNode.EquiJoinClause> criteria)
        {
            ImmutableList.Builder<JoinNode.EquiJoinClause> builder = ImmutableList.builder();
            for (JoinNode.EquiJoinClause clause : criteria) {
                builder.add(new JoinNode.EquiJoinClause(canonicalize(clause.getLeft()), canonicalize(clause.getRight())));
            }

            return builder.build();
        }

        private List<IndexJoinNode.EquiJoinClause> canonicalizeIndexJoinCriteria(List<IndexJoinNode.EquiJoinClause> criteria)
        {
            ImmutableList.Builder<IndexJoinNode.EquiJoinClause> builder = ImmutableList.builder();
            for (IndexJoinNode.EquiJoinClause clause : criteria) {
                builder.add(new IndexJoinNode.EquiJoinClause(canonicalize(clause.getProbe()), canonicalize(clause.getIndex())));
            }

            return builder.build();
        }

        private ListMultimap<Symbol, Symbol> canonicalizeSetOperationSymbolMap(ListMultimap<Symbol, Symbol> setOperationSymbolMap)
        {
            ImmutableListMultimap.Builder<Symbol, Symbol> builder = ImmutableListMultimap.builder();
            Set<Symbol> addedSymbols = new HashSet<>();
            for (Map.Entry<Symbol, Collection<Symbol>> entry : setOperationSymbolMap.asMap().entrySet()) {
                Symbol canonicalOutputSymbol = canonicalize(entry.getKey());
                if (addedSymbols.add(canonicalOutputSymbol)) {
                    builder.putAll(
                            canonicalOutputSymbol,
                            entry.getValue().stream()
                                    .map(this::canonicalize)
                                    .collect(Collectors.toList()));
                }
            }
            return builder.build();
        }
    }
}
