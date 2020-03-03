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
package io.prestosql.plugin.raptor.legacy;

import com.google.common.collect.ImmutableMap;
import io.prestosql.tests.AbstractTestDistributedQueries;

import static io.prestosql.plugin.raptor.legacy.RaptorQueryRunner.createRaptorQueryRunner;

public class TestRaptorDistributedQueries
        extends AbstractTestDistributedQueries
{
    @SuppressWarnings("unused")
    public TestRaptorDistributedQueries()
    {
        this(() -> createRaptorQueryRunner(ImmutableMap.of(), true, false));
    }

    protected TestRaptorDistributedQueries(QueryRunnerSupplier supplier)
    {
        super(supplier);
    }

    @Override
    public void testCommentTable()
    {
        // Raptor connector currently does not support comment on table
        assertQueryFails("COMMENT ON TABLE orders IS 'hello'", "This connector does not support setting table comments");
    }
}
