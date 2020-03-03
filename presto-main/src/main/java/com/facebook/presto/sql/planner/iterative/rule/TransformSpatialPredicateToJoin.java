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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.matching.Capture;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.sql.planner.ExpressionNodeInliner.replaceExpression;
import static com.facebook.presto.sql.planner.SymbolsExtractor.extractUnique;
import static com.facebook.presto.sql.planner.plan.Patterns.filter;
import static com.facebook.presto.sql.planner.plan.Patterns.join;
import static com.facebook.presto.sql.planner.plan.Patterns.source;
import static com.facebook.presto.sql.tree.ComparisonExpressionType.LESS_THAN;
import static com.facebook.presto.sql.tree.ComparisonExpressionType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.util.SpatialJoinUtils.extractSupportedSpatialComparisons;
import static com.facebook.presto.util.SpatialJoinUtils.extractSupportedSpatialFunctions;
import static com.google.common.base.Verify.verify;

/**
 * Applies to broadcast spatial joins expressed via ST_Contains, ST_Intersects and
 * ST_Distance functions.
 * <p>
 * For example:
 * <ul>
 *      <li>SELECT ... FROM a, b WHERE ST_Contains(b.geometry, a.geometry)</li>
 *      <li>SELECT ... FROM a, b WHERE ST_Intersects(b.geometry, a.geometry)</li>
 *      <li>SELECT ... FROM a, b WHERE ST_Distance(b.geometry, a.geometry) <= 300</li>
 *      <li>SELECT ... FROM a, b WHERE 15.5 > ST_Distance(b.geometry, a.geometry)</li>
 * </ul>
 * <p>
 * Joins expressed via ST_Contains and ST_Intersects functions must match all of
 * the following criteria:
 * <p>
 * - arguments of the spatial function are non-scalar expressions;
 * - one of the arguments uses symbols from left side of the join, the other from right.
 * <p>
 * Joins expressed via ST_Distance function must use less than or less than or equals operator
 * to compare ST_Distance value with a radius and must match all of the following criteria:
 * <p>
 * - arguments of the spatial function are non-scalar expressions;
 * - one of the arguments uses symbols from left side of the join, the other from right;
 * - radius is either scalar expression or uses symbols only from the right (build) side of the join.
 * <p>
 * Replaces cross join node and a qualifying filter on top with a single spatial join node.
 * <p>
 * Pushes non-trivial expressions of the spatial function arguments into projections on top of
 * join child nodes.
 * <p>
 * Examples:
 * <p>
 * Point-in-polygon inner join
 *      ST_Contains(ST_GeometryFromText(a.wkt), ST_Point(b.longitude, b.latitude))
 * becomes a spatial join
 *      ST_Contains(st_geometryfromtext, st_point)
 * with st_geometryfromtext -> 'ST_GeometryFromText(a.wkt)' and
 * st_point -> 'ST_Point(b.longitude, b.latitude)' projections on top of child nodes.
 * <p>
 * Distance query
 *      ST_Distance(ST_Point(a.lon, a.lat), ST_Point(b.lon, b.lat)) <= 10 / (111.321 * cos(radians(b.lat)))
 * becomes a spatial join
 *      ST_Distance(st_point_a, st_point_b) <= radius
 * with st_point_a -> 'ST_Point(a.lon, a.lat)', st_point_b -> 'ST_Point(b.lon, b.lat)'
 * and radius -> '10 / (111.321 * cos(radians(b.lat)))' projections on top of child nodes.
 */
