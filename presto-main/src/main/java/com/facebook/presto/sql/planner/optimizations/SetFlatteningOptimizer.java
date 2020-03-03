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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.PlanVariableAllocator;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.ExceptNode;
import com.facebook.presto.sql.planner.plan.IntersectNode;
import com.facebook.presto.sql.planner.plan.SetOperationNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class SetFlatteningOptimizer
        implements PlanOptimizer
{
    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, PlanVariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(variableAllocator, "variableAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return SimplePlanRewriter.rewriteWith(new Rewriter(), plan, false);
    }

    // TODO: remove expectation that UNION DISTINCT => distinct aggregation directly above union node
    private static class Rewriter
            extends SimplePlanRewriter<Boolean>
    {
        @Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<Boolean> context)
        {
            return context.defaultRewrite(node, false);
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<Boolean> context)
        {
            ImmutableList.Builder<PlanNode> flattenedSources = ImmutableList.builder();
            ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> flattenedVariableMap = ImmutableListMultimap.builder();
            flattenSetOperation(node, context, flattenedSources, flattenedVariableMap);

            return new UnionNode(node.getId(), flattenedSources.build(), flattenedVariableMap.build());
        }

        @Override
        public PlanNode visitIntersect(IntersectNode node, RewriteContext<Boolean> context)
        {
            ImmutableList.Builder<PlanNode> flattenedSources = ImmutableList.builder();
            ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> flattenedVariableMap = ImmutableListMultimap.builder();
            flattenSetOperation(node, context, flattenedSources, flattenedVariableMap);

            return new IntersectNode(node.getId(), flattenedSources.build(), flattenedVariableMap.build());
        }

        @Override
        public PlanNode visitExcept(ExceptNode node, RewriteContext<Boolean> context)
        {
            ImmutableList.Builder<PlanNode> flattenedSources = ImmutableList.builder();
            ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> flattenedVariableMap = ImmutableListMultimap.builder();
            flattenSetOperation(node, context, flattenedSources, flattenedVariableMap);

            return new ExceptNode(node.getId(), flattenedSources.build(), flattenedVariableMap.build());
        }

        private static void flattenSetOperation(
                SetOperationNode node, RewriteContext<Boolean> context,
                ImmutableList.Builder<PlanNode> flattenedSources,
                ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> flattenedSymbolMap)
        {
            for (int i = 0; i < node.getSources().size(); i++) {
                PlanNode subplan = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(subplan, context.get());

                Class<?> setOperationClass = node.getClass();
                if (setOperationClass.isInstance(rewrittenSource) && (!setOperationClass.equals(ExceptNode.class) || i == 0)) {
                    // Absorb source's subplans if it is also a SetOperation of the same type
                    // ExceptNodes can only flatten their first source because except is not associative
                    SetOperationNode rewrittenSetOperation = (SetOperationNode) rewrittenSource;
                    flattenedSources.addAll(rewrittenSetOperation.getSources());
                    for (Map.Entry<VariableReferenceExpression, Collection<VariableReferenceExpression>> entry : node.getVariableMapping().asMap().entrySet()) {
                        VariableReferenceExpression inputVariable = Iterables.get(entry.getValue(), i);
                        flattenedSymbolMap.putAll(entry.getKey(), rewrittenSetOperation.getVariableMapping().get(inputVariable));
                    }
                }
                else {
                    flattenedSources.add(rewrittenSource);
                    for (Map.Entry<VariableReferenceExpression, Collection<VariableReferenceExpression>> entry : node.getVariableMapping().asMap().entrySet()) {
                        flattenedSymbolMap.put(entry.getKey(), Iterables.get(entry.getValue(), i));
                    }
                }
            }
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<Boolean> context)
        {
            boolean distinct = isDistinctOperator(node);

            PlanNode rewrittenNode = context.rewrite(node.getSource(), distinct);

            if (context.get() && distinct) {
                // Assumes underlying node has same output symbols as this distinct node
                return rewrittenNode;
            }

            return new AggregationNode(
                    node.getId(),
                    rewrittenNode,
                    node.getAggregations(),
                    node.getGroupingSets(),
                    ImmutableList.of(),
                    node.getStep(),
                    node.getHashVariable(),
                    node.getGroupIdVariable());
        }

        private static boolean isDistinctOperator(AggregationNode node)
        {
            return node.getAggregations().isEmpty();
        }
    }
}
