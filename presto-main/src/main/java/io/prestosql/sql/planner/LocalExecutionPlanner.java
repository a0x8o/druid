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

import com.google.common.base.VerifyException;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.primitives.Ints;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.execution.ExplainAnalyzeContext;
import io.prestosql.execution.StageId;
import io.prestosql.execution.TaskManagerConfig;
import io.prestosql.execution.buffer.OutputBuffer;
import io.prestosql.execution.buffer.PagesSerdeFactory;
import io.prestosql.index.IndexManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.TableHandle;
import io.prestosql.operator.AggregationOperator.AggregationOperatorFactory;
import io.prestosql.operator.AssignUniqueIdOperator;
import io.prestosql.operator.DeleteOperator.DeleteOperatorFactory;
import io.prestosql.operator.DevNullOperator.DevNullOperatorFactory;
import io.prestosql.operator.DriverFactory;
import io.prestosql.operator.DynamicFilterSourceOperator;
import io.prestosql.operator.DynamicFilterSourceOperator.DynamicFilterSourceOperatorFactory;
import io.prestosql.operator.EnforceSingleRowOperator;
import io.prestosql.operator.ExchangeClientSupplier;
import io.prestosql.operator.ExchangeOperator.ExchangeOperatorFactory;
import io.prestosql.operator.ExplainAnalyzeOperator.ExplainAnalyzeOperatorFactory;
import io.prestosql.operator.FilterAndProjectOperator;
import io.prestosql.operator.GroupIdOperator;
import io.prestosql.operator.HashAggregationOperator.HashAggregationOperatorFactory;
import io.prestosql.operator.HashBuilderOperator.HashBuilderOperatorFactory;
import io.prestosql.operator.HashSemiJoinOperator.HashSemiJoinOperatorFactory;
import io.prestosql.operator.JoinBridgeManager;
import io.prestosql.operator.JoinOperatorFactory;
import io.prestosql.operator.JoinOperatorFactory.OuterOperatorFactoryResult;
import io.prestosql.operator.LimitOperator.LimitOperatorFactory;
import io.prestosql.operator.LocalPlannerAware;
import io.prestosql.operator.LookupJoinOperators;
import io.prestosql.operator.LookupOuterOperator.LookupOuterOperatorFactory;
import io.prestosql.operator.LookupSourceFactory;
import io.prestosql.operator.MarkDistinctOperator.MarkDistinctOperatorFactory;
import io.prestosql.operator.MergeOperator.MergeOperatorFactory;
import io.prestosql.operator.NestedLoopJoinBridge;
import io.prestosql.operator.NestedLoopJoinPagesSupplier;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.operator.OrderByOperator.OrderByOperatorFactory;
import io.prestosql.operator.OutputFactory;
import io.prestosql.operator.PagesIndex;
import io.prestosql.operator.PagesSpatialIndexFactory;
import io.prestosql.operator.PartitionFunction;
import io.prestosql.operator.PartitionedLookupSourceFactory;
import io.prestosql.operator.PartitionedOutputOperator.PartitionedOutputFactory;
import io.prestosql.operator.PipelineExecutionStrategy;
import io.prestosql.operator.RowNumberOperator;
import io.prestosql.operator.ScanFilterAndProjectOperator.ScanFilterAndProjectOperatorFactory;
import io.prestosql.operator.SetBuilderOperator.SetBuilderOperatorFactory;
import io.prestosql.operator.SetBuilderOperator.SetSupplier;
import io.prestosql.operator.SourceOperatorFactory;
import io.prestosql.operator.SpatialIndexBuilderOperator.SpatialIndexBuilderOperatorFactory;
import io.prestosql.operator.SpatialIndexBuilderOperator.SpatialPredicate;
import io.prestosql.operator.SpatialJoinOperator.SpatialJoinOperatorFactory;
import io.prestosql.operator.StageExecutionDescriptor;
import io.prestosql.operator.StatisticsWriterOperator.StatisticsWriterOperatorFactory;
import io.prestosql.operator.StreamingAggregationOperator.StreamingAggregationOperatorFactory;
import io.prestosql.operator.TableDeleteOperator.TableDeleteOperatorFactory;
import io.prestosql.operator.TableScanOperator.TableScanOperatorFactory;
import io.prestosql.operator.TaskContext;
import io.prestosql.operator.TaskOutputOperator.TaskOutputFactory;
import io.prestosql.operator.TopNOperator.TopNOperatorFactory;
import io.prestosql.operator.TopNRowNumberOperator;
import io.prestosql.operator.ValuesOperator.ValuesOperatorFactory;
import io.prestosql.operator.WindowFunctionDefinition;
import io.prestosql.operator.WindowOperator.WindowOperatorFactory;
import io.prestosql.operator.WorkProcessorPipelineSourceOperator;
import io.prestosql.operator.aggregation.AccumulatorFactory;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.operator.aggregation.LambdaProvider;
import io.prestosql.operator.exchange.LocalExchange.LocalExchangeFactory;
import io.prestosql.operator.exchange.LocalExchangeSinkOperator.LocalExchangeSinkOperatorFactory;
import io.prestosql.operator.exchange.LocalExchangeSourceOperator.LocalExchangeSourceOperatorFactory;
import io.prestosql.operator.exchange.LocalMergeSourceOperator.LocalMergeSourceOperatorFactory;
import io.prestosql.operator.exchange.PageChannelSelector;
import io.prestosql.operator.index.DynamicTupleFilterFactory;
import io.prestosql.operator.index.FieldSetFilteringRecordSet;
import io.prestosql.operator.index.IndexBuildDriverFactoryProvider;
import io.prestosql.operator.index.IndexJoinLookupStats;
import io.prestosql.operator.index.IndexLookupSourceFactory;
import io.prestosql.operator.index.IndexSourceOperator;
import io.prestosql.operator.project.CursorProcessor;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.operator.window.FrameInfo;
import io.prestosql.operator.window.WindowFunctionSupplier;
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorIndex;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.RecordSet;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.spiller.PartitioningSpillerFactory;
import io.prestosql.spiller.SingleStreamSpillerFactory;
import io.prestosql.spiller.SpillerFactory;
import io.prestosql.split.MappedRecordSet;
import io.prestosql.split.PageSinkManager;
import io.prestosql.split.PageSourceProvider;
import io.prestosql.sql.DynamicFilters;
import io.prestosql.sql.ExpressionUtils;
import io.prestosql.sql.gen.ExpressionCompiler;
import io.prestosql.sql.gen.JoinCompiler;
import io.prestosql.sql.gen.JoinFilterFunctionCompiler;
import io.prestosql.sql.gen.JoinFilterFunctionCompiler.JoinFilterFunctionFactory;
import io.prestosql.sql.gen.OrderingCompiler;
import io.prestosql.sql.gen.PageFunctionCompiler;
import io.prestosql.sql.planner.optimizations.IndexJoinOptimizer;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.AggregationNode.Aggregation;
import io.prestosql.sql.planner.plan.AggregationNode.Step;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.ExplainAnalyzeNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.GroupIdNode;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.IndexSourceNode;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.MarkDistinctNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.sql.planner.plan.PlanVisitor;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.RemoteSourceNode;
import io.prestosql.sql.planner.plan.RowNumberNode;
import io.prestosql.sql.planner.plan.SampleNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.StatisticAggregationsDescriptor;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableDeleteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.TableWriterNode.DeleteTarget;
import io.prestosql.sql.planner.plan.TopNNode;
import io.prestosql.sql.planner.plan.TopNRowNumberNode;
import io.prestosql.sql.planner.plan.UnionNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.planner.plan.WindowNode;
import io.prestosql.sql.planner.plan.WindowNode.Frame;
import io.prestosql.sql.relational.LambdaDefinitionExpression;
import io.prestosql.sql.relational.RowExpression;
import io.prestosql.sql.relational.SqlToRowExpressionTranslator;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.LambdaExpression;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.type.FunctionType;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Functions.forMap;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.DiscreteDomain.integers;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Range.closedOpen;
import static io.airlift.concurrent.MoreFutures.addSuccessCallback;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.prestosql.SystemSessionProperties.getAggregationOperatorUnspillMemoryLimit;
import static io.prestosql.SystemSessionProperties.getDynamicFilteringMaxPerDriverRowCount;
import static io.prestosql.SystemSessionProperties.getDynamicFilteringMaxPerDriverSize;
import static io.prestosql.SystemSessionProperties.getFilterAndProjectMinOutputPageRowCount;
import static io.prestosql.SystemSessionProperties.getFilterAndProjectMinOutputPageSize;
import static io.prestosql.SystemSessionProperties.getTaskConcurrency;
import static io.prestosql.SystemSessionProperties.getTaskWriterCount;
import static io.prestosql.SystemSessionProperties.isEnableDynamicFiltering;
import static io.prestosql.SystemSessionProperties.isExchangeCompressionEnabled;
import static io.prestosql.SystemSessionProperties.isSpillEnabled;
import static io.prestosql.SystemSessionProperties.isSpillOrderBy;
import static io.prestosql.SystemSessionProperties.isSpillWindowOperator;
import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.operator.DistinctLimitOperator.DistinctLimitOperatorFactory;
import static io.prestosql.operator.NestedLoopBuildOperator.NestedLoopBuildOperatorFactory;
import static io.prestosql.operator.NestedLoopJoinOperator.NestedLoopJoinOperatorFactory;
import static io.prestosql.operator.PipelineExecutionStrategy.GROUPED_EXECUTION;
import static io.prestosql.operator.PipelineExecutionStrategy.UNGROUPED_EXECUTION;
import static io.prestosql.operator.TableFinishOperator.TableFinishOperatorFactory;
import static io.prestosql.operator.TableFinishOperator.TableFinisher;
import static io.prestosql.operator.TableWriterOperator.FRAGMENT_CHANNEL;
import static io.prestosql.operator.TableWriterOperator.ROW_COUNT_CHANNEL;
import static io.prestosql.operator.TableWriterOperator.STATS_START_CHANNEL;
import static io.prestosql.operator.TableWriterOperator.TableWriterOperatorFactory;
import static io.prestosql.operator.WindowFunctionDefinition.window;
import static io.prestosql.operator.unnest.UnnestOperator.UnnestOperatorFactory;
import static io.prestosql.spi.StandardErrorCode.COMPILER_ERROR;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.TypeUtils.writeNativeValue;
import static io.prestosql.spiller.PartitioningSpillerFactory.unsupportedPartitioningSpillerFactory;
import static io.prestosql.sql.gen.LambdaBytecodeGenerator.compileLambdaProvider;
import static io.prestosql.sql.planner.ExpressionNodeInliner.replaceExpression;
import static io.prestosql.sql.planner.SystemPartitioningHandle.COORDINATOR_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_ARBITRARY_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_BROADCAST_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SCALED_WRITER_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.prestosql.sql.planner.plan.AggregationNode.Step.FINAL;
import static io.prestosql.sql.planner.plan.AggregationNode.Step.PARTIAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.prestosql.sql.planner.plan.JoinNode.Type.FULL;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.JoinNode.Type.LEFT;
import static io.prestosql.sql.planner.plan.JoinNode.Type.RIGHT;
import static io.prestosql.sql.planner.plan.TableWriterNode.CreateTarget;
import static io.prestosql.sql.planner.plan.TableWriterNode.InsertTarget;
import static io.prestosql.sql.planner.plan.TableWriterNode.WriterTarget;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.prestosql.util.Reflection.constructorMethodHandle;
import static io.prestosql.util.SpatialJoinUtils.ST_CONTAINS;
import static io.prestosql.util.SpatialJoinUtils.ST_DISTANCE;
import static io.prestosql.util.SpatialJoinUtils.ST_INTERSECTS;
import static io.prestosql.util.SpatialJoinUtils.ST_WITHIN;
import static io.prestosql.util.SpatialJoinUtils.extractSupportedSpatialComparisons;
import static io.prestosql.util.SpatialJoinUtils.extractSupportedSpatialFunctions;
import static java.util.Objects.requireNonNull;
import static java.util.stream.IntStream.range;

public class LocalExecutionPlanner
{
    private static final Logger log = Logger.get(LocalExecutionPlanner.class);

    private final Metadata metadata;
    private final TypeAnalyzer typeAnalyzer;
    private final Optional<ExplainAnalyzeContext> explainAnalyzeContext;
    private final PageSourceProvider pageSourceProvider;
    private final IndexManager indexManager;
    private final NodePartitioningManager nodePartitioningManager;
    private final PageSinkManager pageSinkManager;
    private final ExchangeClientSupplier exchangeClientSupplier;
    private final ExpressionCompiler expressionCompiler;
    private final PageFunctionCompiler pageFunctionCompiler;
    private final JoinFilterFunctionCompiler joinFilterFunctionCompiler;
    private final DataSize maxIndexMemorySize;
    private final IndexJoinLookupStats indexJoinLookupStats;
    private final DataSize maxPartialAggregationMemorySize;
    private final DataSize maxPagePartitioningBufferSize;
    private final DataSize maxLocalExchangeBufferSize;
    private final SpillerFactory spillerFactory;
    private final SingleStreamSpillerFactory singleStreamSpillerFactory;
    private final PartitioningSpillerFactory partitioningSpillerFactory;
    private final PagesIndex.Factory pagesIndexFactory;
    private final JoinCompiler joinCompiler;
    private final LookupJoinOperators lookupJoinOperators;
    private final OrderingCompiler orderingCompiler;

    @Inject
    public LocalExecutionPlanner(
            Metadata metadata,
            TypeAnalyzer typeAnalyzer,
            Optional<ExplainAnalyzeContext> explainAnalyzeContext,
            PageSourceProvider pageSourceProvider,
            IndexManager indexManager,
            NodePartitioningManager nodePartitioningManager,
            PageSinkManager pageSinkManager,
            ExchangeClientSupplier exchangeClientSupplier,
            ExpressionCompiler expressionCompiler,
            PageFunctionCompiler pageFunctionCompiler,
            JoinFilterFunctionCompiler joinFilterFunctionCompiler,
            IndexJoinLookupStats indexJoinLookupStats,
            TaskManagerConfig taskManagerConfig,
            SpillerFactory spillerFactory,
            SingleStreamSpillerFactory singleStreamSpillerFactory,
            PartitioningSpillerFactory partitioningSpillerFactory,
            PagesIndex.Factory pagesIndexFactory,
            JoinCompiler joinCompiler,
            LookupJoinOperators lookupJoinOperators,
            OrderingCompiler orderingCompiler)
    {
        this.explainAnalyzeContext = requireNonNull(explainAnalyzeContext, "explainAnalyzeContext is null");
        this.pageSourceProvider = requireNonNull(pageSourceProvider, "pageSourceProvider is null");
        this.indexManager = requireNonNull(indexManager, "indexManager is null");
        this.nodePartitioningManager = requireNonNull(nodePartitioningManager, "nodePartitioningManager is null");
        this.exchangeClientSupplier = exchangeClientSupplier;
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
        this.pageSinkManager = requireNonNull(pageSinkManager, "pageSinkManager is null");
        this.expressionCompiler = requireNonNull(expressionCompiler, "compiler is null");
        this.pageFunctionCompiler = requireNonNull(pageFunctionCompiler, "pageFunctionCompiler is null");
        this.joinFilterFunctionCompiler = requireNonNull(joinFilterFunctionCompiler, "compiler is null");
        this.indexJoinLookupStats = requireNonNull(indexJoinLookupStats, "indexJoinLookupStats is null");
        this.maxIndexMemorySize = requireNonNull(taskManagerConfig, "taskManagerConfig is null").getMaxIndexMemoryUsage();
        this.spillerFactory = requireNonNull(spillerFactory, "spillerFactory is null");
        this.singleStreamSpillerFactory = requireNonNull(singleStreamSpillerFactory, "singleStreamSpillerFactory is null");
        this.partitioningSpillerFactory = requireNonNull(partitioningSpillerFactory, "partitioningSpillerFactory is null");
        this.maxPartialAggregationMemorySize = taskManagerConfig.getMaxPartialAggregationMemoryUsage();
        this.maxPagePartitioningBufferSize = taskManagerConfig.getMaxPagePartitioningBufferSize();
        this.maxLocalExchangeBufferSize = taskManagerConfig.getMaxLocalExchangeBufferSize();
        this.pagesIndexFactory = requireNonNull(pagesIndexFactory, "pagesIndexFactory is null");
        this.joinCompiler = requireNonNull(joinCompiler, "joinCompiler is null");
        this.lookupJoinOperators = requireNonNull(lookupJoinOperators, "lookupJoinOperators is null");
        this.orderingCompiler = requireNonNull(orderingCompiler, "orderingCompiler is null");
    }

