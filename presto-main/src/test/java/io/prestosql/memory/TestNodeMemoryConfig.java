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

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.prestosql.memory.NodeMemoryConfig.AVAILABLE_HEAP_MEMORY;

public class TestNodeMemoryConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(NodeMemoryConfig.class)
                .setMaxQueryMemoryPerNode(new DataSize(AVAILABLE_HEAP_MEMORY * 0.1, BYTE))
                .setMaxQueryTotalMemoryPerNode(new DataSize(AVAILABLE_HEAP_MEMORY * 0.3, BYTE))
                .setHeapHeadroom(new DataSize(AVAILABLE_HEAP_MEMORY * 0.3, BYTE))
                .setReservedPoolEnabled(true));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("query.max-memory-per-node", "1GB")
                .put("query.max-total-memory-per-node", "3GB")
                .put("memory.heap-headroom-per-node", "1GB")
                .put("experimental.reserved-pool-enabled", "false")
                .build();

        NodeMemoryConfig expected = new NodeMemoryConfig()
                .setMaxQueryMemoryPerNode(new DataSize(1, GIGABYTE))
                .setMaxQueryTotalMemoryPerNode(new DataSize(3, GIGABYTE))
                .setHeapHeadroom(new DataSize(1, GIGABYTE))
                .setReservedPoolEnabled(false);

        assertFullMapping(properties, expected);
    }
}
