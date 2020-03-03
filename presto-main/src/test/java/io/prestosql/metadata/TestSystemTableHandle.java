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
package io.prestosql.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.airlift.json.JsonModule;
import io.prestosql.connector.system.SystemTableHandle;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.predicate.TupleDomain;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestSystemTableHandle
{
    private static final Map<String, Object> SCHEMA_AS_MAP = ImmutableMap.of(
            "@type", "$system",
            "schemaName", "system_schema",
            "tableName", "system_table",
            "constraint", ImmutableMap.of("columnDomains", ImmutableList.of()));

    private ObjectMapper objectMapper;

    @BeforeMethod
    public void startUp()
    {
        Injector injector = Guice.createInjector(new JsonModule(), new HandleJsonModule());

        objectMapper = injector.getInstance(ObjectMapper.class);
    }

    @Test
    public void testSystemSerialize()
            throws Exception
    {
        SystemTableHandle internalHandle = new SystemTableHandle("system_schema", "system_table", TupleDomain.all());

        assertTrue(objectMapper.canSerialize(SystemTableHandle.class));
        String json = objectMapper.writeValueAsString(internalHandle);
        testJsonEquals(json, SCHEMA_AS_MAP);
    }

    @Test
    public void testSystemDeserialize()
            throws Exception
    {
        String json = objectMapper.writeValueAsString(SCHEMA_AS_MAP);

        ConnectorTableHandle tableHandle = objectMapper.readValue(json, ConnectorTableHandle.class);
        assertEquals(tableHandle.getClass(), SystemTableHandle.class);
        SystemTableHandle systemHandle = (SystemTableHandle) tableHandle;

        assertEquals(systemHandle.getSchemaTableName(), new SchemaTableName("system_schema", "system_table"));
    }

    private void testJsonEquals(String json, Map<String, Object> expectedMap)
            throws Exception
    {
        Map<String, Object> jsonMap = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        assertEqualsIgnoreOrder(jsonMap.entrySet(), expectedMap.entrySet());
    }
}
