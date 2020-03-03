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
package io.prestosql.plugin.base.security;

import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorAccessControl;
import io.prestosql.spi.connector.ConnectorSecurityContext;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public abstract class ForwardingConnectorAccessControl
        implements ConnectorAccessControl
{
    public static ConnectorAccessControl of(Supplier<ConnectorAccessControl> connectorAccessControlSupplier)
    {
        requireNonNull(connectorAccessControlSupplier, "connectorAccessControlSupplier is null");
        return new ForwardingConnectorAccessControl()
        {
            @Override
            protected ConnectorAccessControl delegate()
            {
                return connectorAccessControlSupplier.get();
            }
        };
    }

    protected abstract ConnectorAccessControl delegate();

    @Override
    public void checkCanCreateSchema(ConnectorSecurityContext context, String schemaName)
    {
        delegate().checkCanCreateSchema(context, schemaName);
    }

    @Override
    public void checkCanDropSchema(ConnectorSecurityContext context, String schemaName)
    {
        delegate().checkCanDropSchema(context, schemaName);
    }

    @Override
    public void checkCanRenameSchema(ConnectorSecurityContext context, String schemaName, String newSchemaName)
    {
        delegate().checkCanRenameSchema(context, schemaName, newSchemaName);
    }

    @Override
    public void checkCanShowSchemas(ConnectorSecurityContext context)
    {
        delegate().checkCanShowSchemas(context);
    }

    @Override
    public Set<String> filterSchemas(ConnectorSecurityContext context, Set<String> schemaNames)
    {
        return delegate().filterSchemas(context, schemaNames);
    }

    @Override
    public void checkCanCreateTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanCreateTable(context, tableName);
    }

    @Override
    public void checkCanDropTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanDropTable(context, tableName);
    }

    @Override
    public void checkCanRenameTable(ConnectorSecurityContext context, SchemaTableName tableName, SchemaTableName newTableName)
    {
        delegate().checkCanRenameTable(context, tableName, newTableName);
    }

    @Override
    public void checkCanSetTableComment(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanSetTableComment(context, tableName);
    }

    @Override
    public void checkCanShowTablesMetadata(ConnectorSecurityContext context, String schemaName)
    {
        delegate().checkCanShowTablesMetadata(context, schemaName);
    }

    @Override
    public Set<SchemaTableName> filterTables(ConnectorSecurityContext context, Set<SchemaTableName> tableNames)
    {
        return delegate().filterTables(context, tableNames);
    }

    @Override
    public void checkCanShowColumnsMetadata(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanShowColumnsMetadata(context, tableName);
    }

    @Override
    public List<ColumnMetadata> filterColumns(ConnectorSecurityContext context, SchemaTableName tableName, List<ColumnMetadata> columns)
    {
        return delegate().filterColumns(context, tableName, columns);
    }

    @Override
    public void checkCanAddColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanAddColumn(context, tableName);
    }

    @Override
    public void checkCanDropColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanDropColumn(context, tableName);
    }

    @Override
    public void checkCanRenameColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanRenameColumn(context, tableName);
    }

    @Override
    public void checkCanSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
        delegate().checkCanSelectFromColumns(context, tableName, columnNames);
    }

    @Override
    public void checkCanInsertIntoTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanInsertIntoTable(context, tableName);
    }

    @Override
    public void checkCanDeleteFromTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        delegate().checkCanDeleteFromTable(context, tableName);
    }

    @Override
    public void checkCanCreateView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
        delegate().checkCanCreateView(context, viewName);
    }

    @Override
    public void checkCanDropView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
        delegate().checkCanDropView(context, viewName);
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
        delegate().checkCanCreateViewWithSelectFromColumns(context, tableName, columnNames);
    }

    @Override
    public void checkCanSetCatalogSessionProperty(ConnectorSecurityContext context, String propertyName)
    {
        delegate().checkCanSetCatalogSessionProperty(context, propertyName);
    }

    @Override
    public void checkCanGrantTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal grantee, boolean withGrantOption)
    {
        delegate().checkCanGrantTablePrivilege(context, privilege, tableName, grantee, withGrantOption);
    }

    @Override
    public void checkCanRevokeTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal revokee, boolean grantOptionFor)
    {
        delegate().checkCanRevokeTablePrivilege(context, privilege, tableName, revokee, grantOptionFor);
    }

    @Override
    public void checkCanCreateRole(ConnectorSecurityContext context, String role, Optional<PrestoPrincipal> grantor)
    {
        delegate().checkCanCreateRole(context, role, grantor);
    }

    @Override
    public void checkCanDropRole(ConnectorSecurityContext context, String role)
    {
        delegate().checkCanDropRole(context, role);
    }

    @Override
    public void checkCanGrantRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean withAdminOption, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        delegate().checkCanGrantRoles(context, roles, grantees, withAdminOption, grantor, catalogName);
    }

    @Override
    public void checkCanRevokeRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOptionFor, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        delegate().checkCanRevokeRoles(context, roles, grantees, adminOptionFor, grantor, catalogName);
    }

    @Override
    public void checkCanSetRole(ConnectorSecurityContext context, String role, String catalogName)
    {
        delegate().checkCanSetRole(context, role, catalogName);
    }

    @Override
    public void checkCanShowRoles(ConnectorSecurityContext context, String catalogName)
    {
        delegate().checkCanShowRoles(context, catalogName);
    }

    @Override
    public void checkCanShowCurrentRoles(ConnectorSecurityContext context, String catalogName)
    {
        delegate().checkCanShowCurrentRoles(context, catalogName);
    }

    @Override
    public void checkCanShowRoleGrants(ConnectorSecurityContext context, String catalogName)
    {
        delegate().checkCanShowRoleGrants(context, catalogName);
    }
}