    public LocalExecutionPlan plan(
            TaskContext taskContext,
            PlanNode plan,
            TypeProvider types,
            PartitioningScheme partitioningScheme,
            StageExecutionDescriptor stageExecutionDescriptor,
            List<PlanNodeId> partitionedSourceOrder,
            OutputBuffer outputBuffer)
    {
        List<Symbol> outputLayout = partitioningScheme.getOutputLayout();

        if (partitioningScheme.getPartitioning().getHandle().equals(FIXED_BROADCAST_DISTRIBUTION) ||
                partitioningScheme.getPartitioning().getHandle().equals(FIXED_ARBITRARY_DISTRIBUTION) ||
                partitioningScheme.getPartitioning().getHandle().equals(SCALED_WRITER_DISTRIBUTION) ||
                partitioningScheme.getPartitioning().getHandle().equals(SINGLE_DISTRIBUTION) ||
                partitioningScheme.getPartitioning().getHandle().equals(COORDINATOR_DISTRIBUTION)) {
            return plan(taskContext, stageExecutionDescriptor, plan, outputLayout, types, partitionedSourceOrder, new TaskOutputFactory(outputBuffer));
        }

        // We can convert the symbols directly into channels, because the root must be a sink and therefore the layout is fixed
        List<Integer> partitionChannels;
        List<Optional<NullableValue>> partitionConstants;
        List<Type> partitionChannelTypes;
        if (partitioningScheme.getHashColumn().isPresent()) {
            partitionChannels = ImmutableList.of(outputLayout.indexOf(partitioningScheme.getHashColumn().get()));
            partitionConstants = ImmutableList.of(Optional.empty());
            partitionChannelTypes = ImmutableList.of(BIGINT);
        }
        else {
            partitionChannels = partitioningScheme.getPartitioning().getArguments().stream()
                    .map(argument -> {
                        if (argument.isConstant()) {
                            return -1;
                        }
                        return outputLayout.indexOf(argument.getColumn());
                    })
                    .collect(toImmutableList());
            partitionConstants = partitioningScheme.getPartitioning().getArguments().stream()
                    .map(argument -> {
                        if (argument.isConstant()) {
                            return Optional.of(argument.getConstant());
                        }
                        return Optional.<NullableValue>empty();
                    })
                    .collect(toImmutableList());
            partitionChannelTypes = partitioningScheme.getPartitioning().getArguments().stream()
                    .map(argument -> {
                        if (argument.isConstant()) {
                            return argument.getConstant().getType();
                        }
                        return types.get(argument.getColumn());
                    })
                    .collect(toImmutableList());
        }

        PartitionFunction partitionFunction = nodePartitioningManager.getPartitionFunction(taskContext.getSession(), partitioningScheme, partitionChannelTypes);
        OptionalInt nullChannel = OptionalInt.empty();
        Set<Symbol> partitioningColumns = partitioningScheme.getPartitioning().getColumns();

        // partitioningColumns expected to have one column in the normal case, and zero columns when partitioning on a constant
        checkArgument(!partitioningScheme.isReplicateNullsAndAny() || partitioningColumns.size() <= 1);
        if (partitioningScheme.isReplicateNullsAndAny() && partitioningColumns.size() == 1) {
            nullChannel = OptionalInt.of(outputLayout.indexOf(getOnlyElement(partitioningColumns)));
        }

        return plan(
                taskContext,
                stageExecutionDescriptor,
                plan,
                outputLayout,
                types,
                partitionedSourceOrder,
                new PartitionedOutputFactory(
                        partitionFunction,
                        partitionChannels,
                        partitionConstants,
                        partitioningScheme.isReplicateNullsAndAny(),
                        nullChannel,
                        outputBuffer,
                        maxPagePartitioningBufferSize));
    }

    public LocalExecutionPlan plan(
            TaskContext taskContext,
            StageExecutionDescriptor stageExecutionDescriptor,
            PlanNode plan,
            List<Symbol> outputLayout,
            TypeProvider types,
            List<PlanNodeId> partitionedSourceOrder,
            OutputFactory outputOperatorFactory)
    {
        Session session = taskContext.getSession();
        LocalExecutionPlanContext context = new LocalExecutionPlanContext(taskContext, types);

        PhysicalOperation physicalOperation = plan.accept(new Visitor(session, stageExecutionDescriptor), context);

        Function<Page, Page> pagePreprocessor = enforceLayoutProcessor(outputLayout, physicalOperation.getLayout());

        List<Type> outputTypes = outputLayout.stream()
                .map(types::get)
                .collect(toImmutableList());

        context.addDriverFactory(
                context.isInputDriver(),
                true,
                ImmutableList.<OperatorFactory>builder()
                        .addAll(physicalOperation.getOperatorFactories())
                        .add(outputOperatorFactory.createOutputOperator(
                                context.getNextOperatorId(),
                                plan.getId(),
                                outputTypes,
                                pagePreprocessor,
                                new PagesSerdeFactory(metadata.getBlockEncodingSerde(), isExchangeCompressionEnabled(session))))
                        .build(),
                context.getDriverInstanceCount(),
                physicalOperation.getPipelineExecutionStrategy());

        addLookupOuterDrivers(context);

        // notify operator factories that planning has completed
        context.getDriverFactories().stream()
                .map(DriverFactory::getOperatorFactories)
                .flatMap(List::stream)
                .filter(LocalPlannerAware.class::isInstance)
                .map(LocalPlannerAware.class::cast)
                .forEach(LocalPlannerAware::localPlannerComplete);

        return new LocalExecutionPlan(context.getDriverFactories(), partitionedSourceOrder, stageExecutionDescriptor);
    }

    private static void addLookupOuterDrivers(LocalExecutionPlanContext context)
    {
        // For an outer join on the lookup side (RIGHT or FULL) add an additional
        // driver to output the unused rows in the lookup source
        for (DriverFactory factory : context.getDriverFactories()) {
            List<OperatorFactory> operatorFactories = factory.getOperatorFactories();
            for (int i = 0; i < operatorFactories.size(); i++) {
                OperatorFactory operatorFactory = operatorFactories.get(i);
                if (!(operatorFactory instanceof JoinOperatorFactory)) {
                    continue;
                }

                JoinOperatorFactory lookupJoin = (JoinOperatorFactory) operatorFactory;
                Optional<OuterOperatorFactoryResult> outerOperatorFactoryResult = lookupJoin.createOuterOperatorFactory();
                if (outerOperatorFactoryResult.isPresent()) {
                    // Add a new driver to output the unmatched rows in an outer join.
                    // We duplicate all of the factories above the JoinOperator (the ones reading from the joins),
                    // and replace the JoinOperator with the OuterOperator (the one that produces unmatched rows).
                    ImmutableList.Builder<OperatorFactory> newOperators = ImmutableList.builder();
                    newOperators.add(outerOperatorFactoryResult.get().getOuterOperatorFactory());
                    operatorFactories.subList(i + 1, operatorFactories.size()).stream()
                            .map(OperatorFactory::duplicate)
                            .forEach(newOperators::add);

                    context.addDriverFactory(false, factory.isOutputDriver(), newOperators.build(), OptionalInt.of(1), outerOperatorFactoryResult.get().getBuildExecutionStrategy());
                }
            }
        }
    }

    private static class LocalExecutionPlanContext
    {
        private final TaskContext taskContext;
        private final TypeProvider types;
        private final List<DriverFactory> driverFactories;
        private final Optional<IndexSourceContext> indexSourceContext;

        // the collector is shared with all subContexts to allow local dynamic filtering
        // with multiple table scans (e.g. co-located joins).
        private final LocalDynamicFiltersCollector dynamicFiltersCollector;

        // this is shared with all subContexts
        private final AtomicInteger nextPipelineId;

        private int nextOperatorId;
        private boolean inputDriver = true;
        private OptionalInt driverInstanceCount = OptionalInt.empty();

        public LocalExecutionPlanContext(TaskContext taskContext, TypeProvider types)
        {
            this(taskContext, types, new ArrayList<>(), Optional.empty(), new LocalDynamicFiltersCollector(), new AtomicInteger(0));
        }

        private LocalExecutionPlanContext(
                TaskContext taskContext,
                TypeProvider types,
                List<DriverFactory> driverFactories,
                Optional<IndexSourceContext> indexSourceContext,
                LocalDynamicFiltersCollector dynamicFiltersCollector,
                AtomicInteger nextPipelineId)
        {
            this.taskContext = taskContext;
            this.types = types;
            this.driverFactories = driverFactories;
            this.indexSourceContext = indexSourceContext;
            this.dynamicFiltersCollector = dynamicFiltersCollector;
            this.nextPipelineId = nextPipelineId;
        }

        public void addDriverFactory(boolean inputDriver, boolean outputDriver, List<OperatorFactory> operatorFactories, OptionalInt driverInstances, PipelineExecutionStrategy pipelineExecutionStrategy)
        {
            if (pipelineExecutionStrategy == GROUPED_EXECUTION) {
                OperatorFactory firstOperatorFactory = operatorFactories.get(0);
                if (inputDriver) {
                    checkArgument(firstOperatorFactory instanceof ScanFilterAndProjectOperatorFactory || firstOperatorFactory instanceof TableScanOperatorFactory);
                }
                else {
                    checkArgument(firstOperatorFactory instanceof LocalExchangeSourceOperatorFactory || firstOperatorFactory instanceof LookupOuterOperatorFactory);
                }
            }

            if (SystemSessionProperties.isWorkProcessorPipelines(taskContext.getSession())) {
                operatorFactories = WorkProcessorPipelineSourceOperator.convertOperators(getNextOperatorId(), operatorFactories);
            }

            driverFactories.add(new DriverFactory(getNextPipelineId(), inputDriver, outputDriver, operatorFactories, driverInstances, pipelineExecutionStrategy));
        }

        private List<DriverFactory> getDriverFactories()
        {
            return ImmutableList.copyOf(driverFactories);
        }

        public Session getSession()
        {
            return taskContext.getSession();
        }

        public StageId getStageId()
        {
            return taskContext.getTaskId().getStageId();
        }

        public TypeProvider getTypes()
        {
            return types;
        }

        public LocalDynamicFiltersCollector getDynamicFiltersCollector()
        {
            return dynamicFiltersCollector;
        }

        public Optional<IndexSourceContext> getIndexSourceContext()
        {
            return indexSourceContext;
        }

        private int getNextPipelineId()
        {
            return nextPipelineId.getAndIncrement();
        }

        private int getNextOperatorId()
        {
            return nextOperatorId++;
        }

        private boolean isInputDriver()
        {
            return inputDriver;
        }

        private void setInputDriver(boolean inputDriver)
        {
            this.inputDriver = inputDriver;
        }

        public LocalExecutionPlanContext createSubContext()
        {
            checkState(!indexSourceContext.isPresent(), "index build plan can not have sub-contexts");
            return new LocalExecutionPlanContext(taskContext, types, driverFactories, indexSourceContext, dynamicFiltersCollector, nextPipelineId);
        }

        public LocalExecutionPlanContext createIndexSourceSubContext(IndexSourceContext indexSourceContext)
        {
            return new LocalExecutionPlanContext(taskContext, types, driverFactories, Optional.of(indexSourceContext), dynamicFiltersCollector, nextPipelineId);
        }

        public OptionalInt getDriverInstanceCount()
        {
            return driverInstanceCount;
        }

        public void setDriverInstanceCount(int driverInstanceCount)
        {
            checkArgument(driverInstanceCount > 0, "driverInstanceCount must be > 0");
            if (this.driverInstanceCount.isPresent()) {
                checkState(this.driverInstanceCount.getAsInt() == driverInstanceCount, "driverInstance count already set to " + this.driverInstanceCount.getAsInt());
            }
            this.driverInstanceCount = OptionalInt.of(driverInstanceCount);
        }
    }

    private static class IndexSourceContext
    {
        private final SetMultimap<Symbol, Integer> indexLookupToProbeInput;

        public IndexSourceContext(SetMultimap<Symbol, Integer> indexLookupToProbeInput)
        {
            this.indexLookupToProbeInput = ImmutableSetMultimap.copyOf(requireNonNull(indexLookupToProbeInput, "indexLookupToProbeInput is null"));
        }

        private SetMultimap<Symbol, Integer> getIndexLookupToProbeInput()
        {
            return indexLookupToProbeInput;
        }
    }

    public static class LocalExecutionPlan
    {
        private final List<DriverFactory> driverFactories;
        private final List<PlanNodeId> partitionedSourceOrder;
        private final StageExecutionDescriptor stageExecutionDescriptor;

        public LocalExecutionPlan(List<DriverFactory> driverFactories, List<PlanNodeId> partitionedSourceOrder, StageExecutionDescriptor stageExecutionDescriptor)
        {
            this.driverFactories = ImmutableList.copyOf(requireNonNull(driverFactories, "driverFactories is null"));
            this.partitionedSourceOrder = ImmutableList.copyOf(requireNonNull(partitionedSourceOrder, "partitionedSourceOrder is null"));
            this.stageExecutionDescriptor = requireNonNull(stageExecutionDescriptor, "stageExecutionDescriptor is null");
        }

        public List<DriverFactory> getDriverFactories()
        {
            return driverFactories;
        }

        public List<PlanNodeId> getPartitionedSourceOrder()
        {
            return partitionedSourceOrder;
        }

        public StageExecutionDescriptor getStageExecutionDescriptor()
        {
            return stageExecutionDescriptor;
        }
    }

    private class Visitor
            extends PlanVisitor<PhysicalOperation, LocalExecutionPlanContext>
    {
        private final Session session;
        private final StageExecutionDescriptor stageExecutionDescriptor;

        private Visitor(Session session, StageExecutionDescriptor stageExecutionDescriptor)
        {
            this.session = session;
            this.stageExecutionDescriptor = stageExecutionDescriptor;
        }

        @Override
        public PhysicalOperation visitRemoteSource(RemoteSourceNode node, LocalExecutionPlanContext context)
        {
            if (node.getOrderingScheme().isPresent()) {
                return createMergeSource(node, context);
            }

            return createRemoteSource(node, context);
        }

        private PhysicalOperation createMergeSource(RemoteSourceNode node, LocalExecutionPlanContext context)
        {
            checkArgument(node.getOrderingScheme().isPresent(), "orderingScheme is absent");

            // merging remote source must have a single driver
            context.setDriverInstanceCount(1);

            OrderingScheme orderingScheme = node.getOrderingScheme().get();
            ImmutableMap<Symbol, Integer> layout = makeLayout(node);
            List<Integer> sortChannels = getChannelsForSymbols(orderingScheme.getOrderBy(), layout);
            List<SortOrder> sortOrder = orderingScheme.getOrderingList();

            List<Type> types = getSourceOperatorTypes(node, context.getTypes());
            ImmutableList<Integer> outputChannels = IntStream.range(0, types.size())
                    .boxed()
                    .collect(toImmutableList());

            OperatorFactory operatorFactory = new MergeOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    exchangeClientSupplier,
                    new PagesSerdeFactory(metadata.getBlockEncodingSerde(), isExchangeCompressionEnabled(session)),
                    orderingCompiler,
                    types,
                    outputChannels,
                    sortChannels,
                    sortOrder);

            return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
        }

