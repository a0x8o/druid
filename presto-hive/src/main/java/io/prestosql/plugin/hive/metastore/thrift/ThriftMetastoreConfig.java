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

import com.google.common.net.HostAndPort;
import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.Duration;
import io.prestosql.plugin.hive.util.RetryDriver;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import java.util.concurrent.TimeUnit;

public class ThriftMetastoreConfig
{
    private Duration metastoreTimeout = new Duration(10, TimeUnit.SECONDS);
    private HostAndPort socksProxy;
    private int maxRetries = RetryDriver.DEFAULT_MAX_ATTEMPTS - 1;
    private double backoffScaleFactor = RetryDriver.DEFAULT_SCALE_FACTOR;
    private Duration minBackoffDelay = RetryDriver.DEFAULT_SLEEP_TIME;
    private Duration maxBackoffDelay = RetryDriver.DEFAULT_SLEEP_TIME;
    private Duration maxRetryTime = RetryDriver.DEFAULT_MAX_RETRY_TIME;
    private boolean impersonationEnabled;

    @NotNull
    public Duration getMetastoreTimeout()
    {
        return metastoreTimeout;
    }

    @Config("hive.metastore-timeout")
    public ThriftMetastoreConfig setMetastoreTimeout(Duration metastoreTimeout)
    {
        this.metastoreTimeout = metastoreTimeout;
        return this;
    }

    public HostAndPort getSocksProxy()
    {
        return socksProxy;
    }

    @Config("hive.metastore.thrift.client.socks-proxy")
    public ThriftMetastoreConfig setSocksProxy(HostAndPort socksProxy)
    {
        this.socksProxy = socksProxy;
        return this;
    }

    @Min(0)
    public int getMaxRetries()
    {
        return maxRetries;
    }

    @Config("hive.metastore.thrift.client.max-retries")
    @ConfigDescription("Maximum number of retry attempts for metastore requests")
    public ThriftMetastoreConfig setMaxRetries(int maxRetries)
    {
        this.maxRetries = maxRetries;
        return this;
    }

    public double getBackoffScaleFactor()
    {
        return backoffScaleFactor;
    }

    @Config("hive.metastore.thrift.client.backoff-scale-factor")
    @ConfigDescription("Scale factor for metastore request retry delay")
    public ThriftMetastoreConfig setBackoffScaleFactor(double backoffScaleFactor)
    {
        this.backoffScaleFactor = backoffScaleFactor;
        return this;
    }

    @NotNull
    public Duration getMaxRetryTime()
    {
        return maxRetryTime;
    }

    @Config("hive.metastore.thrift.client.max-retry-time")
    @ConfigDescription("Total time limit for a metastore request to be retried")
    public ThriftMetastoreConfig setMaxRetryTime(Duration maxRetryTime)
    {
        this.maxRetryTime = maxRetryTime;
        return this;
    }

    public Duration getMinBackoffDelay()
    {
        return minBackoffDelay;
    }

    @Config("hive.metastore.thrift.client.min-backoff-delay")
    @ConfigDescription("Minimum delay between metastore request retries")
    public ThriftMetastoreConfig setMinBackoffDelay(Duration minBackoffDelay)
    {
        this.minBackoffDelay = minBackoffDelay;
        return this;
    }

    public Duration getMaxBackoffDelay()
    {
        return maxBackoffDelay;
    }

    @Config("hive.metastore.thrift.client.max-backoff-delay")
    @ConfigDescription("Maximum delay between metastore request retries")
    public ThriftMetastoreConfig setMaxBackoffDelay(Duration maxBackoffDelay)
    {
        this.maxBackoffDelay = maxBackoffDelay;
        return this;
    }

    public boolean isImpersonationEnabled()
    {
        return impersonationEnabled;
    }

    @Config("hive.metastore.thrift.impersonation.enabled")
    @ConfigDescription("Should end user be impersonated when communicating with metastore")
    public ThriftMetastoreConfig setImpersonationEnabled(boolean impersonationEnabled)
    {
        this.impersonationEnabled = impersonationEnabled;
        return this;
    }
}
