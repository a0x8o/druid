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
package io.prestosql.tests.jdbc;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.fulfillment.ldap.LdapObjectRequirement;
import io.prestosql.tempto.query.QueryResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.AMERICA_ORG;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.ASIA_ORG;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.CHILD_GROUP;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.CHILD_GROUP_USER;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.DEFAULT_GROUP;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.DEFAULT_GROUP_USER;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.ORPHAN_USER;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.PARENT_GROUP;
import static io.prestosql.tests.ImmutableLdapObjectDefinitions.PARENT_GROUP_USER;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public abstract class LdapJdbcTests
        extends ProductTest
        implements RequirementsProvider
{
    protected static final long TIMEOUT = 30 * 1000; // seconds per test

    protected static final String NATION_SELECT_ALL_QUERY = "select * from tpch.tiny.nation";

    @Inject
    @Named("databases.presto.cli_ldap_truststore_path")
    protected String ldapTruststorePath;

    @Inject
    @Named("databases.presto.cli_ldap_truststore_password")
    protected String ldapTruststorePassword;

    @Inject
    @Named("databases.presto.cli_ldap_user_name")
    protected String ldapUserName;

    @Inject
    @Named("databases.presto.cli_ldap_user_password")
    protected String ldapUserPassword;

    @Inject
    @Named("databases.presto.cli_ldap_server_address")
    private String prestoServer;

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return new LdapObjectRequirement(
                Arrays.asList(
                        AMERICA_ORG, ASIA_ORG,
                        DEFAULT_GROUP, PARENT_GROUP, CHILD_GROUP,
                        DEFAULT_GROUP_USER, PARENT_GROUP_USER, CHILD_GROUP_USER, ORPHAN_USER));
    }

    protected void expectQueryToFail(String user, String password, String message)
    {
        try {
            executeLdapQuery(NATION_SELECT_ALL_QUERY, user, password);
            fail();
        }
        catch (SQLException exception) {
            assertEquals(exception.getMessage(), message);
        }
    }

    protected QueryResult executeLdapQuery(String query, String name, String password)
            throws SQLException
    {
        try (Connection connection = getLdapConnection(name, password)) {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(query);
            return QueryResult.forResultSet(rs);
        }
    }

    private Connection getLdapConnection(String name, String password)
            throws SQLException
    {
        return DriverManager.getConnection(getLdapUrl(), name, password);
    }

    protected String prestoServer()
    {
        String prefix = "https://";
        checkState(prestoServer.startsWith(prefix), "invalid server address: %s", prestoServer);
        return prestoServer.substring(prefix.length());
    }

    protected String getLdapUrl()
    {
        return format(getLdapUrlFormat(), prestoServer(), ldapTruststorePath, ldapTruststorePassword);
    }

    protected abstract String getLdapUrlFormat();
}
