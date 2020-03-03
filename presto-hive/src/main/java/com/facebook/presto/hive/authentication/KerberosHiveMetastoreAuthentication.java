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
package com.facebook.presto.hive.authentication;

import com.facebook.presto.hive.ForHiveMetastore;
import com.facebook.presto.hive.HiveClientConfig;
import com.google.common.collect.ImmutableMap;
import org.apache.hadoop.hive.thrift.client.TUGIAssumingTransport;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.thrift.transport.TSaslClientTransport;
import org.apache.thrift.transport.TTransport;

import javax.inject.Inject;
import javax.security.sasl.Sasl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.security.SaslRpcServer.AuthMethod.KERBEROS;
import static org.apache.hadoop.security.SecurityUtil.getServerPrincipal;

public class KerberosHiveMetastoreAuthentication
        implements HiveMetastoreAuthentication
{
    private final String hiveMetastoreServicePrincipal;
    private final HadoopAuthentication authentication;
    private final boolean hdfsWireEncryptionEnabled;

    @Inject
    public KerberosHiveMetastoreAuthentication(
            MetastoreKerberosConfig config,
            @ForHiveMetastore HadoopAuthentication authentication,
            HiveClientConfig hiveClientConfig)
    {
        this(config.getHiveMetastoreServicePrincipal(), authentication, hiveClientConfig.isHdfsWireEncryptionEnabled());
    }

    public KerberosHiveMetastoreAuthentication(String hiveMetastoreServicePrincipal, HadoopAuthentication authentication, boolean hdfsWireEncryptionEnabled)
    {
        this.hiveMetastoreServicePrincipal = requireNonNull(hiveMetastoreServicePrincipal, "hiveMetastoreServicePrincipal is null");
        this.authentication = requireNonNull(authentication, "authentication is null");
        this.hdfsWireEncryptionEnabled = hdfsWireEncryptionEnabled;
    }

    @Override
    public TTransport authenticate(TTransport rawTransport, String hiveMetastoreHost)
    {
        try {
            String serverPrincipal = getServerPrincipal(hiveMetastoreServicePrincipal, hiveMetastoreHost);
            String[] names = SaslRpcServer.splitKerberosName(serverPrincipal);
            checkState(names.length == 3,
                    "Kerberos principal name does NOT have the expected hostname part: %s", serverPrincipal);

            Map<String, String> saslProps = ImmutableMap.of(
                    Sasl.QOP, hdfsWireEncryptionEnabled ? "auth-conf" : "auth",
                    Sasl.SERVER_AUTH, "true");

            TTransport saslTransport = new TSaslClientTransport(
                    KERBEROS.getMechanismName(),
                    null,
                    names[0],
                    names[1],
                    saslProps,
                    null,
                    rawTransport);

            return new TUGIAssumingTransport(saslTransport, authentication.getUserGroupInformation());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
