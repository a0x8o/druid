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
package io.prestosql.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.plan.JoinNode.Type;
import io.prestosql.sql.tree.Expression;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Immutable
public class UnnestNode
        extends PlanNode
{
    private final PlanNode source;
    private final List<Symbol> replicateSymbols;
    private final Map<Symbol, List<Symbol>> unnestSymbols;
    private final Optional<Symbol> ordinalitySymbol;
    private final Type joinType;
    private final Optional<Expression> filter;

    @JsonCreator
    public UnnestNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("source") PlanNode source,
            @JsonProperty("replicateSymbols") List<Symbol> replicateSymbols,
            @JsonProperty("unnestSymbols") Map<Symbol, List<Symbol>> unnestSymbols,
            @JsonProperty("ordinalitySymbol") Optional<Symbol> ordinalitySymbol,
            @JsonProperty("joinType") Type joinType,
            @JsonProperty("filter") Optional<Expression> filter)
    {
        super(id);
        this.source = requireNonNull(source, "source is null");
        this.replicateSymbols = ImmutableList.copyOf(requireNonNull(replicateSymbols, "replicateSymbols is null"));
        checkArgument(source.getOutputSymbols().containsAll(replicateSymbols), "Source does not contain all replicateSymbols");
        requireNonNull(unnestSymbols, "unnestSymbols is null");
        checkArgument(!unnestSymbols.isEmpty(), "unnestSymbols is empty");
        ImmutableMap.Builder<Symbol, List<Symbol>> builder = ImmutableMap.builder();
        for (Map.Entry<Symbol, List<Symbol>> entry : unnestSymbols.entrySet()) {
            builder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }
        this.unnestSymbols = builder.build();
        this.ordinalitySymbol = requireNonNull(ordinalitySymbol, "ordinalitySymbol is null");
        this.joinType = requireNonNull(joinType, "type is null");
        this.filter = requireNonNull(filter, "filter is null");
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        ImmutableList.Builder<Symbol> outputSymbolsBuilder = ImmutableList.<Symbol>builder()
                .addAll(replicateSymbols)
                .addAll(Iterables.concat(unnestSymbols.values()));
        ordinalitySymbol.ifPresent(outputSymbolsBuilder::add);
        return outputSymbolsBuilder.build();
    }

    @JsonProperty
    public PlanNode getSource()
    {
        return source;
    }

    @JsonProperty
    public List<Symbol> getReplicateSymbols()
    {
        return replicateSymbols;
    }

    @JsonProperty
    public Map<Symbol, List<Symbol>> getUnnestSymbols()
    {
        return unnestSymbols;
    }

    @JsonProperty
    public Optional<Symbol> getOrdinalitySymbol()
    {
        return ordinalitySymbol;
    }

    @JsonProperty
    public Type getJoinType()
    {
        return joinType;
    }

    @JsonProperty
    public Optional<Expression> getFilter()
    {
        return filter;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(source);
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitUnnest(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new UnnestNode(getId(), Iterables.getOnlyElement(newChildren), replicateSymbols, unnestSymbols, ordinalitySymbol, joinType, filter);
    }
}
