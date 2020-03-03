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
import org.testng.annotations.Test;

import static io.prestosql.plugin.raptor.legacy.RaptorQueryRunner.createRaptorQueryRunner;

public class TestRaptorIntegrationSmokeTestBucketed
        extends TestRaptorIntegrationSmokeTest
{
    public TestRaptorIntegrationSmokeTestBucketed()
    {
        super(() -> createRaptorQueryRunner(ImmutableMap.of(), true, true));
    }

    @Test
    public void testShardsSystemTableBucketNumber()
    {
        assertQuery("" +
                        "SELECT count(DISTINCT bucket_number)\n" +
                        "FROM system.shards\n" +
                        "WHERE table_schema = 'tpch'\n" +
                        "  AND table_name = 'orders'",
                "SELECT 25");
    }
}
