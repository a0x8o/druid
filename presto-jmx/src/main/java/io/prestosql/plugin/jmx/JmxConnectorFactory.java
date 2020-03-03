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
package io.prestosql.plugin.jmx;

import com.google.inject.Injector;
import com.google.inject.Scopes;
import io.airlift.bootstrap.Bootstrap;
import io.prestosql.plugin.base.jmx.MBeanServerModule;
import io.prestosql.spi.NodeManager;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorContext;
import io.prestosql.spi.connector.ConnectorFactory;
import io.prestosql.spi.connector.ConnectorHandleResolver;

import java.util.Map;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class JmxConnectorFactory
        implements ConnectorFactory
{
    @Override
    public String getName()
    {
        return "jmx";
    }

    @Override
    public ConnectorHandleResolver getHandleResolver()
    {
        return new JmxHandleResolver();
    }

    @Override
    public Connector create(String catalogName, Map<String, String> config, ConnectorContext context)
    {
        Bootstrap app = new Bootstrap(
                new MBeanServerModule(),
                binder -> {
                    configBinder(binder).bindConfig(JmxConnectorConfig.class);
                    binder.bind(NodeManager.class).toInstance(context.getNodeManager());
                    binder.bind(JmxConnector.class).in(Scopes.SINGLETON);
                    binder.bind(JmxHistoricalData.class).in(Scopes.SINGLETON);
                    binder.bind(JmxMetadata.class).in(Scopes.SINGLETON);
                    binder.bind(JmxSplitManager.class).in(Scopes.SINGLETON);
                    binder.bind(JmxPeriodicSampler.class).in(Scopes.SINGLETON);
                    binder.bind(JmxRecordSetProvider.class).in(Scopes.SINGLETON);
                });

        Injector injector = app
                .strictConfig()
                .doNotInitializeLogging()
                .setRequiredConfigurationProperties(config)
                .initialize();

        return injector.getInstance(JmxConnector.class);
    }
}
