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
package io.prestosql.plugin.cassandra;

import io.airlift.json.JsonCodec;
import org.testng.annotations.Test;

import static io.airlift.json.JsonCodec.jsonCodec;
import static org.testng.Assert.assertEquals;

public class TestCassandraColumnHandle
{
    private final JsonCodec<CassandraColumnHandle> codec = jsonCodec(CassandraColumnHandle.class);

    @Test
    public void testRoundTrip()
    {
        CassandraColumnHandle expected = new CassandraColumnHandle("name", 42, CassandraType.FLOAT, true, false, false, false);

        String json = codec.toJson(expected);
        CassandraColumnHandle actual = codec.fromJson(json);

        assertEquals(actual.getName(), expected.getName());
        assertEquals(actual.getOrdinalPosition(), expected.getOrdinalPosition());
        assertEquals(actual.getCassandraType(), expected.getCassandraType());
        assertEquals(actual.isPartitionKey(), expected.isPartitionKey());
        assertEquals(actual.isClusteringKey(), expected.isClusteringKey());
    }

    @Test
    public void testRoundTrip2()
    {
        CassandraColumnHandle expected = new CassandraColumnHandle(
                "name2",
                1,
                CassandraType.MAP,
                false,
                true,
                false,
                false);

        String json = codec.toJson(expected);
        CassandraColumnHandle actual = codec.fromJson(json);

        assertEquals(actual.getName(), expected.getName());
        assertEquals(actual.getOrdinalPosition(), expected.getOrdinalPosition());
        assertEquals(actual.getCassandraType(), expected.getCassandraType());
        assertEquals(actual.isPartitionKey(), expected.isPartitionKey());
        assertEquals(actual.isClusteringKey(), expected.isClusteringKey());
    }
}
