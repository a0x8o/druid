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
import io.airlift.units.Duration;
import io.prestosql.plugin.hive.authentication.NoHiveMetastoreAuthentication;
import org.apache.thrift.transport.TTransportException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class MockThriftMetastoreClientFactory
        extends ThriftMetastoreClientFactory
{
    private final List<ThriftMetastoreClient> clients;

    public MockThriftMetastoreClientFactory(Optional<HostAndPort> socksProxy, Duration timeout, List<ThriftMetastoreClient> clients)
    {
        super(Optional.empty(), socksProxy, timeout, new NoHiveMetastoreAuthentication());
        this.clients = new ArrayList<>(requireNonNull(clients, "clients is null"));
    }

    @Override
    public ThriftMetastoreClient create(HostAndPort address, Optional<String> delegationToken)
            throws TTransportException
    {
        checkArgument(!delegationToken.isPresent(), "delegation token is not supported");
        checkState(!clients.isEmpty(), "mock not given enough clients");
        ThriftMetastoreClient client = clients.remove(0);
        if (client == null) {
            throw new TTransportException(TTransportException.TIMED_OUT);
        }
        return client;
    }
}