public class TransformSpatialPredicateToJoin
        implements Rule<FilterNode>
{
    private static final Capture<JoinNode> JOIN = newCapture();
    private static final Pattern<FilterNode> PATTERN = filter()
            .with(source().matching(join().capturedAs(JOIN).matching(node -> node.isCrossJoin())));
    private static final TypeSignature GEOMETRY_TYPE_SIGNATURE = parseTypeSignature("Geometry");

    private final Metadata metadata;

    public TransformSpatialPredicateToJoin(Metadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return SystemSessionProperties.isSpatialJoinEanbled(session);
    }

    @Override
    public Result apply(FilterNode node, Captures captures, Context context)
    {
        JoinNode joinNode = captures.get(JOIN);

        Expression filter = node.getPredicate();
        List<FunctionCall> spatialFunctions = extractSupportedSpatialFunctions(filter);

        for (FunctionCall spatialFunction : spatialFunctions) {
            Result result = tryCreateSpatialJoin(context, node, joinNode, filter, spatialFunction);
            if (!result.isEmpty()) {
                return result;
            }
        }

        List<ComparisonExpression> spatialComparisons = extractSupportedSpatialComparisons(filter);
        for (ComparisonExpression spatialComparison : spatialComparisons) {
            Result result = tryCreateSpatialJoin(context, node, joinNode, filter, spatialComparison);
            if (!result.isEmpty()) {
                return result;
            }
        }

        return Result.empty();
    }

    private Result tryCreateSpatialJoin(Context context, FilterNode filterNode, JoinNode joinNode, Expression filter, ComparisonExpression spatialComparison)
    {
        PlanNode leftNode = joinNode.getLeft();
        PlanNode rightNode = joinNode.getRight();

        List<Symbol> leftSymbols = leftNode.getOutputSymbols();
        List<Symbol> rightSymbols = rightNode.getOutputSymbols();

        Expression radius;
        Optional<Symbol> newRadiusSymbol;
        ComparisonExpression newComparison;
        if (spatialComparison.getType() == LESS_THAN || spatialComparison.getType() == LESS_THAN_OR_EQUAL) {
            // ST_Distance(a, b) <= r
            radius = spatialComparison.getRight();
            Set<Symbol> radiusSymbols = extractUnique(radius);
            if (radiusSymbols.isEmpty() || (rightSymbols.containsAll(radiusSymbols) && containsNone(leftSymbols, radiusSymbols))) {
                newRadiusSymbol = newRadiusSymbol(context, radius);
                newComparison = new ComparisonExpression(spatialComparison.getType(), spatialComparison.getLeft(), toExpression(newRadiusSymbol, radius));
            }
            else {
                return Result.empty();
            }
        }
        else {
            // r >= ST_Distance(a, b)
            radius = spatialComparison.getLeft();
            Set<Symbol> radiusSymbols = extractUnique(radius);
            if (radiusSymbols.isEmpty() || (rightSymbols.containsAll(radiusSymbols) && containsNone(leftSymbols, radiusSymbols))) {
                newRadiusSymbol = newRadiusSymbol(context, radius);
                newComparison = new ComparisonExpression(spatialComparison.getType().flip(), spatialComparison.getRight(), toExpression(newRadiusSymbol, radius));
            }
            else {
                return Result.empty();
            }
        }

        Expression newFilter = replaceExpression(filter, ImmutableMap.of(spatialComparison, newComparison));
        PlanNode newRightNode = newRadiusSymbol.map(symbol -> addProjection(context, rightNode, symbol, radius)).orElse(rightNode);

        JoinNode newJoinNode = new JoinNode(
                joinNode.getId(),
                joinNode.getType(),
                leftNode,
                newRightNode,
                joinNode.getCriteria(),
                joinNode.getOutputSymbols(),
                Optional.of(newFilter),
                joinNode.getLeftHashSymbol(),
                joinNode.getRightHashSymbol(),
                joinNode.getDistributionType());

        return tryCreateSpatialJoin(context, filterNode, newJoinNode, newFilter, (FunctionCall) newComparison.getLeft());
    }

    private Result tryCreateSpatialJoin(Context context, FilterNode filterNode, JoinNode joinNode, Expression filter, FunctionCall spatialFunction)
    {
        List<Expression> arguments = spatialFunction.getArguments();
        verify(arguments.size() == 2);

        Expression firstArgument = arguments.get(0);
        Expression secondArgument = arguments.get(1);

        Set<Symbol> firstSymbols = extractUnique(firstArgument);
        Set<Symbol> secondSymbols = extractUnique(secondArgument);

        if (firstSymbols.isEmpty() || secondSymbols.isEmpty()) {
            return Result.empty();
        }

        Optional<Symbol> newFirstSymbol = newGeometrySymbol(context, firstArgument);
        Optional<Symbol> newSecondSymbol = newGeometrySymbol(context, secondArgument);

        PlanNode leftNode = joinNode.getLeft();
        PlanNode rightNode = joinNode.getRight();

        PlanNode newLeftNode;
        PlanNode newRightNode;

        int alignment = checkAlignment(joinNode, firstSymbols, secondSymbols);
        if (alignment > 0) {
            newLeftNode = newFirstSymbol.map(symbol -> addProjection(context, leftNode, symbol, firstArgument)).orElse(leftNode);
            newRightNode = newSecondSymbol.map(symbol -> addProjection(context, rightNode, symbol, secondArgument)).orElse(rightNode);
        }
        else if (alignment < 0) {
            newLeftNode = newSecondSymbol.map(symbol -> addProjection(context, leftNode, symbol, secondArgument)).orElse(leftNode);
            newRightNode = newFirstSymbol.map(symbol -> addProjection(context, rightNode, symbol, firstArgument)).orElse(rightNode);
        }
        else {
            return Result.empty();
        }

        Expression newFirstArgument = toExpression(newFirstSymbol, firstArgument);
        Expression newSecondArgument = toExpression(newSecondSymbol, secondArgument);

        Expression newSpatialFunction = new FunctionCall(spatialFunction.getName(), ImmutableList.of(newFirstArgument, newSecondArgument));
        Expression newFilter = replaceExpression(filter, ImmutableMap.of(spatialFunction, newSpatialFunction));

        return Result.ofPlanNode(new JoinNode(
                filterNode.getId(),
                joinNode.getType(),
                newLeftNode,
                newRightNode,
                joinNode.getCriteria(),
                filterNode.getOutputSymbols(),
                Optional.of(newFilter),
                joinNode.getLeftHashSymbol(),
                joinNode.getRightHashSymbol(),
                joinNode.getDistributionType()));
    }

    private static int checkAlignment(JoinNode joinNode, Set<Symbol> maybeLeftSymbols, Set<Symbol> maybeRightSymbols)
    {
        List<Symbol> leftSymbols = joinNode.getLeft().getOutputSymbols();
        List<Symbol> rightSymbols = joinNode.getRight().getOutputSymbols();

        if (leftSymbols.containsAll(maybeLeftSymbols)
                && containsNone(leftSymbols, maybeRightSymbols)
                && rightSymbols.containsAll(maybeRightSymbols)
                && containsNone(rightSymbols, maybeLeftSymbols)) {
            return 1;
        }
        else if (leftSymbols.containsAll(maybeRightSymbols)
                && containsNone(leftSymbols, maybeLeftSymbols)
                && rightSymbols.containsAll(maybeLeftSymbols)
                && containsNone(rightSymbols, maybeRightSymbols)) {
            return -1;
        }
        else {
            return 0;
        }
    }

    private Expression toExpression(Optional<Symbol> optionalSymbol, Expression defaultExpression)
    {
        return optionalSymbol.map(symbol -> (Expression) symbol.toSymbolReference()).orElse(defaultExpression);
    }

    private Optional<Symbol> newGeometrySymbol(Context context, Expression expression)
    {
        if (expression instanceof SymbolReference) {
            return Optional.empty();
        }

        return Optional.of(context.getSymbolAllocator().newSymbol(expression, metadata.getType(GEOMETRY_TYPE_SIGNATURE)));
    }

    private Optional<Symbol> newRadiusSymbol(Context context, Expression expression)
    {
        if (expression instanceof SymbolReference) {
            return Optional.empty();
        }

        return Optional.of(context.getSymbolAllocator().newSymbol(expression, DOUBLE));
    }

    private PlanNode addProjection(Context context, PlanNode node, Symbol symbol, Expression expression)
    {
        Assignments.Builder projections = Assignments.builder();
        for (Symbol outputSymbol : node.getOutputSymbols()) {
            projections.putIdentity(outputSymbol);
        }

        projections.put(symbol, expression);
        return new ProjectNode(context.getIdAllocator().getNextId(), node, projections.build());
    }

    private static boolean containsNone(Collection<Symbol> values, Collection<Symbol> testValues)
    {
        return values.stream().noneMatch(ImmutableSet.copyOf(testValues)::contains);
    }
}
