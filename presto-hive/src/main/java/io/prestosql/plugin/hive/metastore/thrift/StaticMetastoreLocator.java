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
import io.prestosql.plugin.hive.authentication.HiveAuthenticationConfig;
import io.prestosql.plugin.hive.authentication.HiveAuthenticationConfig.HiveMetastoreAuthenticationType;
import org.apache.thrift.TException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static io.prestosql.plugin.hive.metastore.thrift.StaticMetastoreConfig.HIVE_METASTORE_USERNAME;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class StaticMetastoreLocator
        implements MetastoreLocator
{
    private final List<HostAndPort> addresses;
    private final ThriftMetastoreClientFactory clientFactory;
    private final String metastoreUsername;

    @Inject
    public StaticMetastoreLocator(StaticMetastoreConfig config, HiveAuthenticationConfig hiveAuthenticationConfig, ThriftMetastoreClientFactory clientFactory)
    {
        this(config.getMetastoreUris(), config.getMetastoreUsername(), clientFactory);

        checkArgument(
                isNullOrEmpty(metastoreUsername) || hiveAuthenticationConfig.getHiveMetastoreAuthenticationType() == HiveMetastoreAuthenticationType.NONE,
                "%s cannot be used together with %s authentication",
                HIVE_METASTORE_USERNAME,
                hiveAuthenticationConfig.getHiveMetastoreAuthenticationType());
    }

    public StaticMetastoreLocator(List<URI> metastoreUris, @Nullable String metastoreUsername, ThriftMetastoreClientFactory clientFactory)
    {
        requireNonNull(metastoreUris, "metastoreUris is null");
        checkArgument(!metastoreUris.isEmpty(), "metastoreUris must specify at least one URI");
        this.addresses = metastoreUris.stream()
                .map(StaticMetastoreLocator::checkMetastoreUri)
                .map(uri -> HostAndPort.fromParts(uri.getHost(), uri.getPort()))
                .collect(toList());
        this.metastoreUsername = metastoreUsername;
        this.clientFactory = requireNonNull(clientFactory, "clientFactory is null");
    }

    /**
     * Create a metastore client connected to the Hive metastore.
     * <p>
     * As per Hive HA metastore behavior, return the first metastore in the list
     * list of available metastores (i.e. the default metastore) if a connection
     * can be made, else try another of the metastores at random, until either a
     * connection succeeds or there are no more fallback metastores.
     */
    @Override
    public ThriftMetastoreClient createMetastoreClient(Optional<String> delegationToken)
            throws TException
    {
        List<HostAndPort> metastores = new ArrayList<>(addresses);
        Collections.shuffle(metastores.subList(1, metastores.size()));

        TException lastException = null;
        for (HostAndPort metastore : metastores) {
            try {
                ThriftMetastoreClient client = clientFactory.create(metastore, delegationToken);
                if (!isNullOrEmpty(metastoreUsername)) {
                    client.setUGI(metastoreUsername);
                }
                return client;
            }
            catch (TException e) {
                lastException = e;
            }
        }
        throw new TException("Failed connecting to Hive metastore: " + addresses, lastException);
    }

    private static URI checkMetastoreUri(URI uri)
    {
        requireNonNull(uri, "metastoreUri is null");
        String scheme = uri.getScheme();
        checkArgument(!isNullOrEmpty(scheme), "metastoreUri scheme is missing: %s", uri);
        checkArgument(scheme.equals("thrift"), "metastoreUri scheme must be thrift: %s", uri);
        checkArgument(uri.getHost() != null, "metastoreUri host is missing: %s", uri);
        checkArgument(uri.getPort() != -1, "metastoreUri port is missing: %s", uri);
        return uri;
    }
}
