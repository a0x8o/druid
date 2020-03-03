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
package io.prestosql.plugin.hive.metastore.thrift;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestThriftMetastoreConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(ThriftMetastoreConfig.class)
                .setMetastoreTimeout(new Duration(10, SECONDS))
                .setSocksProxy(null)
                .setMaxRetries(9)
                .setBackoffScaleFactor(2.0)
                .setMinBackoffDelay(new Duration(1, SECONDS))
                .setMaxBackoffDelay(new Duration(1, SECONDS))
                .setMaxRetryTime(new Duration(30, SECONDS))
                .setImpersonationEnabled(false));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("hive.metastore-timeout", "20s")
                .put("hive.metastore.thrift.client.socks-proxy", "localhost:1234")
                .put("hive.metastore.thrift.client.max-retries", "15")
                .put("hive.metastore.thrift.client.backoff-scale-factor", "3.0")
                .put("hive.metastore.thrift.client.min-backoff-delay", "2s")
                .put("hive.metastore.thrift.client.max-backoff-delay", "4s")
                .put("hive.metastore.thrift.client.max-retry-time", "60s")
                .put("hive.metastore.thrift.impersonation.enabled", "true")
                .build();

        ThriftMetastoreConfig expected = new ThriftMetastoreConfig()
                .setMetastoreTimeout(new Duration(20, SECONDS))
                .setSocksProxy(HostAndPort.fromParts("localhost", 1234))
                .setMaxRetries(15)
                .setBackoffScaleFactor(3.0)
                .setMinBackoffDelay(new Duration(2, SECONDS))
                .setMaxBackoffDelay(new Duration(4, SECONDS))
                .setMaxRetryTime(new Duration(60, SECONDS))
                .setImpersonationEnabled(true);

        assertFullMapping(properties, expected);
    }
}
