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
package io.prestosql.memory;

import io.prestosql.Session;
import io.prestosql.tests.AbstractTestQueryFramework;
import io.prestosql.tests.tpch.TpchQueryRunnerBuilder;
import org.testng.annotations.Test;

import static io.prestosql.SystemSessionProperties.QUERY_MAX_MEMORY_PER_NODE;
import static io.prestosql.SystemSessionProperties.QUERY_MAX_TOTAL_MEMORY_PER_NODE;
import static org.testng.Assert.fail;

public class TestMemorySessionProperties
        extends AbstractTestQueryFramework
{
    public static final String sql = "SELECT COUNT(*), clerk FROM orders GROUP BY clerk";

    TestMemorySessionProperties()
    {
        super(() -> TpchQueryRunnerBuilder.builder().setNodeCount(2).build());
    }

    @Test(timeOut = 240_000)
    public void testSessionQueryMemoryPerNodeLimit()
            throws Exception
    {
        assertQuery(sql);
        Session session = Session.builder(getQueryRunner().getDefaultSession())
                .setSystemProperty(QUERY_MAX_MEMORY_PER_NODE, "1kB")
                .build();
        try {
            getQueryRunner().execute(session, sql);
            fail("Expected query to fail due to low query_max_memory_per_node.");
        }
        catch (RuntimeException e) {
            // expected
        }
    }

    @Test(timeOut = 240_000)
    public void testSessionQueryMaxTotalMemoryPerNodeLimit()
            throws Exception
    {
        assertQuery(sql);
        Session session = Session.builder(getQueryRunner().getDefaultSession())
                .setSystemProperty(QUERY_MAX_TOTAL_MEMORY_PER_NODE, "1kB")
                .build();
        try {
            getQueryRunner().execute(session, sql);
            fail("Expected query to fail due to low query_max_memory_per_node.");
        }
        catch (RuntimeException e) {
            // expected
        }
    }
}
