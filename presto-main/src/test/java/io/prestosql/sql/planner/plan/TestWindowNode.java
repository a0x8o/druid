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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.slice.Slice;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.Signature;
import io.prestosql.server.SliceDeserializer;
import io.prestosql.server.SliceSerializer;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.sql.Serialization;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.OrderingScheme;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FrameBound;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.WindowFrame;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static org.testng.Assert.assertEquals;

public class TestWindowNode
{
    private SymbolAllocator symbolAllocator;
    private ValuesNode sourceNode;
    private Symbol columnA;
    private Symbol columnB;
    private Symbol columnC;

    private final ObjectMapper objectMapper;

    public TestWindowNode()
    {
        // dependencies copied from ServerMainModule.java to avoid depending on whole ServerMainModule here
        SqlParser sqlParser = new SqlParser();
        ObjectMapperProvider provider = new ObjectMapperProvider();
        provider.setJsonSerializers(ImmutableMap.of(
                Slice.class, new SliceSerializer(),
                Expression.class, new Serialization.ExpressionSerializer()));
        provider.setJsonDeserializers(ImmutableMap.of(
                Slice.class, new SliceDeserializer(),
                Expression.class, new Serialization.ExpressionDeserializer(sqlParser),
                FunctionCall.class, new Serialization.FunctionCallDeserializer(sqlParser)));
        objectMapper = provider.get();
    }

    @BeforeClass
    public void setUp()
    {
        symbolAllocator = new SymbolAllocator();
        columnA = symbolAllocator.newSymbol("a", BIGINT);
        columnB = symbolAllocator.newSymbol("b", BIGINT);
        columnC = symbolAllocator.newSymbol("c", BIGINT);

        sourceNode = new ValuesNode(
                newId(),
                ImmutableList.of(columnA, columnB, columnC),
                ImmutableList.of());
    }

    @Test
    public void testSerializationRoundtrip()
            throws Exception
    {
        Symbol windowSymbol = symbolAllocator.newSymbol("sum", BIGINT);
        Signature signature = new Signature(
                "sum",
                FunctionKind.WINDOW,
                ImmutableList.of(),
                ImmutableList.of(),
                BIGINT.getTypeSignature(),
                ImmutableList.of(BIGINT.getTypeSignature()),
                false);
        WindowNode.Frame frame = new WindowNode.Frame(
                WindowFrame.Type.RANGE,
                FrameBound.Type.UNBOUNDED_PRECEDING,
                Optional.empty(),
                FrameBound.Type.UNBOUNDED_FOLLOWING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        PlanNodeId id = newId();
        WindowNode.Specification specification = new WindowNode.Specification(
                ImmutableList.of(columnA),
                Optional.of(new OrderingScheme(
                        ImmutableList.of(columnB),
                        ImmutableMap.of(columnB, SortOrder.ASC_NULLS_FIRST))));
        Map<Symbol, WindowNode.Function> functions = ImmutableMap.of(windowSymbol, new WindowNode.Function(signature, ImmutableList.of(columnC.toSymbolReference()), frame, false));
        Optional<Symbol> hashSymbol = Optional.of(columnB);
        Set<Symbol> prePartitionedInputs = ImmutableSet.of(columnA);
        WindowNode windowNode = new WindowNode(
                id,
                sourceNode,
                specification,
                functions,
                hashSymbol,
                prePartitionedInputs,
                0);

        String json = objectMapper.writeValueAsString(windowNode);

        WindowNode actualNode = objectMapper.readValue(json, WindowNode.class);

        assertEquals(actualNode.getId(), windowNode.getId());
        assertEquals(actualNode.getSpecification(), windowNode.getSpecification());
        assertEquals(actualNode.getWindowFunctions(), windowNode.getWindowFunctions());
        assertEquals(actualNode.getFrames(), windowNode.getFrames());
        assertEquals(actualNode.getHashSymbol(), windowNode.getHashSymbol());
        assertEquals(actualNode.getPrePartitionedInputs(), windowNode.getPrePartitionedInputs());
        assertEquals(actualNode.getPreSortedOrderPrefix(), windowNode.getPreSortedOrderPrefix());
    }

    private static PlanNodeId newId()
    {
        return new PlanNodeId(UUID.randomUUID().toString());
    }
}
