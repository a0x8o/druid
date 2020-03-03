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

import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.optimizations.ScalarAggregationToJoinRewriter;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.CorrelatedJoinNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;

import java.util.Optional;

import static io.prestosql.matching.Pattern.nonEmpty;
import static io.prestosql.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static io.prestosql.sql.planner.optimizations.QueryCardinalityUtil.isScalar;
import static io.prestosql.sql.planner.plan.Patterns.CorrelatedJoin.correlation;
import static io.prestosql.sql.planner.plan.Patterns.CorrelatedJoin.filter;
import static io.prestosql.sql.planner.plan.Patterns.correlatedJoin;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.util.MorePredicates.isInstanceOfAny;
import static java.util.Objects.requireNonNull;

/**
 * Scalar aggregation is aggregation with GROUP BY 'a constant' (or empty GROUP BY).
 * It always returns single row.
 * <p>
 * This optimizer rewrites correlated scalar aggregation subquery to left outer join in a way described here:
 * https://github.com/prestosql/presto/wiki/Correlated-subqueries
 * <p>
 * From:
 * <pre>
 * - CorrelatedJoin (with correlation list: [C])
 *   - (input) plan which produces symbols: [A, B, C]
 *   - (subquery) Aggregation(GROUP BY (); functions: [sum(F), count(), ...]
 *     - Filter(D = C AND E > 5)
 *       - plan which produces symbols: [D, E, F]
 * </pre>
 * to:
 * <pre>
 * - Aggregation(GROUP BY A, B, C, U; functions: [sum(F), count(non_null), ...]
 *   - Join(LEFT_OUTER, D = C)
 *     - AssignUniqueId(adds symbol U)
 *       - (input) plan which produces symbols: [A, B, C]
 *     - projection which adds non null symbol used for count() function
 *       - Filter(E > 5)
 *         - plan which produces symbols: [D, E, F]
 * </pre>
 * <p>
 * Note that only conjunction predicates in FilterNode are supported
 */
public class TransformCorrelatedScalarAggregationToJoin
        implements Rule<CorrelatedJoinNode>
{
    private static final Pattern<CorrelatedJoinNode> PATTERN = correlatedJoin()
            .with(nonEmpty(correlation()))
            .with(filter().equalTo(TRUE_LITERAL)); // todo non-trivial join filter: adding filter/project on top of aggregation

    @Override
    public Pattern<CorrelatedJoinNode> getPattern()
    {
        return PATTERN;
    }

    private final Metadata metadata;

    public TransformCorrelatedScalarAggregationToJoin(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Result apply(CorrelatedJoinNode correlatedJoinNode, Captures captures, Context context)
    {
        PlanNode subquery = correlatedJoinNode.getSubquery();

        if (!isScalar(subquery, context.getLookup())) {
            return Result.empty();
        }

        Optional<AggregationNode> aggregation = findAggregation(subquery, context.getLookup());
        if (!(aggregation.isPresent() && aggregation.get().getGroupingKeys().isEmpty())) {
            return Result.empty();
        }

        ScalarAggregationToJoinRewriter rewriter = new ScalarAggregationToJoinRewriter(metadata, context.getSymbolAllocator(), context.getIdAllocator(), context.getLookup());

        PlanNode rewrittenNode = rewriter.rewriteScalarAggregation(correlatedJoinNode, aggregation.get());

        if (rewrittenNode instanceof CorrelatedJoinNode) {
            return Result.empty();
        }

        return Result.ofPlanNode(rewrittenNode);
    }

    private static Optional<AggregationNode> findAggregation(PlanNode rootNode, Lookup lookup)
    {
        return searchFrom(rootNode, lookup)
                .where(AggregationNode.class::isInstance)
                .recurseOnlyWhen(isInstanceOfAny(ProjectNode.class, EnforceSingleRowNode.class))
                .findFirst();
    }
}