        private PhysicalOperation createRemoteSource(RemoteSourceNode node, LocalExecutionPlanContext context)
        {
            if (!context.getDriverInstanceCount().isPresent()) {
                context.setDriverInstanceCount(getTaskConcurrency(session));
            }

            OperatorFactory operatorFactory = new ExchangeOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    exchangeClientSupplier,
                    new PagesSerdeFactory(metadata.getBlockEncodingSerde(), isExchangeCompressionEnabled(session)));

            return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
        }

        @Override
        public PhysicalOperation visitExplainAnalyze(ExplainAnalyzeNode node, LocalExecutionPlanContext context)
        {
            ExplainAnalyzeContext analyzeContext = explainAnalyzeContext
                    .orElseThrow(() -> new IllegalStateException("ExplainAnalyze can only run on coordinator"));
            PhysicalOperation source = node.getSource().accept(this, context);
            OperatorFactory operatorFactory = new ExplainAnalyzeOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    analyzeContext.getQueryPerformanceFetcher(),
                    metadata,
                    node.isVerbose());
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitOutput(OutputNode node, LocalExecutionPlanContext context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public PhysicalOperation visitRowNumber(RowNumberNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Symbol> partitionBySymbols = node.getPartitionBy();
            List<Integer> partitionChannels = getChannelsForSymbols(partitionBySymbols, source.getLayout());

            List<Type> partitionTypes = partitionChannels.stream()
                    .map(channel -> source.getTypes().get(channel))
                    .collect(toImmutableList());

            ImmutableList.Builder<Integer> outputChannels = ImmutableList.builder();
            for (int i = 0; i < source.getTypes().size(); i++) {
                outputChannels.add(i);
            }

            // compute the layout of the output from the window operator
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            outputMappings.putAll(source.getLayout());

            // row number function goes in the last channel
            int channel = source.getTypes().size();
            outputMappings.put(node.getRowNumberSymbol(), channel);

            Optional<Integer> hashChannel = node.getHashSymbol().map(channelGetter(source));
            OperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    outputChannels.build(),
                    partitionChannels,
                    partitionTypes,
                    node.getMaxRowCountPerPartition(),
                    hashChannel,
                    10_000,
                    joinCompiler);
            return new PhysicalOperation(operatorFactory, outputMappings.build(), context, source);
        }

        @Override
        public PhysicalOperation visitTopNRowNumber(TopNRowNumberNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Symbol> partitionBySymbols = node.getPartitionBy();
            List<Integer> partitionChannels = getChannelsForSymbols(partitionBySymbols, source.getLayout());
            List<Type> partitionTypes = partitionChannels.stream()
                    .map(channel -> source.getTypes().get(channel))
                    .collect(toImmutableList());

            List<Symbol> orderBySymbols = node.getOrderingScheme().getOrderBy();
            List<Integer> sortChannels = getChannelsForSymbols(orderBySymbols, source.getLayout());
            List<SortOrder> sortOrder = orderBySymbols.stream()
                    .map(symbol -> node.getOrderingScheme().getOrdering(symbol))
                    .collect(toImmutableList());

            ImmutableList.Builder<Integer> outputChannels = ImmutableList.builder();
            for (int i = 0; i < source.getTypes().size(); i++) {
                outputChannels.add(i);
            }

            // compute the layout of the output from the window operator
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            outputMappings.putAll(source.getLayout());

            if (!node.isPartial() || !partitionChannels.isEmpty()) {
                // row number function goes in the last channel
                int channel = source.getTypes().size();
                outputMappings.put(node.getRowNumberSymbol(), channel);
            }

            Optional<Integer> hashChannel = node.getHashSymbol().map(channelGetter(source));
            OperatorFactory operatorFactory = new TopNRowNumberOperator.TopNRowNumberOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    outputChannels.build(),
                    partitionChannels,
                    partitionTypes,
                    sortChannels,
                    sortOrder,
                    node.getMaxRowCountPerPartition(),
                    node.isPartial(),
                    hashChannel,
                    1000,
                    joinCompiler);

            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitWindow(WindowNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Symbol> partitionBySymbols = node.getPartitionBy();
            List<Integer> partitionChannels = ImmutableList.copyOf(getChannelsForSymbols(partitionBySymbols, source.getLayout()));
            List<Integer> preGroupedChannels = ImmutableList.copyOf(getChannelsForSymbols(ImmutableList.copyOf(node.getPrePartitionedInputs()), source.getLayout()));

            List<Integer> sortChannels = ImmutableList.of();
            List<SortOrder> sortOrder = ImmutableList.of();

            if (node.getOrderingScheme().isPresent()) {
                OrderingScheme orderingScheme = node.getOrderingScheme().get();
                sortChannels = getChannelsForSymbols(orderingScheme.getOrderBy(), source.getLayout());
                sortOrder = orderingScheme.getOrderingList();
            }

            ImmutableList.Builder<Integer> outputChannels = ImmutableList.builder();
            for (int i = 0; i < source.getTypes().size(); i++) {
                outputChannels.add(i);
            }

            ImmutableList.Builder<WindowFunctionDefinition> windowFunctionsBuilder = ImmutableList.builder();
            ImmutableList.Builder<Symbol> windowFunctionOutputSymbolsBuilder = ImmutableList.builder();
            for (Map.Entry<Symbol, WindowNode.Function> entry : node.getWindowFunctions().entrySet()) {
                Optional<Integer> frameStartChannel = Optional.empty();
                Optional<Integer> frameEndChannel = Optional.empty();

                Frame frame = entry.getValue().getFrame();
                if (frame.getStartValue().isPresent()) {
                    frameStartChannel = Optional.of(source.getLayout().get(frame.getStartValue().get()));
                }
                if (frame.getEndValue().isPresent()) {
                    frameEndChannel = Optional.of(source.getLayout().get(frame.getEndValue().get()));
                }

                FrameInfo frameInfo = new FrameInfo(frame.getType(), frame.getStartType(), frameStartChannel, frame.getEndType(), frameEndChannel);

                WindowNode.Function function = entry.getValue();
                Signature signature = function.getSignature();
                ImmutableList.Builder<Integer> arguments = ImmutableList.builder();
                for (Expression argument : function.getArguments()) {
                    Symbol argumentSymbol = Symbol.from(argument);
                    arguments.add(source.getLayout().get(argumentSymbol));
                }
                Symbol symbol = entry.getKey();
                WindowFunctionSupplier windowFunctionSupplier = metadata.getWindowFunctionImplementation(signature);
                Type type = metadata.getType(signature.getReturnType());
                windowFunctionsBuilder.add(window(windowFunctionSupplier, type, frameInfo, function.isIgnoreNulls(), arguments.build()));
                windowFunctionOutputSymbolsBuilder.add(symbol);
            }

            List<Symbol> windowFunctionOutputSymbols = windowFunctionOutputSymbolsBuilder.build();

            // compute the layout of the output from the window operator
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            for (Symbol symbol : node.getSource().getOutputSymbols()) {
                outputMappings.put(symbol, source.getLayout().get(symbol));
            }

            // window functions go in remaining channels starting after the last channel from the source operator, one per channel
            int channel = source.getTypes().size();
            for (Symbol symbol : windowFunctionOutputSymbols) {
                outputMappings.put(symbol, channel);
                channel++;
            }

            OperatorFactory operatorFactory = new WindowOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    outputChannels.build(),
                    windowFunctionsBuilder.build(),
                    partitionChannels,
                    preGroupedChannels,
                    sortChannels,
                    sortOrder,
                    node.getPreSortedOrderPrefix(),
                    10_000,
                    pagesIndexFactory,
                    isSpillEnabled(session) && isSpillWindowOperator(session),
                    spillerFactory,
                    orderingCompiler);

            return new PhysicalOperation(operatorFactory, outputMappings.build(), context, source);
        }

        @Override
        public PhysicalOperation visitTopN(TopNNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Symbol> orderBySymbols = node.getOrderingScheme().getOrderBy();

            List<Integer> sortChannels = new ArrayList<>();
            List<SortOrder> sortOrders = new ArrayList<>();
            for (Symbol symbol : orderBySymbols) {
                sortChannels.add(source.getLayout().get(symbol));
                sortOrders.add(node.getOrderingScheme().getOrdering(symbol));
            }

            OperatorFactory operator = new TopNOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    (int) node.getCount(),
                    sortChannels,
                    sortOrders);

            return new PhysicalOperation(operator, source.getLayout(), context, source);
        }

        @Override
        public PhysicalOperation visitSort(SortNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Symbol> orderBySymbols = node.getOrderingScheme().getOrderBy();

            List<Integer> orderByChannels = getChannelsForSymbols(orderBySymbols, source.getLayout());

            ImmutableList.Builder<SortOrder> sortOrder = ImmutableList.builder();
            for (Symbol symbol : orderBySymbols) {
                sortOrder.add(node.getOrderingScheme().getOrdering(symbol));
            }

            ImmutableList.Builder<Integer> outputChannels = ImmutableList.builder();
            for (int i = 0; i < source.getTypes().size(); i++) {
                outputChannels.add(i);
            }

            boolean spillEnabled = isSpillEnabled(context.getSession()) && isSpillOrderBy(context.getSession());

            OperatorFactory operator = new OrderByOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    outputChannels.build(),
                    10_000,
                    orderByChannels,
                    sortOrder.build(),
                    pagesIndexFactory,
                    spillEnabled,
                    Optional.of(spillerFactory),
                    orderingCompiler);

            return new PhysicalOperation(operator, source.getLayout(), context, source);
        }

        @Override
        public PhysicalOperation visitLimit(LimitNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            OperatorFactory operatorFactory = new LimitOperatorFactory(context.getNextOperatorId(), node.getId(), node.getCount());
            return new PhysicalOperation(operatorFactory, source.getLayout(), context, source);
        }

        @Override
        public PhysicalOperation visitDistinctLimit(DistinctLimitNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            Optional<Integer> hashChannel = node.getHashSymbol().map(channelGetter(source));
            List<Integer> distinctChannels = getChannelsForSymbols(node.getDistinctSymbols(), source.getLayout());

            OperatorFactory operatorFactory = new DistinctLimitOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    source.getTypes(),
                    distinctChannels,
                    node.getLimit(),
                    hashChannel,
                    joinCompiler);
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitGroupId(GroupIdNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);
            Map<Symbol, Integer> newLayout = new HashMap<>();
            ImmutableList.Builder<Type> outputTypes = ImmutableList.builder();

            int outputChannel = 0;

            for (Symbol output : node.getGroupingSets().stream().flatMap(Collection::stream).collect(Collectors.toSet())) {
                newLayout.put(output, outputChannel++);
                outputTypes.add(source.getTypes().get(source.getLayout().get(node.getGroupingColumns().get(output))));
            }

            Map<Symbol, Integer> argumentMappings = new HashMap<>();
            for (Symbol output : node.getAggregationArguments()) {
                int inputChannel = source.getLayout().get(output);

                newLayout.put(output, outputChannel++);
                outputTypes.add(source.getTypes().get(inputChannel));
                argumentMappings.put(output, inputChannel);
            }

            // for every grouping set, create a mapping of all output to input channels (including arguments)
            ImmutableList.Builder<Map<Integer, Integer>> mappings = ImmutableList.builder();
            for (List<Symbol> groupingSet : node.getGroupingSets()) {
                ImmutableMap.Builder<Integer, Integer> setMapping = ImmutableMap.builder();

                for (Symbol output : groupingSet) {
                    setMapping.put(newLayout.get(output), source.getLayout().get(node.getGroupingColumns().get(output)));
                }

                for (Symbol output : argumentMappings.keySet()) {
                    setMapping.put(newLayout.get(output), argumentMappings.get(output));
                }

                mappings.add(setMapping.build());
            }

            newLayout.put(node.getGroupIdSymbol(), outputChannel);
            outputTypes.add(BIGINT);

            OperatorFactory groupIdOperatorFactory = new GroupIdOperator.GroupIdOperatorFactory(context.getNextOperatorId(),
                    node.getId(),
                    outputTypes.build(),
                    mappings.build());

            return new PhysicalOperation(groupIdOperatorFactory, newLayout, context, source);
        }

        @Override
        public PhysicalOperation visitAggregation(AggregationNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            if (node.getGroupingKeys().isEmpty()) {
                return planGlobalAggregation(node, source, context);
            }

            boolean spillEnabled = isSpillEnabled(context.getSession());
            DataSize unspillMemoryLimit = getAggregationOperatorUnspillMemoryLimit(context.getSession());

            return planGroupByAggregation(node, source, spillEnabled, unspillMemoryLimit, context);
        }

        @Override
        public PhysicalOperation visitMarkDistinct(MarkDistinctNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            List<Integer> channels = getChannelsForSymbols(node.getDistinctSymbols(), source.getLayout());
            Optional<Integer> hashChannel = node.getHashSymbol().map(channelGetter(source));
            MarkDistinctOperatorFactory operator = new MarkDistinctOperatorFactory(context.getNextOperatorId(), node.getId(), source.getTypes(), channels, hashChannel, joinCompiler);
            return new PhysicalOperation(operator, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitSample(SampleNode node, LocalExecutionPlanContext context)
        {
            // For system sample, the splits are already filtered out, so no specific action needs to be taken here
            if (node.getSampleType() == SampleNode.Type.SYSTEM) {
                return node.getSource().accept(this, context);
            }

            throw new UnsupportedOperationException("not yet implemented: " + node);
        }

        @Override
        public PhysicalOperation visitFilter(FilterNode node, LocalExecutionPlanContext context)
        {
            PlanNode sourceNode = node.getSource();

            Expression filterExpression = node.getPredicate();
            List<Symbol> outputSymbols = node.getOutputSymbols();

            return visitScanFilterAndProject(context, node.getId(), sourceNode, Optional.of(filterExpression), Assignments.identity(outputSymbols), outputSymbols);
        }

        @Override
        public PhysicalOperation visitProject(ProjectNode node, LocalExecutionPlanContext context)
        {
            PlanNode sourceNode;
            Optional<Expression> filterExpression = Optional.empty();
            if (node.getSource() instanceof FilterNode) {
                FilterNode filterNode = (FilterNode) node.getSource();
                sourceNode = filterNode.getSource();
                filterExpression = Optional.of(filterNode.getPredicate());
            }
            else {
                sourceNode = node.getSource();
            }

            List<Symbol> outputSymbols = node.getOutputSymbols();

            return visitScanFilterAndProject(context, node.getId(), sourceNode, filterExpression, node.getAssignments(), outputSymbols);
        }

        // TODO: This should be refactored, so that there's an optimizer that merges scan-filter-project into a single PlanNode
        private PhysicalOperation visitScanFilterAndProject(
                LocalExecutionPlanContext context,
                PlanNodeId planNodeId,
                PlanNode sourceNode,
                Optional<Expression> filterExpression,
                Assignments assignments,
                List<Symbol> outputSymbols)
        {
            // if source is a table scan we fold it directly into the filter and project
            // otherwise we plan it as a normal operator
            Map<Symbol, Integer> sourceLayout;
            TableHandle table = null;
            List<ColumnHandle> columns = null;
            PhysicalOperation source = null;
            if (sourceNode instanceof TableScanNode) {
                TableScanNode tableScanNode = (TableScanNode) sourceNode;
                table = tableScanNode.getTable();

                // extract the column handles and channel to type mapping
                sourceLayout = new LinkedHashMap<>();
                columns = new ArrayList<>();
                int channel = 0;
                for (Symbol symbol : tableScanNode.getOutputSymbols()) {
                    columns.add(tableScanNode.getAssignments().get(symbol));

                    Integer input = channel;
                    sourceLayout.put(symbol, input);

                    channel++;
                }
            }
            //TODO: This is a simple hack, it will be replaced when we add ability to push down sampling into connectors.
            // SYSTEM sampling is performed in the coordinator by dropping some random splits so the SamplingNode can be skipped here.
            else if (sourceNode instanceof SampleNode) {
                SampleNode sampleNode = (SampleNode) sourceNode;
                checkArgument(sampleNode.getSampleType() == SampleNode.Type.SYSTEM, "%s sampling is not supported", sampleNode.getSampleType());
                return visitScanFilterAndProject(context,
                        planNodeId,
                        sampleNode.getSource(),
                        filterExpression,
                        assignments,
                        outputSymbols);
            }
            else {
                // plan source
                source = sourceNode.accept(this, context);
                sourceLayout = source.getLayout();
            }

            // build output mapping
            ImmutableMap.Builder<Symbol, Integer> outputMappingsBuilder = ImmutableMap.builder();
            for (int i = 0; i < outputSymbols.size(); i++) {
                Symbol symbol = outputSymbols.get(i);
                outputMappingsBuilder.put(symbol, i);
            }
            Map<Symbol, Integer> outputMappings = outputMappingsBuilder.build();

            Optional<DynamicFilters.ExtractResult> extractDynamicFilterResult = filterExpression.map(DynamicFilters::extractDynamicFilters);
            Optional<Expression> staticFilters = extractDynamicFilterResult
                    .map(DynamicFilters.ExtractResult::getStaticConjuncts)
                    .map(ExpressionUtils::combineConjuncts);

            // TODO: Execution must be plugged in here
            Optional<List<DynamicFilters.Descriptor>> dynamicFilters = extractDynamicFilterResult.map(DynamicFilters.ExtractResult::getDynamicConjuncts);
            Supplier<TupleDomain<ColumnHandle>> dynamicFilterSupplier = null;
            if (dynamicFilters.isPresent() && !dynamicFilters.get().isEmpty()) {
                log.debug("[TableScan] Dynamic filters: %s", dynamicFilters);
                if (sourceNode instanceof TableScanNode) {
                    TableScanNode tableScanNode = (TableScanNode) sourceNode;
                    LocalDynamicFiltersCollector collector = context.getDynamicFiltersCollector();
                    dynamicFilterSupplier = () -> {
                        TupleDomain<Symbol> predicate = collector.getPredicate();
                        return predicate.transform(tableScanNode.getAssignments()::get);
                    };
                }
            }

            List<Expression> projections = new ArrayList<>();
            for (Symbol symbol : outputSymbols) {
                projections.add(assignments.get(symbol));
            }

            Map<NodeRef<Expression>, Type> expressionTypes = typeAnalyzer.getTypes(
                    context.getSession(),
                    context.getTypes(),
                    concat(staticFilters.map(ImmutableList::of).orElse(ImmutableList.of()), assignments.getExpressions()));

            Optional<RowExpression> translatedFilter = staticFilters.map(filter -> toRowExpression(filter, expressionTypes, sourceLayout));
            List<RowExpression> translatedProjections = projections.stream()
                    .map(expression -> toRowExpression(expression, expressionTypes, sourceLayout))
                    .collect(toImmutableList());

            try {
                if (columns != null) {
                    Supplier<CursorProcessor> cursorProcessor = expressionCompiler.compileCursorProcessor(translatedFilter, translatedProjections, sourceNode.getId());
                    Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(translatedFilter, translatedProjections, Optional.of(context.getStageId() + "_" + planNodeId));

                    SourceOperatorFactory operatorFactory = new ScanFilterAndProjectOperatorFactory(
                            context.getNextOperatorId(),
                            planNodeId,
                            sourceNode.getId(),
                            pageSourceProvider,
                            cursorProcessor,
                            pageProcessor,
                            table,
                            columns,
                            dynamicFilterSupplier,
                            getTypes(projections, expressionTypes),
                            getFilterAndProjectMinOutputPageSize(session),
                            getFilterAndProjectMinOutputPageRowCount(session));

                    return new PhysicalOperation(operatorFactory, outputMappings, context, stageExecutionDescriptor.isScanGroupedExecution(sourceNode.getId()) ? GROUPED_EXECUTION : UNGROUPED_EXECUTION);
                }
                else {
                    Supplier<PageProcessor> pageProcessor = expressionCompiler.compilePageProcessor(translatedFilter, translatedProjections, Optional.of(context.getStageId() + "_" + planNodeId));

                    OperatorFactory operatorFactory = new FilterAndProjectOperator.FilterAndProjectOperatorFactory(
                            context.getNextOperatorId(),
                            planNodeId,
                            pageProcessor,
                            getTypes(projections, expressionTypes),
                            getFilterAndProjectMinOutputPageSize(session),
                            getFilterAndProjectMinOutputPageRowCount(session));

                    return new PhysicalOperation(operatorFactory, outputMappings, context, source);
                }
            }
            catch (RuntimeException e) {
                throw new PrestoException(COMPILER_ERROR, "Compiler failed", e);
            }
        }

        private RowExpression toRowExpression(Expression expression, Map<NodeRef<Expression>, Type> types, Map<Symbol, Integer> layout)
        {
            return SqlToRowExpressionTranslator.translate(expression, SCALAR, types, layout, metadata, session, true);
        }

        @Override
        public PhysicalOperation visitTableScan(TableScanNode node, LocalExecutionPlanContext context)
        {
            List<ColumnHandle> columns = new ArrayList<>();
            for (Symbol symbol : node.getOutputSymbols()) {
                columns.add(node.getAssignments().get(symbol));
            }

            OperatorFactory operatorFactory = new TableScanOperatorFactory(context.getNextOperatorId(), node.getId(), pageSourceProvider, node.getTable(), columns);
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, stageExecutionDescriptor.isScanGroupedExecution(node.getId()) ? GROUPED_EXECUTION : UNGROUPED_EXECUTION);
        }

        @Override
        public PhysicalOperation visitValues(ValuesNode node, LocalExecutionPlanContext context)
        {
            // a values node must have a single driver
            context.setDriverInstanceCount(1);

            if (node.getRows().isEmpty()) {
                OperatorFactory operatorFactory = new ValuesOperatorFactory(context.getNextOperatorId(), node.getId(), ImmutableList.of());
                return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
            }

            List<Type> outputTypes = getSymbolTypes(node.getOutputSymbols(), context.getTypes());
            PageBuilder pageBuilder = new PageBuilder(node.getRows().size(), outputTypes);
            for (List<Expression> row : node.getRows()) {
                pageBuilder.declarePosition();
                Map<NodeRef<Expression>, Type> expressionTypes = typeAnalyzer.getTypes(context.getSession(), TypeProvider.empty(), ImmutableList.copyOf(row));
                for (int i = 0; i < row.size(); i++) {
                    // evaluate the literal value
                    Object result = ExpressionInterpreter.expressionInterpreter(row.get(i), metadata, context.getSession(), expressionTypes).evaluate();
                    writeNativeValue(outputTypes.get(i), pageBuilder.getBlockBuilder(i), result);
                }
            }

            OperatorFactory operatorFactory = new ValuesOperatorFactory(context.getNextOperatorId(), node.getId(), ImmutableList.of(pageBuilder.build()));
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
        }

        @Override
        public PhysicalOperation visitUnnest(UnnestNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            ImmutableList.Builder<Type> replicateTypes = ImmutableList.builder();
            for (Symbol symbol : node.getReplicateSymbols()) {
                replicateTypes.add(context.getTypes().get(symbol));
            }
            List<Symbol> unnestSymbols = ImmutableList.copyOf(node.getUnnestSymbols().keySet());
            ImmutableList.Builder<Type> unnestTypes = ImmutableList.builder();
            for (Symbol symbol : unnestSymbols) {
                unnestTypes.add(context.getTypes().get(symbol));
            }
            Optional<Symbol> ordinalitySymbol = node.getOrdinalitySymbol();
            Optional<Type> ordinalityType = ordinalitySymbol.map(context.getTypes()::get);
            ordinalityType.ifPresent(type -> checkState(type.equals(BIGINT), "Type of ordinalitySymbol must always be BIGINT."));

            List<Integer> replicateChannels = getChannelsForSymbols(node.getReplicateSymbols(), source.getLayout());
            List<Integer> unnestChannels = getChannelsForSymbols(unnestSymbols, source.getLayout());

            // Source channels are always laid out first, followed by the unnested symbols
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            int channel = 0;
            for (Symbol symbol : node.getReplicateSymbols()) {
                outputMappings.put(symbol, channel);
                channel++;
            }
            for (Symbol symbol : unnestSymbols) {
                for (Symbol unnestedSymbol : node.getUnnestSymbols().get(symbol)) {
                    outputMappings.put(unnestedSymbol, channel);
                    channel++;
                }
            }
            if (ordinalitySymbol.isPresent()) {
                outputMappings.put(ordinalitySymbol.get(), channel);
                channel++;
            }
            boolean outer = node.getJoinType() == LEFT || node.getJoinType() == FULL;
            OperatorFactory operatorFactory = new UnnestOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    replicateChannels,
                    replicateTypes.build(),
                    unnestChannels,
                    unnestTypes.build(),
                    ordinalityType.isPresent(),
                    outer);
            return new PhysicalOperation(operatorFactory, outputMappings.build(), context, source);
        }

        private ImmutableMap<Symbol, Integer> makeLayout(PlanNode node)
        {
            return makeLayoutFromOutputSymbols(node.getOutputSymbols());
        }

        private ImmutableMap<Symbol, Integer> makeLayoutFromOutputSymbols(List<Symbol> outputSymbols)
        {
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            int channel = 0;
            for (Symbol symbol : outputSymbols) {
                outputMappings.put(symbol, channel);
                channel++;
            }
            return outputMappings.build();
        }

        @Override
        public PhysicalOperation visitIndexSource(IndexSourceNode node, LocalExecutionPlanContext context)
        {
            checkState(context.getIndexSourceContext().isPresent(), "Must be in an index source context");
            IndexSourceContext indexSourceContext = context.getIndexSourceContext().get();

            SetMultimap<Symbol, Integer> indexLookupToProbeInput = indexSourceContext.getIndexLookupToProbeInput();
            checkState(indexLookupToProbeInput.keySet().equals(node.getLookupSymbols()));

            // Finalize the symbol lookup layout for the index source
            List<Symbol> lookupSymbolSchema = ImmutableList.copyOf(node.getLookupSymbols());

            // Identify how to remap the probe key Input to match the source index lookup layout
            ImmutableList.Builder<Integer> remappedProbeKeyChannelsBuilder = ImmutableList.builder();
            // Identify overlapping fields that can produce the same lookup symbol.
            // We will filter incoming keys to ensure that overlapping fields will have the same value.
            ImmutableList.Builder<Set<Integer>> overlappingFieldSetsBuilder = ImmutableList.builder();
            for (Symbol lookupSymbol : lookupSymbolSchema) {
                Set<Integer> potentialProbeInputs = indexLookupToProbeInput.get(lookupSymbol);
                checkState(!potentialProbeInputs.isEmpty(), "Must have at least one source from the probe input");
                if (potentialProbeInputs.size() > 1) {
                    overlappingFieldSetsBuilder.add(potentialProbeInputs.stream().collect(toImmutableSet()));
                }
                remappedProbeKeyChannelsBuilder.add(Iterables.getFirst(potentialProbeInputs, null));
            }
            List<Set<Integer>> overlappingFieldSets = overlappingFieldSetsBuilder.build();
            List<Integer> remappedProbeKeyChannels = remappedProbeKeyChannelsBuilder.build();
            Function<RecordSet, RecordSet> probeKeyNormalizer = recordSet -> {
                if (!overlappingFieldSets.isEmpty()) {
                    recordSet = new FieldSetFilteringRecordSet(metadata, recordSet, overlappingFieldSets);
                }
                return new MappedRecordSet(recordSet, remappedProbeKeyChannels);
            };

            // Declare the input and output schemas for the index and acquire the actual Index
            List<ColumnHandle> lookupSchema = Lists.transform(lookupSymbolSchema, forMap(node.getAssignments()));
            List<ColumnHandle> outputSchema = Lists.transform(node.getOutputSymbols(), forMap(node.getAssignments()));
            ConnectorIndex index = indexManager.getIndex(session, node.getIndexHandle(), lookupSchema, outputSchema);

            OperatorFactory operatorFactory = new IndexSourceOperator.IndexSourceOperatorFactory(context.getNextOperatorId(), node.getId(), index, probeKeyNormalizer);
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
        }

        /**
         * This method creates a mapping from each index source lookup symbol (directly applied to the index)
         * to the corresponding probe key Input
         */
        private SetMultimap<Symbol, Integer> mapIndexSourceLookupSymbolToProbeKeyInput(IndexJoinNode node, Map<Symbol, Integer> probeKeyLayout)
        {
            Set<Symbol> indexJoinSymbols = node.getCriteria().stream()
                    .map(IndexJoinNode.EquiJoinClause::getIndex)
                    .collect(toImmutableSet());

            // Trace the index join symbols to the index source lookup symbols
            // Map: Index join symbol => Index source lookup symbol
            Map<Symbol, Symbol> indexKeyTrace = IndexJoinOptimizer.IndexKeyTracer.trace(node.getIndexSource(), indexJoinSymbols);

            // Map the index join symbols to the probe key Input
            Multimap<Symbol, Integer> indexToProbeKeyInput = HashMultimap.create();
            for (IndexJoinNode.EquiJoinClause clause : node.getCriteria()) {
                indexToProbeKeyInput.put(clause.getIndex(), probeKeyLayout.get(clause.getProbe()));
            }

            // Create the mapping from index source look up symbol to probe key Input
            ImmutableSetMultimap.Builder<Symbol, Integer> builder = ImmutableSetMultimap.builder();
            for (Map.Entry<Symbol, Symbol> entry : indexKeyTrace.entrySet()) {
                Symbol indexJoinSymbol = entry.getKey();
                Symbol indexLookupSymbol = entry.getValue();
                builder.putAll(indexLookupSymbol, indexToProbeKeyInput.get(indexJoinSymbol));
            }
            return builder.build();
        }

        @Override
        public PhysicalOperation visitIndexJoin(IndexJoinNode node, LocalExecutionPlanContext context)
        {
            List<IndexJoinNode.EquiJoinClause> clauses = node.getCriteria();

            List<Symbol> probeSymbols = Lists.transform(clauses, IndexJoinNode.EquiJoinClause::getProbe);
            List<Symbol> indexSymbols = Lists.transform(clauses, IndexJoinNode.EquiJoinClause::getIndex);

            // Plan probe side
            PhysicalOperation probeSource = node.getProbeSource().accept(this, context);
            List<Integer> probeChannels = getChannelsForSymbols(probeSymbols, probeSource.getLayout());
            OptionalInt probeHashChannel = node.getProbeHashSymbol().map(channelGetter(probeSource))
                    .map(OptionalInt::of).orElse(OptionalInt.empty());

            // The probe key channels will be handed to the index according to probeSymbol order
            Map<Symbol, Integer> probeKeyLayout = new HashMap<>();
            for (int i = 0; i < probeSymbols.size(); i++) {
                // Duplicate symbols can appear and we only need to take take one of the Inputs
                probeKeyLayout.put(probeSymbols.get(i), i);
            }

            // Plan the index source side
            SetMultimap<Symbol, Integer> indexLookupToProbeInput = mapIndexSourceLookupSymbolToProbeKeyInput(node, probeKeyLayout);
            LocalExecutionPlanContext indexContext = context.createIndexSourceSubContext(new IndexSourceContext(indexLookupToProbeInput));
            PhysicalOperation indexSource = node.getIndexSource().accept(this, indexContext);
            List<Integer> indexOutputChannels = getChannelsForSymbols(indexSymbols, indexSource.getLayout());
            OptionalInt indexHashChannel = node.getIndexHashSymbol().map(channelGetter(indexSource))
                    .map(OptionalInt::of).orElse(OptionalInt.empty());

            // Identify just the join keys/channels needed for lookup by the index source (does not have to use all of them).
            Set<Symbol> indexSymbolsNeededBySource = IndexJoinOptimizer.IndexKeyTracer.trace(node.getIndexSource(), ImmutableSet.copyOf(indexSymbols)).keySet();

            Set<Integer> lookupSourceInputChannels = node.getCriteria().stream()
                    .filter(equiJoinClause -> indexSymbolsNeededBySource.contains(equiJoinClause.getIndex()))
                    .map(IndexJoinNode.EquiJoinClause::getProbe)
                    .map(probeKeyLayout::get)
                    .collect(toImmutableSet());

            Optional<DynamicTupleFilterFactory> dynamicTupleFilterFactory = Optional.empty();
            if (lookupSourceInputChannels.size() < probeKeyLayout.values().size()) {
                int[] nonLookupInputChannels = Ints.toArray(node.getCriteria().stream()
                        .filter(equiJoinClause -> !indexSymbolsNeededBySource.contains(equiJoinClause.getIndex()))
                        .map(IndexJoinNode.EquiJoinClause::getProbe)
                        .map(probeKeyLayout::get)
                        .collect(toImmutableList()));
                int[] nonLookupOutputChannels = Ints.toArray(node.getCriteria().stream()
                        .filter(equiJoinClause -> !indexSymbolsNeededBySource.contains(equiJoinClause.getIndex()))
                        .map(IndexJoinNode.EquiJoinClause::getIndex)
                        .map(indexSource.getLayout()::get)
                        .collect(toImmutableList()));

                int filterOperatorId = indexContext.getNextOperatorId();
                dynamicTupleFilterFactory = Optional.of(new DynamicTupleFilterFactory(
                        filterOperatorId,
                        node.getId(),
                        nonLookupInputChannels,
                        nonLookupOutputChannels,
                        indexSource.getTypes(),
                        pageFunctionCompiler));
            }

            IndexBuildDriverFactoryProvider indexBuildDriverFactoryProvider = new IndexBuildDriverFactoryProvider(
                    indexContext.getNextPipelineId(),
                    indexContext.getNextOperatorId(),
                    node.getId(),
                    indexContext.isInputDriver(),
                    indexSource.getTypes(),
                    indexSource.getOperatorFactories(),
                    dynamicTupleFilterFactory);

            IndexLookupSourceFactory indexLookupSourceFactory = new IndexLookupSourceFactory(
                    lookupSourceInputChannels,
                    indexOutputChannels,
                    indexHashChannel,
                    indexSource.getTypes(),
                    indexSource.getLayout(),
                    indexBuildDriverFactoryProvider,
                    maxIndexMemorySize,
                    indexJoinLookupStats,
                    SystemSessionProperties.isShareIndexLoading(session),
                    pagesIndexFactory,
                    joinCompiler);

            verify(probeSource.getPipelineExecutionStrategy() == UNGROUPED_EXECUTION);
            verify(indexSource.getPipelineExecutionStrategy() == UNGROUPED_EXECUTION);
            JoinBridgeManager<LookupSourceFactory> lookupSourceFactoryManager = new JoinBridgeManager<>(
                    false,
                    UNGROUPED_EXECUTION,
                    UNGROUPED_EXECUTION,
                    lifespan -> indexLookupSourceFactory,
                    indexLookupSourceFactory.getOutputTypes());

            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            outputMappings.putAll(probeSource.getLayout());

            // inputs from index side of the join are laid out following the input from the probe side,
            // so adjust the channel ids but keep the field layouts intact
            int offset = probeSource.getTypes().size();
            for (Map.Entry<Symbol, Integer> entry : indexSource.getLayout().entrySet()) {
                Integer input = entry.getValue();
                outputMappings.put(entry.getKey(), offset + input);
            }

            OperatorFactory lookupJoinOperatorFactory;
            OptionalInt totalOperatorsCount = context.getDriverInstanceCount();
            switch (node.getType()) {
                case INNER:
                    lookupJoinOperatorFactory = lookupJoinOperators.innerJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeSource.getTypes(), probeChannels, probeHashChannel, Optional.empty(), totalOperatorsCount, unsupportedPartitioningSpillerFactory());
                    break;
                case SOURCE_OUTER:
                    lookupJoinOperatorFactory = lookupJoinOperators.probeOuterJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeSource.getTypes(), probeChannels, probeHashChannel, Optional.empty(), totalOperatorsCount, unsupportedPartitioningSpillerFactory());
                    break;
                default:
                    throw new AssertionError("Unknown type: " + node.getType());
            }
            return new PhysicalOperation(lookupJoinOperatorFactory, outputMappings.build(), context, probeSource);
        }

        @Override
        public PhysicalOperation visitJoin(JoinNode node, LocalExecutionPlanContext context)
        {
            if (node.isCrossJoin()) {
                return createNestedLoopJoin(node, context);
            }

            List<JoinNode.EquiJoinClause> clauses = node.getCriteria();

            // TODO: Execution must be plugged in here
            if (!node.getDynamicFilters().isEmpty()) {
                log.debug("[Join] Dynamic filters: %s", node.getDynamicFilters());
            }

            List<Symbol> leftSymbols = Lists.transform(clauses, JoinNode.EquiJoinClause::getLeft);
            List<Symbol> rightSymbols = Lists.transform(clauses, JoinNode.EquiJoinClause::getRight);

            switch (node.getType()) {
                case INNER:
                case LEFT:
                case RIGHT:
                case FULL:
                    return createLookupJoin(node, node.getLeft(), leftSymbols, node.getLeftHashSymbol(), node.getRight(), rightSymbols, node.getRightHashSymbol(), context);
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
            }
        }

        @Override
        public PhysicalOperation visitSpatialJoin(SpatialJoinNode node, LocalExecutionPlanContext context)
        {
            Expression filterExpression = node.getFilter();
            List<FunctionCall> spatialFunctions = extractSupportedSpatialFunctions(filterExpression);
            for (FunctionCall spatialFunction : spatialFunctions) {
                Optional<PhysicalOperation> operation = tryCreateSpatialJoin(context, node, removeExpressionFromFilter(filterExpression, spatialFunction), spatialFunction, Optional.empty(), Optional.empty());
                if (operation.isPresent()) {
                    return operation.get();
                }
            }

            List<ComparisonExpression> spatialComparisons = extractSupportedSpatialComparisons(filterExpression);
            for (ComparisonExpression spatialComparison : spatialComparisons) {
                if (spatialComparison.getOperator() == LESS_THAN || spatialComparison.getOperator() == LESS_THAN_OR_EQUAL) {
                    // ST_Distance(a, b) <= r
                    Expression radius = spatialComparison.getRight();
                    if (radius instanceof SymbolReference && getSymbolReferences(node.getRight().getOutputSymbols()).contains(radius)) {
                        FunctionCall spatialFunction = (FunctionCall) spatialComparison.getLeft();
                        Optional<PhysicalOperation> operation = tryCreateSpatialJoin(context, node, removeExpressionFromFilter(filterExpression, spatialComparison), spatialFunction, Optional.of(radius), Optional.of(spatialComparison.getOperator()));
                        if (operation.isPresent()) {
                            return operation.get();
                        }
                    }
                }
            }

            throw new VerifyException("No valid spatial relationship found for spatial join");
        }

        private Optional<PhysicalOperation> tryCreateSpatialJoin(
                LocalExecutionPlanContext context,
                SpatialJoinNode node,
                Optional<Expression> filterExpression,
                FunctionCall spatialFunction,
                Optional<Expression> radius,
                Optional<ComparisonExpression.Operator> comparisonOperator)
        {
            List<Expression> arguments = spatialFunction.getArguments();
            verify(arguments.size() == 2);

            if (!(arguments.get(0) instanceof SymbolReference) || !(arguments.get(1) instanceof SymbolReference)) {
                return Optional.empty();
            }

            SymbolReference firstSymbol = (SymbolReference) arguments.get(0);
            SymbolReference secondSymbol = (SymbolReference) arguments.get(1);

            PlanNode probeNode = node.getLeft();
            Set<SymbolReference> probeSymbols = getSymbolReferences(probeNode.getOutputSymbols());

            PlanNode buildNode = node.getRight();
            Set<SymbolReference> buildSymbols = getSymbolReferences(buildNode.getOutputSymbols());

            if (probeSymbols.contains(firstSymbol) && buildSymbols.contains(secondSymbol)) {
                return Optional.of(createSpatialLookupJoin(
                        node,
                        probeNode,
                        Symbol.from(firstSymbol),
                        buildNode,
                        Symbol.from(secondSymbol),
                        radius.map(Symbol::from),
                        spatialTest(spatialFunction, true, comparisonOperator),
                        filterExpression,
                        context));
            }
            if (probeSymbols.contains(secondSymbol) && buildSymbols.contains(firstSymbol)) {
                return Optional.of(createSpatialLookupJoin(
                        node,
                        probeNode,
                        Symbol.from(secondSymbol),
                        buildNode,
                        Symbol.from(firstSymbol),
                        radius.map(Symbol::from),
                        spatialTest(spatialFunction, false, comparisonOperator),
                        filterExpression,
                        context));
            }
            return Optional.empty();
        }

        private Optional<Expression> removeExpressionFromFilter(Expression filter, Expression expression)
        {
            Expression updatedJoinFilter = replaceExpression(filter, ImmutableMap.of(expression, TRUE_LITERAL));
            return updatedJoinFilter == TRUE_LITERAL ? Optional.empty() : Optional.of(updatedJoinFilter);
        }

        private SpatialPredicate spatialTest(FunctionCall functionCall, boolean probeFirst, Optional<ComparisonExpression.Operator> comparisonOperator)
        {
            switch (functionCall.getName().toString().toLowerCase(Locale.ENGLISH)) {
                case ST_CONTAINS:
                    if (probeFirst) {
                        return (buildGeometry, probeGeometry, radius) -> probeGeometry.contains(buildGeometry);
                    }
                    else {
                        return (buildGeometry, probeGeometry, radius) -> buildGeometry.contains(probeGeometry);
                    }
                case ST_WITHIN:
                    if (probeFirst) {
                        return (buildGeometry, probeGeometry, radius) -> probeGeometry.within(buildGeometry);
                    }
                    else {
                        return (buildGeometry, probeGeometry, radius) -> buildGeometry.within(probeGeometry);
                    }
                case ST_INTERSECTS:
                    return (buildGeometry, probeGeometry, radius) -> buildGeometry.intersects(probeGeometry);
                case ST_DISTANCE:
                    if (comparisonOperator.get() == LESS_THAN) {
                        return (buildGeometry, probeGeometry, radius) -> buildGeometry.distance(probeGeometry) < radius.getAsDouble();
                    }
                    else if (comparisonOperator.get() == LESS_THAN_OR_EQUAL) {
                        return (buildGeometry, probeGeometry, radius) -> buildGeometry.distance(probeGeometry) <= radius.getAsDouble();
                    }
                    else {
                        throw new UnsupportedOperationException("Unsupported comparison operator: " + comparisonOperator.get());
                    }
                default:
                    throw new UnsupportedOperationException("Unsupported spatial function: " + functionCall.getName());
            }
        }

        private Set<SymbolReference> getSymbolReferences(Collection<Symbol> symbols)
        {
            return symbols.stream().map(Symbol::toSymbolReference).collect(toImmutableSet());
        }

        private PhysicalOperation createNestedLoopJoin(JoinNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation probeSource = node.getLeft().accept(this, context);

            LocalExecutionPlanContext buildContext = context.createSubContext();
            PhysicalOperation buildSource = node.getRight().accept(this, buildContext);

            checkState(
                    buildSource.getPipelineExecutionStrategy() == UNGROUPED_EXECUTION,
                    "Build source of a nested loop join is expected to be GROUPED_EXECUTION.");
            checkArgument(node.getType() == INNER, "NestedLoopJoin is only used for inner join");

            JoinBridgeManager<NestedLoopJoinBridge> nestedLoopJoinBridgeManager = new JoinBridgeManager<>(
                    false,
                    probeSource.getPipelineExecutionStrategy(),
                    buildSource.getPipelineExecutionStrategy(),
                    lifespan -> new NestedLoopJoinPagesSupplier(),
                    buildSource.getTypes());
            NestedLoopBuildOperatorFactory nestedLoopBuildOperatorFactory = new NestedLoopBuildOperatorFactory(
                    buildContext.getNextOperatorId(),
                    node.getId(),
                    nestedLoopJoinBridgeManager);

            checkArgument(buildContext.getDriverInstanceCount().orElse(1) == 1, "Expected local execution to not be parallel");
            context.addDriverFactory(
                    buildContext.isInputDriver(),
                    false,
                    ImmutableList.<OperatorFactory>builder()
                            .addAll(buildSource.getOperatorFactories())
                            .add(nestedLoopBuildOperatorFactory)
                            .build(),
                    buildContext.getDriverInstanceCount(),
                    buildSource.getPipelineExecutionStrategy());

            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            outputMappings.putAll(probeSource.getLayout());

            // inputs from build side of the join are laid out following the input from the probe side,
            // so adjust the channel ids but keep the field layouts intact
            int offset = probeSource.getTypes().size();
            for (Map.Entry<Symbol, Integer> entry : buildSource.getLayout().entrySet()) {
                outputMappings.put(entry.getKey(), offset + entry.getValue());
            }

            OperatorFactory operatorFactory = new NestedLoopJoinOperatorFactory(context.getNextOperatorId(), node.getId(), nestedLoopJoinBridgeManager);
            return new PhysicalOperation(operatorFactory, outputMappings.build(), context, probeSource);
        }

        private PhysicalOperation createSpatialLookupJoin(
                SpatialJoinNode node,
                PlanNode probeNode,
                Symbol probeSymbol,
                PlanNode buildNode,
                Symbol buildSymbol,
                Optional<Symbol> radiusSymbol,
                SpatialPredicate spatialRelationshipTest,
                Optional<Expression> joinFilter,
                LocalExecutionPlanContext context)
        {
            // Plan probe
            PhysicalOperation probeSource = probeNode.accept(this, context);

            // Plan build
            PagesSpatialIndexFactory pagesSpatialIndexFactory = createPagesSpatialIndexFactory(node,
                    buildNode,
                    buildSymbol,
                    radiusSymbol,
                    probeSource.getLayout(),
                    spatialRelationshipTest,
                    joinFilter,
                    context);

            OperatorFactory operator = createSpatialLookupJoin(node, probeNode, probeSource, probeSymbol, pagesSpatialIndexFactory, context);

            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            List<Symbol> outputSymbols = node.getOutputSymbols();
            for (int i = 0; i < outputSymbols.size(); i++) {
                Symbol symbol = outputSymbols.get(i);
                outputMappings.put(symbol, i);
            }

            return new PhysicalOperation(operator, outputMappings.build(), context, probeSource);
        }

        private OperatorFactory createSpatialLookupJoin(SpatialJoinNode node,
                PlanNode probeNode,
                PhysicalOperation probeSource,
                Symbol probeSymbol,
                PagesSpatialIndexFactory pagesSpatialIndexFactory,
                LocalExecutionPlanContext context)
        {
            List<Type> probeTypes = probeSource.getTypes();
            List<Symbol> probeOutputSymbols = node.getOutputSymbols().stream()
                    .filter(symbol -> probeNode.getOutputSymbols().contains(symbol))
                    .collect(toImmutableList());
            List<Integer> probeOutputChannels = ImmutableList.copyOf(getChannelsForSymbols(probeOutputSymbols, probeSource.getLayout()));
            Function<Symbol, Integer> probeChannelGetter = channelGetter(probeSource);
            int probeChannel = probeChannelGetter.apply(probeSymbol);

            Optional<Integer> partitionChannel = node.getLeftPartitionSymbol().map(probeChannelGetter::apply);

            return new SpatialJoinOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    node.getType(),
                    probeTypes,
                    probeOutputChannels,
                    probeChannel,
                    partitionChannel,
                    pagesSpatialIndexFactory);
        }

        private PagesSpatialIndexFactory createPagesSpatialIndexFactory(
                SpatialJoinNode node,
                PlanNode buildNode,
                Symbol buildSymbol,
                Optional<Symbol> radiusSymbol,
                Map<Symbol, Integer> probeLayout,
                SpatialPredicate spatialRelationshipTest,
                Optional<Expression> joinFilter,
                LocalExecutionPlanContext context)
        {
            LocalExecutionPlanContext buildContext = context.createSubContext();
            PhysicalOperation buildSource = buildNode.accept(this, buildContext);
            List<Symbol> buildOutputSymbols = node.getOutputSymbols().stream()
                    .filter(symbol -> buildNode.getOutputSymbols().contains(symbol))
                    .collect(toImmutableList());
            Map<Symbol, Integer> buildLayout = buildSource.getLayout();
            List<Integer> buildOutputChannels = ImmutableList.copyOf(getChannelsForSymbols(buildOutputSymbols, buildLayout));
            Function<Symbol, Integer> buildChannelGetter = channelGetter(buildSource);
            Integer buildChannel = buildChannelGetter.apply(buildSymbol);
            Optional<Integer> radiusChannel = radiusSymbol.map(buildChannelGetter::apply);

            Optional<JoinFilterFunctionFactory> filterFunctionFactory = joinFilter
                    .map(filterExpression -> compileJoinFilterFunction(
                            filterExpression,
                            probeLayout,
                            buildLayout,
                            context.getTypes(),
                            context.getSession()));

            Optional<Integer> partitionChannel = node.getRightPartitionSymbol().map(buildChannelGetter::apply);

            SpatialIndexBuilderOperatorFactory builderOperatorFactory = new SpatialIndexBuilderOperatorFactory(
                    buildContext.getNextOperatorId(),
                    node.getId(),
                    buildSource.getTypes(),
                    buildOutputChannels,
                    buildChannel,
                    radiusChannel,
                    partitionChannel,
                    spatialRelationshipTest,
                    node.getKdbTree(),
                    filterFunctionFactory,
                    10_000,
                    pagesIndexFactory);

            context.addDriverFactory(
                    buildContext.isInputDriver(),
                    false,
                    ImmutableList.<OperatorFactory>builder()
                            .addAll(buildSource.getOperatorFactories())
                            .add(builderOperatorFactory)
                            .build(),
                    buildContext.getDriverInstanceCount(),
                    buildSource.getPipelineExecutionStrategy());

            return builderOperatorFactory.getPagesSpatialIndexFactory();
        }

        private PhysicalOperation createLookupJoin(JoinNode node,
                PlanNode probeNode,
                List<Symbol> probeSymbols,
                Optional<Symbol> probeHashSymbol,
                PlanNode buildNode,
                List<Symbol> buildSymbols,
                Optional<Symbol> buildHashSymbol,
                LocalExecutionPlanContext context)
        {
            // Plan probe
            PhysicalOperation probeSource = probeNode.accept(this, context);

            // Plan build
            boolean spillEnabled = isSpillEnabled(session) && node.isSpillable().orElseThrow(() -> new IllegalArgumentException("spillable not yet set"));
            JoinBridgeManager<PartitionedLookupSourceFactory> lookupSourceFactory =
                    createLookupSourceFactory(node, buildNode, buildSymbols, buildHashSymbol, probeSource, context, spillEnabled);

            OperatorFactory operator = createLookupJoin(node, probeSource, probeSymbols, probeHashSymbol, lookupSourceFactory, context, spillEnabled);

            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            List<Symbol> outputSymbols = node.getOutputSymbols();
            for (int i = 0; i < outputSymbols.size(); i++) {
                Symbol symbol = outputSymbols.get(i);
                outputMappings.put(symbol, i);
            }

            return new PhysicalOperation(operator, outputMappings.build(), context, probeSource);
        }

        private JoinBridgeManager<PartitionedLookupSourceFactory> createLookupSourceFactory(
                JoinNode node,
                PlanNode buildNode,
                List<Symbol> buildSymbols,
                Optional<Symbol> buildHashSymbol,
                PhysicalOperation probeSource,
                LocalExecutionPlanContext context,
                boolean spillEnabled)
        {
            LocalExecutionPlanContext buildContext = context.createSubContext();
            PhysicalOperation buildSource = buildNode.accept(this, buildContext);

            if (buildSource.getPipelineExecutionStrategy() == GROUPED_EXECUTION) {
                checkState(
                        probeSource.getPipelineExecutionStrategy() == GROUPED_EXECUTION,
                        "Build execution is GROUPED_EXECUTION. Probe execution is expected be GROUPED_EXECUTION, but is UNGROUPED_EXECUTION.");
            }

            List<Symbol> buildOutputSymbols = node.getOutputSymbols().stream()
                    .filter(symbol -> node.getRight().getOutputSymbols().contains(symbol))
                    .collect(toImmutableList());
            List<Integer> buildOutputChannels = ImmutableList.copyOf(getChannelsForSymbols(buildOutputSymbols, buildSource.getLayout()));
            List<Integer> buildChannels = ImmutableList.copyOf(getChannelsForSymbols(buildSymbols, buildSource.getLayout()));
            OptionalInt buildHashChannel = buildHashSymbol.map(channelGetter(buildSource))
                    .map(OptionalInt::of).orElse(OptionalInt.empty());

            boolean buildOuter = node.getType() == RIGHT || node.getType() == FULL;
            int partitionCount = buildContext.getDriverInstanceCount().orElse(1);

            Optional<JoinFilterFunctionFactory> filterFunctionFactory = node.getFilter()
                    .map(filterExpression -> compileJoinFilterFunction(
                            filterExpression,
                            probeSource.getLayout(),
                            buildSource.getLayout(),
                            context.getTypes(),
                            context.getSession()));

            Optional<SortExpressionContext> sortExpressionContext = node.getSortExpressionContext();

            Optional<Integer> sortChannel = sortExpressionContext
                    .map(SortExpressionContext::getSortExpression)
                    .map(Symbol::from)
                    .map(sortSymbol -> createJoinSourcesLayout(buildSource.getLayout(), probeSource.getLayout()).get(sortSymbol));

            List<JoinFilterFunctionFactory> searchFunctionFactories = sortExpressionContext
                    .map(SortExpressionContext::getSearchExpressions)
                    .map(searchExpressions -> searchExpressions.stream()
                            .map(searchExpression -> compileJoinFilterFunction(
                                    searchExpression,
                                    probeSource.getLayout(),
                                    buildSource.getLayout(),
                                    context.getTypes(),
                                    context.getSession()))
                            .collect(toImmutableList()))
                    .orElse(ImmutableList.of());

            ImmutableList<Type> buildOutputTypes = buildOutputChannels.stream()
                    .map(buildSource.getTypes()::get)
                    .collect(toImmutableList());
            JoinBridgeManager<PartitionedLookupSourceFactory> lookupSourceFactoryManager = new JoinBridgeManager<>(
                    buildOuter,
                    probeSource.getPipelineExecutionStrategy(),
                    buildSource.getPipelineExecutionStrategy(),
                    lifespan -> new PartitionedLookupSourceFactory(
                            buildSource.getTypes(),
                            buildOutputTypes,
                            buildChannels.stream()
                                    .map(buildSource.getTypes()::get)
                                    .collect(toImmutableList()),
                            partitionCount,
                            buildSource.getLayout(),
                            buildOuter),
                    buildOutputTypes);

            ImmutableList.Builder<OperatorFactory> factoriesBuilder = new ImmutableList.Builder<>();
            factoriesBuilder.addAll(buildSource.getOperatorFactories());

            createDynamicFilter(node, context, partitionCount).ifPresent(
                    filter -> factoriesBuilder.add(createDynamicFilterSourceOperatorFactory(filter, node, buildSource, buildContext)));

            HashBuilderOperatorFactory hashBuilderOperatorFactory = new HashBuilderOperatorFactory(
                    buildContext.getNextOperatorId(),
                    node.getId(),
                    lookupSourceFactoryManager,
                    buildOutputChannels,
                    buildChannels,
                    buildHashChannel,
                    filterFunctionFactory,
                    sortChannel,
                    searchFunctionFactories,
                    10_000,
                    pagesIndexFactory,
                    spillEnabled && !buildOuter && partitionCount > 1,
                    singleStreamSpillerFactory);

            factoriesBuilder.add(hashBuilderOperatorFactory);

            context.addDriverFactory(
                    buildContext.isInputDriver(),
                    false,
                    factoriesBuilder.build(),
                    buildContext.getDriverInstanceCount(),
                    buildSource.getPipelineExecutionStrategy());

            return lookupSourceFactoryManager;
        }

        private DynamicFilterSourceOperatorFactory createDynamicFilterSourceOperatorFactory(
                LocalDynamicFilter dynamicFilter,
                JoinNode node,
                PhysicalOperation buildSource,
                LocalExecutionPlanContext context)
        {
            List<DynamicFilterSourceOperator.Channel> filterBuildChannels = dynamicFilter.getBuildChannels().entrySet().stream()
                    .map(entry -> {
                        String filterId = entry.getKey();
                        int index = entry.getValue();
                        Type type = buildSource.getTypes().get(index);
                        return new DynamicFilterSourceOperator.Channel(filterId, type, index);
                    })
                    .collect(Collectors.toList());
            return new DynamicFilterSourceOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    dynamicFilter.getTupleDomainConsumer(),
                    filterBuildChannels,
                    getDynamicFilteringMaxPerDriverRowCount(context.getSession()),
                    getDynamicFilteringMaxPerDriverSize(context.getSession()));
        }

        private Optional<LocalDynamicFilter> createDynamicFilter(JoinNode node, LocalExecutionPlanContext context, int partitionCount)
        {
            if (!isEnableDynamicFiltering(context.getSession())) {
                return Optional.empty();
            }
            if (node.getDynamicFilters().isEmpty()) {
                return Optional.empty();
            }
            LocalDynamicFiltersCollector collector = context.getDynamicFiltersCollector();
            return LocalDynamicFilter
                    .create(node, partitionCount)
                    .map(filter -> {
                        // Intersect dynamic filters' predicates when they become ready,
                        // in order to support multiple join nodes in the same plan fragment.
                        addSuccessCallback(filter.getResultFuture(), collector::intersect);
                        return filter;
                    });
        }

        private JoinFilterFunctionFactory compileJoinFilterFunction(
                Expression filterExpression,
                Map<Symbol, Integer> probeLayout,
                Map<Symbol, Integer> buildLayout,
                TypeProvider types,
                Session session)
        {
            Map<Symbol, Integer> joinSourcesLayout = createJoinSourcesLayout(buildLayout, probeLayout);

            RowExpression translatedFilter = toRowExpression(filterExpression, typeAnalyzer.getTypes(session, types, filterExpression), joinSourcesLayout);
            return joinFilterFunctionCompiler.compileJoinFilterFunction(translatedFilter, buildLayout.size());
        }

        private OperatorFactory createLookupJoin(
                JoinNode node,
                PhysicalOperation probeSource,
                List<Symbol> probeSymbols,
                Optional<Symbol> probeHashSymbol,
                JoinBridgeManager<? extends LookupSourceFactory> lookupSourceFactoryManager,
                LocalExecutionPlanContext context,
                boolean spillEnabled)
        {
            List<Type> probeTypes = probeSource.getTypes();
            List<Symbol> probeOutputSymbols = node.getOutputSymbols().stream()
                    .filter(symbol -> node.getLeft().getOutputSymbols().contains(symbol))
                    .collect(toImmutableList());
            List<Integer> probeOutputChannels = ImmutableList.copyOf(getChannelsForSymbols(probeOutputSymbols, probeSource.getLayout()));
            List<Integer> probeJoinChannels = ImmutableList.copyOf(getChannelsForSymbols(probeSymbols, probeSource.getLayout()));
            OptionalInt probeHashChannel = probeHashSymbol.map(channelGetter(probeSource))
                    .map(OptionalInt::of).orElse(OptionalInt.empty());
            OptionalInt totalOperatorsCount = context.getDriverInstanceCount();
            checkState(!spillEnabled || totalOperatorsCount.isPresent(), "A fixed distribution is required for JOIN when spilling is enabled");

            switch (node.getType()) {
                case INNER:
                    return lookupJoinOperators.innerJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeTypes, probeJoinChannels, probeHashChannel, Optional.of(probeOutputChannels), totalOperatorsCount, partitioningSpillerFactory);
                case LEFT:
                    return lookupJoinOperators.probeOuterJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeTypes, probeJoinChannels, probeHashChannel, Optional.of(probeOutputChannels), totalOperatorsCount, partitioningSpillerFactory);
                case RIGHT:
                    return lookupJoinOperators.lookupOuterJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeTypes, probeJoinChannels, probeHashChannel, Optional.of(probeOutputChannels), totalOperatorsCount, partitioningSpillerFactory);
                case FULL:
                    return lookupJoinOperators.fullOuterJoin(context.getNextOperatorId(), node.getId(), lookupSourceFactoryManager, probeTypes, probeJoinChannels, probeHashChannel, Optional.of(probeOutputChannels), totalOperatorsCount, partitioningSpillerFactory);
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
            }
        }

        private Map<Symbol, Integer> createJoinSourcesLayout(Map<Symbol, Integer> lookupSourceLayout, Map<Symbol, Integer> probeSourceLayout)
        {
            ImmutableMap.Builder<Symbol, Integer> joinSourcesLayout = ImmutableMap.builder();
            joinSourcesLayout.putAll(lookupSourceLayout);
            for (Map.Entry<Symbol, Integer> probeLayoutEntry : probeSourceLayout.entrySet()) {
                joinSourcesLayout.put(probeLayoutEntry.getKey(), probeLayoutEntry.getValue() + lookupSourceLayout.size());
            }
            return joinSourcesLayout.build();
        }

        @Override
        public PhysicalOperation visitSemiJoin(SemiJoinNode node, LocalExecutionPlanContext context)
        {
            // Plan probe
            PhysicalOperation probeSource = node.getSource().accept(this, context);

            // Plan build
            LocalExecutionPlanContext buildContext = context.createSubContext();
            PhysicalOperation buildSource = node.getFilteringSource().accept(this, buildContext);
            checkState(buildSource.getPipelineExecutionStrategy() == probeSource.getPipelineExecutionStrategy(), "build and probe have different pipelineExecutionStrategy");
            checkArgument(buildContext.getDriverInstanceCount().orElse(1) == 1, "Expected local execution to not be parallel");

            int probeChannel = probeSource.getLayout().get(node.getSourceJoinSymbol());
            int buildChannel = buildSource.getLayout().get(node.getFilteringSourceJoinSymbol());

            Optional<Integer> buildHashChannel = node.getFilteringSourceHashSymbol().map(channelGetter(buildSource));
            Optional<Integer> probeHashChannel = node.getSourceHashSymbol().map(channelGetter(probeSource));

            SetBuilderOperatorFactory setBuilderOperatorFactory = new SetBuilderOperatorFactory(
                    buildContext.getNextOperatorId(),
                    node.getId(),
                    buildSource.getTypes().get(buildChannel),
                    buildChannel,
                    buildHashChannel,
                    10_000,
                    joinCompiler);
            SetSupplier setProvider = setBuilderOperatorFactory.getSetProvider();
            context.addDriverFactory(
                    buildContext.isInputDriver(),
                    false,
                    ImmutableList.<OperatorFactory>builder()
                            .addAll(buildSource.getOperatorFactories())
                            .add(setBuilderOperatorFactory)
                            .build(),
                    buildContext.getDriverInstanceCount(),
                    buildSource.getPipelineExecutionStrategy());

            // Source channels are always laid out first, followed by the boolean output symbol
            Map<Symbol, Integer> outputMappings = ImmutableMap.<Symbol, Integer>builder()
                    .putAll(probeSource.getLayout())
                    .put(node.getSemiJoinOutput(), probeSource.getLayout().size())
                    .build();

            HashSemiJoinOperatorFactory operator = new HashSemiJoinOperatorFactory(context.getNextOperatorId(), node.getId(), setProvider, probeSource.getTypes(), probeChannel, probeHashChannel);
            return new PhysicalOperation(operator, outputMappings, context, probeSource);
        }

        @Override
        public PhysicalOperation visitTableWriter(TableWriterNode node, LocalExecutionPlanContext context)
        {
            // Set table writer count
            if (node.getPartitioningScheme().isPresent()) {
                context.setDriverInstanceCount(1);
            }
            else {
                context.setDriverInstanceCount(getTaskWriterCount(session));
            }

            // serialize writes by forcing data through a single writer
            PhysicalOperation source = node.getSource().accept(this, context);

            ImmutableMap.Builder<Symbol, Integer> outputMapping = ImmutableMap.builder();
            outputMapping.put(node.getOutputSymbols().get(0), ROW_COUNT_CHANNEL);
            outputMapping.put(node.getOutputSymbols().get(1), FRAGMENT_CHANNEL);

            OperatorFactory statisticsAggregation = node.getStatisticsAggregation().map(aggregation -> {
                List<Symbol> groupingSymbols = aggregation.getGroupingSymbols();
                if (groupingSymbols.isEmpty()) {
                    return createAggregationOperatorFactory(
                            node.getId(),
                            aggregation.getAggregations(),
                            PARTIAL,
                            STATS_START_CHANNEL,
                            outputMapping,
                            source,
                            context,
                            true);
                }
                return createHashAggregationOperatorFactory(
                        node.getId(),
                        aggregation.getAggregations(),
                        ImmutableSet.of(),
                        groupingSymbols,
                        PARTIAL,
                        Optional.empty(),
                        Optional.empty(),
                        source,
                        false,
                        false,
                        false,
                        new DataSize(0, BYTE),
                        context,
                        STATS_START_CHANNEL,
                        outputMapping,
                        200,
                        // This aggregation must behave as INTERMEDIATE.
                        // Using INTERMEDIATE aggregation directly
                        // is not possible, as it doesn't accept raw input data.
                        // Disabling partial pre-aggregation memory limit effectively
                        // turns PARTIAL aggregation into INTERMEDIATE.
                        Optional.empty(),
                        true);
            }).orElse(new DevNullOperatorFactory(context.getNextOperatorId(), node.getId()));

            List<Integer> inputChannels = node.getColumns().stream()
                    .map(source::symbolToChannel)
                    .collect(toImmutableList());

            OperatorFactory operatorFactory = new TableWriterOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    pageSinkManager,
                    node.getTarget(),
                    inputChannels,
                    session,
                    statisticsAggregation,
                    getSymbolTypes(node.getOutputSymbols(), context.getTypes()));

            return new PhysicalOperation(operatorFactory, outputMapping.build(), context, source);
        }

        @Override
        public PhysicalOperation visitStatisticsWriterNode(StatisticsWriterNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            StatisticAggregationsDescriptor<Integer> descriptor = node.getDescriptor().map(symbol -> source.getLayout().get(symbol));

            OperatorFactory operatorFactory = new StatisticsWriterOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    computedStatistics -> metadata.finishStatisticsCollection(session, ((StatisticsWriterNode.WriteStatisticsHandle) node.getTarget()).getHandle(), computedStatistics),
                    node.isRowCountEnabled(),
                    descriptor);
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitTableFinish(TableFinishNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            ImmutableMap.Builder<Symbol, Integer> outputMapping = ImmutableMap.builder();

            OperatorFactory statisticsAggregation = node.getStatisticsAggregation().map(aggregation -> {
                List<Symbol> groupingSymbols = aggregation.getGroupingSymbols();
                if (groupingSymbols.isEmpty()) {
                    return createAggregationOperatorFactory(
                            node.getId(),
                            aggregation.getAggregations(),
                            FINAL,
                            0,
                            outputMapping,
                            source,
                            context,
                            true);
                }
                return createHashAggregationOperatorFactory(
                        node.getId(),
                        aggregation.getAggregations(),
                        ImmutableSet.of(),
                        groupingSymbols,
                        FINAL,
                        Optional.empty(),
                        Optional.empty(),
                        source,
                        false,
                        false,
                        false,
                        new DataSize(0, BYTE),
                        context,
                        0,
                        outputMapping,
                        200,
                        // final aggregation ignores partial pre-aggregation memory limit
                        Optional.empty(),
                        true);
            }).orElse(new DevNullOperatorFactory(context.getNextOperatorId(), node.getId()));

            Map<Symbol, Integer> aggregationOutput = outputMapping.build();
            StatisticAggregationsDescriptor<Integer> descriptor = node.getStatisticsAggregationDescriptor()
                    .map(desc -> desc.map(aggregationOutput::get))
                    .orElse(StatisticAggregationsDescriptor.empty());

            OperatorFactory operatorFactory = new TableFinishOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    createTableFinisher(session, node, metadata),
                    statisticsAggregation,
                    descriptor,
                    session);
            Map<Symbol, Integer> layout = ImmutableMap.of(node.getOutputSymbols().get(0), 0);

            return new PhysicalOperation(operatorFactory, layout, context, source);
        }

        @Override
        public PhysicalOperation visitDelete(DeleteNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            OperatorFactory operatorFactory = new DeleteOperatorFactory(context.getNextOperatorId(), node.getId(), source.getLayout().get(node.getRowId()));

            Map<Symbol, Integer> layout = ImmutableMap.<Symbol, Integer>builder()
                    .put(node.getOutputSymbols().get(0), 0)
                    .put(node.getOutputSymbols().get(1), 1)
                    .build();

            return new PhysicalOperation(operatorFactory, layout, context, source);
        }

        @Override
        public PhysicalOperation visitTableDelete(TableDeleteNode node, LocalExecutionPlanContext context)
        {
            OperatorFactory operatorFactory = new TableDeleteOperatorFactory(context.getNextOperatorId(), node.getId(), metadata, session, node.getTarget());

            return new PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION);
        }

        @Override
        public PhysicalOperation visitUnion(UnionNode node, LocalExecutionPlanContext context)
        {
            throw new UnsupportedOperationException("Union node should not be present in a local execution plan");
        }

        @Override
        public PhysicalOperation visitEnforceSingleRow(EnforceSingleRowNode node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            OperatorFactory operatorFactory = new EnforceSingleRowOperator.EnforceSingleRowOperatorFactory(context.getNextOperatorId(), node.getId());
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitAssignUniqueId(AssignUniqueId node, LocalExecutionPlanContext context)
        {
            PhysicalOperation source = node.getSource().accept(this, context);

            OperatorFactory operatorFactory = new AssignUniqueIdOperator.AssignUniqueIdOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId());
            return new PhysicalOperation(operatorFactory, makeLayout(node), context, source);
        }

        @Override
        public PhysicalOperation visitExchange(ExchangeNode node, LocalExecutionPlanContext context)
        {
            checkArgument(node.getScope() == LOCAL, "Only local exchanges are supported in the local planner");

            if (node.getOrderingScheme().isPresent()) {
                return createLocalMerge(node, context);
            }

            return createLocalExchange(node, context);
        }

        private PhysicalOperation createLocalMerge(ExchangeNode node, LocalExecutionPlanContext context)
        {
            checkArgument(node.getOrderingScheme().isPresent(), "orderingScheme is absent");
            checkState(node.getSources().size() == 1, "single source is expected");

            // local merge source must have a single driver
            context.setDriverInstanceCount(1);

            PlanNode sourceNode = getOnlyElement(node.getSources());
            LocalExecutionPlanContext subContext = context.createSubContext();
            PhysicalOperation source = sourceNode.accept(this, subContext);

            int operatorsCount = subContext.getDriverInstanceCount().orElse(1);
            List<Type> types = getSourceOperatorTypes(node, context.getTypes());
            LocalExchangeFactory exchangeFactory = new LocalExchangeFactory(
                    node.getPartitioningScheme().getPartitioning().getHandle(),
                    operatorsCount,
                    types,
                    ImmutableList.of(),
                    Optional.empty(),
                    source.getPipelineExecutionStrategy(),
                    maxLocalExchangeBufferSize);

            List<OperatorFactory> operatorFactories = new ArrayList<>(source.getOperatorFactories());
            List<Symbol> expectedLayout = node.getInputs().get(0);
            Function<Page, Page> pagePreprocessor = enforceLayoutProcessor(expectedLayout, source.getLayout());
            operatorFactories.add(new LocalExchangeSinkOperatorFactory(
                    exchangeFactory,
                    subContext.getNextOperatorId(),
                    node.getId(),
                    exchangeFactory.newSinkFactoryId(),
                    pagePreprocessor));
            context.addDriverFactory(subContext.isInputDriver(), false, operatorFactories, subContext.getDriverInstanceCount(), source.getPipelineExecutionStrategy());
            // the main driver is not an input... the exchange sources are the input for the plan
            context.setInputDriver(false);

            OrderingScheme orderingScheme = node.getOrderingScheme().get();
            ImmutableMap<Symbol, Integer> layout = makeLayout(node);
            List<Integer> sortChannels = getChannelsForSymbols(orderingScheme.getOrderBy(), layout);
            List<SortOrder> orderings = orderingScheme.getOrderingList();
            OperatorFactory operatorFactory = new LocalMergeSourceOperatorFactory(
                    context.getNextOperatorId(),
                    node.getId(),
                    exchangeFactory,
                    types,
                    orderingCompiler,
                    sortChannels,
                    orderings);
            return new PhysicalOperation(operatorFactory, layout, context, UNGROUPED_EXECUTION);
        }

        private PhysicalOperation createLocalExchange(ExchangeNode node, LocalExecutionPlanContext context)
        {
            int driverInstanceCount;
            if (node.getType() == ExchangeNode.Type.GATHER) {
                driverInstanceCount = 1;
                context.setDriverInstanceCount(1);
            }
            else if (context.getDriverInstanceCount().isPresent()) {
                driverInstanceCount = context.getDriverInstanceCount().getAsInt();
            }
            else {
                driverInstanceCount = getTaskConcurrency(session);
                context.setDriverInstanceCount(driverInstanceCount);
            }

            List<Type> types = getSourceOperatorTypes(node, context.getTypes());
            List<Integer> channels = node.getPartitioningScheme().getPartitioning().getArguments().stream()
                    .map(argument -> node.getOutputSymbols().indexOf(argument.getColumn()))
                    .collect(toImmutableList());
            Optional<Integer> hashChannel = node.getPartitioningScheme().getHashColumn()
                    .map(symbol -> node.getOutputSymbols().indexOf(symbol));

            PipelineExecutionStrategy exchangeSourcePipelineExecutionStrategy = GROUPED_EXECUTION;
            List<DriverFactoryParameters> driverFactoryParametersList = new ArrayList<>();
            for (int i = 0; i < node.getSources().size(); i++) {
                PlanNode sourceNode = node.getSources().get(i);

                LocalExecutionPlanContext subContext = context.createSubContext();
                PhysicalOperation source = sourceNode.accept(this, subContext);
                driverFactoryParametersList.add(new DriverFactoryParameters(subContext, source));

                if (source.getPipelineExecutionStrategy() == UNGROUPED_EXECUTION) {
                    exchangeSourcePipelineExecutionStrategy = UNGROUPED_EXECUTION;
                }
            }

            LocalExchangeFactory localExchangeFactory = new LocalExchangeFactory(
                    node.getPartitioningScheme().getPartitioning().getHandle(),
                    driverInstanceCount,
                    types,
                    channels,
                    hashChannel,
                    exchangeSourcePipelineExecutionStrategy,
                    maxLocalExchangeBufferSize);
            for (int i = 0; i < node.getSources().size(); i++) {
                DriverFactoryParameters driverFactoryParameters = driverFactoryParametersList.get(i);
                PhysicalOperation source = driverFactoryParameters.getSource();
                LocalExecutionPlanContext subContext = driverFactoryParameters.getSubContext();

                List<Symbol> expectedLayout = node.getInputs().get(i);
                Function<Page, Page> pagePreprocessor = enforceLayoutProcessor(expectedLayout, source.getLayout());
                List<OperatorFactory> operatorFactories = new ArrayList<>(source.getOperatorFactories());

                operatorFactories.add(new LocalExchangeSinkOperatorFactory(
                        localExchangeFactory,
                        subContext.getNextOperatorId(),
                        node.getId(),
                        localExchangeFactory.newSinkFactoryId(),
                        pagePreprocessor));
                context.addDriverFactory(
                        subContext.isInputDriver(),
                        false,
                        operatorFactories,
                        subContext.getDriverInstanceCount(),
                        exchangeSourcePipelineExecutionStrategy);
            }

            // the main driver is not an input... the exchange sources are the input for the plan
            context.setInputDriver(false);

            // instance count must match the number of partitions in the exchange
            verify(context.getDriverInstanceCount().getAsInt() == localExchangeFactory.getBufferCount(),
                    "driver instance count must match the number of exchange partitions");

            return new PhysicalOperation(new LocalExchangeSourceOperatorFactory(context.getNextOperatorId(), node.getId(), localExchangeFactory), makeLayout(node), context, exchangeSourcePipelineExecutionStrategy);
        }

        @Override
        protected PhysicalOperation visitPlan(PlanNode node, LocalExecutionPlanContext context)
        {
            throw new UnsupportedOperationException("not yet implemented");
        }

        private List<Type> getSourceOperatorTypes(PlanNode node, TypeProvider types)
        {
            return getSymbolTypes(node.getOutputSymbols(), types);
        }

        private List<Type> getSymbolTypes(List<Symbol> symbols, TypeProvider types)
        {
            return symbols.stream()
                    .map(types::get)
                    .collect(toImmutableList());
        }

        private AccumulatorFactory buildAccumulatorFactory(
                PhysicalOperation source,
                Aggregation aggregation)
        {
            InternalAggregationFunction internalAggregationFunction = metadata.getAggregateFunctionImplementation(aggregation.getSignature());

            List<Integer> valueChannels = new ArrayList<>();
            for (Expression argument : aggregation.getArguments()) {
                if (!(argument instanceof LambdaExpression)) {
                    Symbol argumentSymbol = Symbol.from(argument);
                    valueChannels.add(source.getLayout().get(argumentSymbol));
                }
            }

            List<LambdaProvider> lambdaProviders = new ArrayList<>();
            List<LambdaExpression> lambdaExpressions = aggregation.getArguments().stream()
                    .filter(LambdaExpression.class::isInstance)
                    .map(LambdaExpression.class::cast)
                    .collect(toImmutableList());
            if (!lambdaExpressions.isEmpty()) {
                List<FunctionType> functionTypes = aggregation.getSignature().getArgumentTypes().stream()
                        .filter(typeSignature -> typeSignature.getBase().equals(FunctionType.NAME))
                        .map(metadata::getType)
                        .map(FunctionType.class::cast)
                        .collect(toImmutableList());
                List<Class<?>> lambdaInterfaces = internalAggregationFunction.getLambdaInterfaces();
                verify(lambdaExpressions.size() == functionTypes.size());
                verify(lambdaExpressions.size() == lambdaInterfaces.size());

                for (int i = 0; i < lambdaExpressions.size(); i++) {
                    LambdaExpression lambdaExpression = lambdaExpressions.get(i);
                    FunctionType functionType = functionTypes.get(i);

                    // To compile lambda, LambdaDefinitionExpression needs to be generated from LambdaExpression,
                    // which requires the types of all sub-expressions.
                    //
                    // In project and filter expression compilation, ExpressionAnalyzer.getExpressionTypesFromInput
                    // is used to generate the types of all sub-expressions. (see visitScanFilterAndProject and visitFilter)
                    //
                    // This does not work here since the function call representation in final aggregation node
                    // is currently a hack: it takes intermediate type as input, and may not be a valid
                    // function call in Presto.
                    //
                    // TODO: Once the final aggregation function call representation is fixed,
                    // the same mechanism in project and filter expression should be used here.
                    verify(lambdaExpression.getArguments().size() == functionType.getArgumentTypes().size());
                    Map<NodeRef<Expression>, Type> lambdaArgumentExpressionTypes = new HashMap<>();
                    Map<Symbol, Type> lambdaArgumentSymbolTypes = new HashMap<>();
                    for (int j = 0; j < lambdaExpression.getArguments().size(); j++) {
                        LambdaArgumentDeclaration argument = lambdaExpression.getArguments().get(j);
                        Type type = functionType.getArgumentTypes().get(j);
                        lambdaArgumentExpressionTypes.put(NodeRef.of(argument), type);
                        lambdaArgumentSymbolTypes.put(new Symbol(argument.getName().getValue()), type);
                    }
                    Map<NodeRef<Expression>, Type> expressionTypes = ImmutableMap.<NodeRef<Expression>, Type>builder()
                            // the lambda expression itself
                            .put(NodeRef.of(lambdaExpression), functionType)
                            // expressions from lambda arguments
                            .putAll(lambdaArgumentExpressionTypes)
                            // expressions from lambda body
                            .putAll(typeAnalyzer.getTypes(session, TypeProvider.copyOf(lambdaArgumentSymbolTypes), lambdaExpression.getBody()))
                            .build();

                    LambdaDefinitionExpression lambda = (LambdaDefinitionExpression) toRowExpression(lambdaExpression, expressionTypes, ImmutableMap.of());
                    Class<? extends LambdaProvider> lambdaProviderClass = compileLambdaProvider(lambda, metadata, lambdaInterfaces.get(i));
                    try {
                        lambdaProviders.add((LambdaProvider) constructorMethodHandle(lambdaProviderClass, ConnectorSession.class).invoke(session.toConnectorSession()));
                    }
                    catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
            }

            Optional<Integer> maskChannel = aggregation.getMask().map(value -> source.getLayout().get(value));
            List<SortOrder> sortOrders = ImmutableList.of();
            List<Symbol> sortKeys = ImmutableList.of();
            if (aggregation.getOrderingScheme().isPresent()) {
                OrderingScheme orderingScheme = aggregation.getOrderingScheme().get();
                sortKeys = orderingScheme.getOrderBy();
                sortOrders = sortKeys.stream()
                        .map(orderingScheme::getOrdering)
                        .collect(toImmutableList());
            }

            return internalAggregationFunction.bind(
                    valueChannels,
                    maskChannel,
                    source.getTypes(),
                    getChannelsForSymbols(sortKeys, source.getLayout()),
                    sortOrders,
                    pagesIndexFactory,
                    aggregation.isDistinct(),
                    joinCompiler,
                    lambdaProviders,
                    session);
        }

        private PhysicalOperation planGlobalAggregation(AggregationNode node, PhysicalOperation source, LocalExecutionPlanContext context)
        {
            ImmutableMap.Builder<Symbol, Integer> outputMappings = ImmutableMap.builder();
            AggregationOperatorFactory operatorFactory = createAggregationOperatorFactory(
                    node.getId(),
                    node.getAggregations(),
                    node.getStep(),
                    0,
                    outputMappings,
                    source,
                    context,
                    node.getStep().isOutputPartial());
            return new PhysicalOperation(operatorFactory, outputMappings.build(), context, source);
        }

        private AggregationOperatorFactory createAggregationOperatorFactory(
                PlanNodeId planNodeId,
                Map<Symbol, Aggregation> aggregations,
                Step step,
                int startOutputChannel,
                ImmutableMap.Builder<Symbol, Integer> outputMappings,
                PhysicalOperation source,
                LocalExecutionPlanContext context,
                boolean useSystemMemory)
        {
            int outputChannel = startOutputChannel;
            ImmutableList.Builder<AccumulatorFactory> accumulatorFactories = ImmutableList.builder();
            for (Map.Entry<Symbol, Aggregation> entry : aggregations.entrySet()) {
                Symbol symbol = entry.getKey();
                Aggregation aggregation = entry.getValue();
                accumulatorFactories.add(buildAccumulatorFactory(source, aggregation));
                outputMappings.put(symbol, outputChannel); // one aggregation per channel
                outputChannel++;
            }
            return new AggregationOperatorFactory(context.getNextOperatorId(), planNodeId, step, accumulatorFactories.build(), useSystemMemory);
        }

        private PhysicalOperation planGroupByAggregation(
                AggregationNode node,
                PhysicalOperation source,
                boolean spillEnabled,
                DataSize unspillMemoryLimit,
                LocalExecutionPlanContext context)
        {
            ImmutableMap.Builder<Symbol, Integer> mappings = ImmutableMap.builder();
            OperatorFactory operatorFactory = createHashAggregationOperatorFactory(
                    node.getId(),
                    node.getAggregations(),
                    node.getGlobalGroupingSets(),
                    node.getGroupingKeys(),
                    node.getStep(),
                    node.getHashSymbol(),
                    node.getGroupIdSymbol(),
                    source,
                    node.hasDefaultOutput(),
                    spillEnabled,
                    node.isStreamable(),
                    unspillMemoryLimit,
                    context,
                    0,
                    mappings,
                    10_000,
                    Optional.of(maxPartialAggregationMemorySize),
                    node.getStep().isOutputPartial());
            return new PhysicalOperation(operatorFactory, mappings.build(), context, source);
        }

        private OperatorFactory createHashAggregationOperatorFactory(
                PlanNodeId planNodeId,
                Map<Symbol, Aggregation> aggregations,
                Set<Integer> globalGroupingSets,
                List<Symbol> groupBySymbols,
                Step step,
                Optional<Symbol> hashSymbol,
                Optional<Symbol> groupIdSymbol,
                PhysicalOperation source,
                boolean hasDefaultOutput,
                boolean spillEnabled,
                boolean isStreamable,
                DataSize unspillMemoryLimit,
                LocalExecutionPlanContext context,
                int startOutputChannel,
                ImmutableMap.Builder<Symbol, Integer> outputMappings,
                int expectedGroups,
                Optional<DataSize> maxPartialAggregationMemorySize,
                boolean useSystemMemory)
        {
            List<Symbol> aggregationOutputSymbols = new ArrayList<>();
            List<AccumulatorFactory> accumulatorFactories = new ArrayList<>();
            for (Map.Entry<Symbol, Aggregation> entry : aggregations.entrySet()) {
                Symbol symbol = entry.getKey();
                Aggregation aggregation = entry.getValue();

                accumulatorFactories.add(buildAccumulatorFactory(source, aggregation));
                aggregationOutputSymbols.add(symbol);
            }

            // add group-by key fields each in a separate channel
            int channel = startOutputChannel;
            Optional<Integer> groupIdChannel = Optional.empty();
            for (Symbol symbol : groupBySymbols) {
                outputMappings.put(symbol, channel);
                if (groupIdSymbol.isPresent() && groupIdSymbol.get().equals(symbol)) {
                    groupIdChannel = Optional.of(channel);
                }
                channel++;
            }

            // hashChannel follows the group by channels
            if (hashSymbol.isPresent()) {
                outputMappings.put(hashSymbol.get(), channel++);
            }

            // aggregations go in following channels
            for (Symbol symbol : aggregationOutputSymbols) {
                outputMappings.put(symbol, channel);
                channel++;
            }

            List<Integer> groupByChannels = getChannelsForSymbols(groupBySymbols, source.getLayout());
            List<Type> groupByTypes = groupByChannels.stream()
                    .map(entry -> source.getTypes().get(entry))
                    .collect(toImmutableList());

            if (isStreamable) {
                return new StreamingAggregationOperatorFactory(
                        context.getNextOperatorId(),
                        planNodeId,
                        source.getTypes(),
                        groupByTypes,
                        groupByChannels,
                        step,
                        accumulatorFactories,
                        joinCompiler);
            }
            else {
                Optional<Integer> hashChannel = hashSymbol.map(channelGetter(source));
                return new HashAggregationOperatorFactory(
                        context.getNextOperatorId(),
                        planNodeId,
                        groupByTypes,
                        groupByChannels,
                        ImmutableList.copyOf(globalGroupingSets),
                        step,
                        hasDefaultOutput,
                        accumulatorFactories,
                        hashChannel,
                        groupIdChannel,
                        expectedGroups,
                        maxPartialAggregationMemorySize,
                        spillEnabled,
                        unspillMemoryLimit,
                        spillerFactory,
                        joinCompiler,
                        useSystemMemory);
            }
        }
    }

    private static List<Type> getTypes(List<Expression> expressions, Map<NodeRef<Expression>, Type> expressionTypes)
    {
        return expressions.stream()
                .map(NodeRef::of)
                .map(expressionTypes::get)
                .collect(toImmutableList());
    }

    private static TableFinisher createTableFinisher(Session session, TableFinishNode node, Metadata metadata)
    {
        WriterTarget target = node.getTarget();
        return (fragments, statistics) -> {
            if (target instanceof CreateTarget) {
                return metadata.finishCreateTable(session, ((CreateTarget) target).getHandle(), fragments, statistics);
            }
            else if (target instanceof InsertTarget) {
                return metadata.finishInsert(session, ((InsertTarget) target).getHandle(), fragments, statistics);
            }
            else if (target instanceof DeleteTarget) {
                metadata.finishDelete(session, ((DeleteTarget) target).getHandle(), fragments);
                return Optional.empty();
            }
            else {
                throw new AssertionError("Unhandled target type: " + target.getClass().getName());
            }
        };
    }

    private static Function<Page, Page> enforceLayoutProcessor(List<Symbol> expectedLayout, Map<Symbol, Integer> inputLayout)
    {
        int[] channels = expectedLayout.stream()
                .peek(symbol -> checkArgument(inputLayout.containsKey(symbol), "channel not found for symbol: %s", symbol))
                .mapToInt(inputLayout::get)
                .toArray();

        if (Arrays.equals(channels, range(0, inputLayout.size()).toArray())) {
            // this is an identity mapping
            return Function.identity();
        }

        return new PageChannelSelector(channels);
    }

    private static List<Integer> getChannelsForSymbols(List<Symbol> symbols, Map<Symbol, Integer> layout)
    {
        ImmutableList.Builder<Integer> builder = ImmutableList.builder();
        for (Symbol symbol : symbols) {
            builder.add(layout.get(symbol));
        }
        return builder.build();
    }

    private static Function<Symbol, Integer> channelGetter(PhysicalOperation source)
    {
        return input -> {
            checkArgument(source.getLayout().containsKey(input));
            return source.getLayout().get(input);
        };
    }

    /**
     * Encapsulates an physical operator plus the mapping of logical symbols to channel/field
     */
    private static class PhysicalOperation
    {
        private final List<OperatorFactory> operatorFactories;
        private final Map<Symbol, Integer> layout;
        private final List<Type> types;

        private final PipelineExecutionStrategy pipelineExecutionStrategy;

        public PhysicalOperation(OperatorFactory operatorFactory, Map<Symbol, Integer> layout, LocalExecutionPlanContext context, PipelineExecutionStrategy pipelineExecutionStrategy)
        {
            this(operatorFactory, layout, context, Optional.empty(), pipelineExecutionStrategy);
        }

        public PhysicalOperation(OperatorFactory operatorFactory, Map<Symbol, Integer> layout, LocalExecutionPlanContext context, PhysicalOperation source)
        {
            this(operatorFactory, layout, context, Optional.of(requireNonNull(source, "source is null")), source.getPipelineExecutionStrategy());
        }

        private PhysicalOperation(
                OperatorFactory operatorFactory,
                Map<Symbol, Integer> layout,
                LocalExecutionPlanContext context,
                Optional<PhysicalOperation> source,
                PipelineExecutionStrategy pipelineExecutionStrategy)
        {
            requireNonNull(operatorFactory, "operatorFactory is null");
            requireNonNull(layout, "layout is null");
            requireNonNull(context, "context is null");
            requireNonNull(source, "source is null");
            requireNonNull(pipelineExecutionStrategy, "pipelineExecutionStrategy is null");

            this.operatorFactories = ImmutableList.<OperatorFactory>builder()
                    .addAll(source.map(PhysicalOperation::getOperatorFactories).orElse(ImmutableList.of()))
                    .add(operatorFactory)
                    .build();
            this.layout = ImmutableMap.copyOf(layout);
            this.types = toTypes(layout, context);
            this.pipelineExecutionStrategy = pipelineExecutionStrategy;
        }

        private static List<Type> toTypes(Map<Symbol, Integer> layout, LocalExecutionPlanContext context)
        {
            // verify layout covers all values
            int channelCount = layout.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            checkArgument(
                    layout.size() == channelCount && ImmutableSet.copyOf(layout.values()).containsAll(ContiguousSet.create(closedOpen(0, channelCount), integers())),
                    "Layout does not have a symbol for every output channel: %s", layout);
            Map<Integer, Symbol> channelLayout = ImmutableBiMap.copyOf(layout).inverse();

            return range(0, channelCount)
                    .mapToObj(channelLayout::get)
                    .map(context.getTypes()::get)
                    .collect(toImmutableList());
        }

        public int symbolToChannel(Symbol input)
        {
            checkArgument(layout.containsKey(input));
            return layout.get(input);
        }

        public List<Type> getTypes()
        {
            return types;
        }

        public Map<Symbol, Integer> getLayout()
        {
            return layout;
        }

        private List<OperatorFactory> getOperatorFactories()
        {
            return operatorFactories;
        }

        public PipelineExecutionStrategy getPipelineExecutionStrategy()
        {
            return pipelineExecutionStrategy;
        }
    }

    private static class DriverFactoryParameters
    {
        private final LocalExecutionPlanContext subContext;
        private final PhysicalOperation source;

        public DriverFactoryParameters(LocalExecutionPlanContext subContext, PhysicalOperation source)
        {
            this.subContext = subContext;
            this.source = source;
        }

        public LocalExecutionPlanContext getSubContext()
        {
            return subContext;
        }

        public PhysicalOperation getSource()
        {
            return source;
        }
    }
}
