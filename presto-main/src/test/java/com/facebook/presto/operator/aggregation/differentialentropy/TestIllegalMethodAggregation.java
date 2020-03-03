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
package com.facebook.presto.operator.aggregation.differentialentropy;

import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.operator.aggregation.InternalAggregationFunction;
import com.facebook.presto.spi.PrestoException;
import org.testng.annotations.Test;

import static com.facebook.presto.block.BlockAssertions.createDoublesBlock;
import static com.facebook.presto.block.BlockAssertions.createLongsBlock;
import static com.facebook.presto.block.BlockAssertions.createStringsBlock;
import static com.facebook.presto.operator.aggregation.AggregationTestUtils.aggregation;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;

public class TestIllegalMethodAggregation
{
    @Test(
            expectedExceptions = PrestoException.class,
            expectedExceptionsMessageRegExp = "In differential_entropy UDF, invalid method: no_such_method")
    public void testIllegalMethod()
    {
        FunctionManager functionManager = MetadataManager.createTestMetadataManager().getFunctionManager();
        InternalAggregationFunction function = functionManager.getAggregateFunctionImplementation(
                functionManager.lookupFunction(
                        "differential_entropy",
                        fromTypes(BIGINT, DOUBLE, DOUBLE, VARCHAR, DOUBLE, DOUBLE)));
        aggregation(
                function,
                createLongsBlock(200),
                createDoublesBlock(0.1),
                createDoublesBlock(0.2),
                createStringsBlock("no_such_method"),
                createDoublesBlock(0.0),
                createDoublesBlock(1.0));
    }

    @Test
    public void testNullMethod()
    {
        FunctionManager functionManager = MetadataManager.createTestMetadataManager().getFunctionManager();
        InternalAggregationFunction function = functionManager.getAggregateFunctionImplementation(
                functionManager.lookupFunction(
                        "differential_entropy",
                        fromTypes(BIGINT, DOUBLE, DOUBLE, VARCHAR, DOUBLE, DOUBLE)));
        createStringsBlock((String) null);
        System.out.println("foo");
        aggregation(
                function,
                createLongsBlock(200),
                createDoublesBlock(0.1),
                createDoublesBlock(-0.2),
                createStringsBlock((String) null),
                createDoublesBlock(0.0),
                createDoublesBlock(1.0));
    }
}
