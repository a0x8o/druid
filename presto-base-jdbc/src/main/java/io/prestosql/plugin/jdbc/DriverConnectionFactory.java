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

import io.prestosql.plugin.jdbc.credential.CredentialProvider;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class DriverConnectionFactory
        implements ConnectionFactory
{
    private final Driver driver;
    private final String connectionUrl;
    private final Properties connectionProperties;
    private final CredentialProvider credentialProvider;

    public DriverConnectionFactory(Driver driver, BaseJdbcConfig config, CredentialProvider credentialProvider)
    {
        this(driver,
                config.getConnectionUrl(),
                new Properties(),
                credentialProvider);
    }

    public DriverConnectionFactory(Driver driver, String connectionUrl, Properties connectionProperties, CredentialProvider credentialProvider)
    {
        this.driver = requireNonNull(driver, "driver is null");
        this.connectionUrl = requireNonNull(connectionUrl, "connectionUrl is null");
        this.connectionProperties = new Properties();
        this.connectionProperties.putAll(requireNonNull(connectionProperties, "basicConnectionProperties is null"));
        this.credentialProvider = requireNonNull(credentialProvider, "credentialProvider is null");
    }

    @Override
    public Connection openConnection(JdbcIdentity identity)
            throws SQLException
    {
        Properties properties = getCredentialProperties(identity);
        Connection connection = driver.connect(connectionUrl, properties);
        checkState(connection != null, "Driver returned null connection");
        return connection;
    }

    private Properties getCredentialProperties(JdbcIdentity identity)
    {
        Properties properties = new Properties();
        properties.putAll(connectionProperties);
        credentialProvider.getConnectionUser(Optional.of(identity))
                .ifPresent(userName -> properties.setProperty("user", userName));

        credentialProvider.getConnectionPassword(Optional.of(identity))
                .ifPresent(password -> properties.setProperty("password", password));
        return properties;
    }
}
