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
package io.prestosql.plugin.jdbc;

import io.airlift.testing.EquivalenceTester;
import org.testng.annotations.Test;

import static io.prestosql.plugin.jdbc.MetadataUtil.COLUMN_CODEC;
import static io.prestosql.plugin.jdbc.MetadataUtil.assertJsonRoundTrip;
import static io.prestosql.plugin.jdbc.TestingJdbcTypeHandle.JDBC_BIGINT;
import static io.prestosql.plugin.jdbc.TestingJdbcTypeHandle.JDBC_VARCHAR;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;

public class TestJdbcColumnHandle
{
    @Test
    public void testJsonRoundTrip()
    {
        assertJsonRoundTrip(COLUMN_CODEC, new JdbcColumnHandle("columnName", JDBC_VARCHAR, VARCHAR, true));
    }

    @Test
    public void testEquivalence()
    {
        EquivalenceTester.equivalenceTester()
                .addEquivalentGroup(
                        new JdbcColumnHandle("columnName", JDBC_VARCHAR, VARCHAR, true),
                        new JdbcColumnHandle("columnName", JDBC_VARCHAR, VARCHAR, true),
                        new JdbcColumnHandle("columnName", JDBC_BIGINT, BIGINT, true),
                        new JdbcColumnHandle("columnName", JDBC_VARCHAR, VARCHAR, true))
                .addEquivalentGroup(
                        new JdbcColumnHandle("columnNameX", JDBC_VARCHAR, VARCHAR, true),
                        new JdbcColumnHandle("columnNameX", JDBC_VARCHAR, VARCHAR, true),
                        new JdbcColumnHandle("columnNameX", JDBC_BIGINT, BIGINT, true),
                        new JdbcColumnHandle("columnNameX", JDBC_VARCHAR, VARCHAR, true))
                .check();
    }
}
