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
package io.prestosql.plugin.hive.security;

import io.prestosql.plugin.hive.HiveTransactionHandle;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorAccessControl;
import io.prestosql.spi.connector.ConnectorSecurityContext;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.prestosql.spi.security.AccessDeniedException.denyAddColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyCommentTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyDropTable;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameTable;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class LegacyAccessControl
        implements ConnectorAccessControl
{
    private final Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider;
    private final boolean allowDropTable;
    private final boolean allowRenameTable;
    private final boolean allowCommentTable;
    private final boolean allowAddColumn;
    private final boolean allowDropColumn;
    private final boolean allowRenameColumn;

    @Inject
    public LegacyAccessControl(
            Function<HiveTransactionHandle, SemiTransactionalHiveMetastore> metastoreProvider,
            LegacySecurityConfig securityConfig)
    {
        this.metastoreProvider = requireNonNull(metastoreProvider, "metastoreProvider is null");

        requireNonNull(securityConfig, "securityConfig is null");
        allowDropTable = securityConfig.getAllowDropTable();
        allowRenameTable = securityConfig.getAllowRenameTable();
        allowCommentTable = securityConfig.getAllowCommentTable();
        allowAddColumn = securityConfig.getAllowAddColumn();
        allowDropColumn = securityConfig.getAllowDropColumn();
        allowRenameColumn = securityConfig.getAllowRenameColumn();
    }

    @Override
    public void checkCanCreateSchema(ConnectorSecurityContext context, String schemaName)
    {
    }

    @Override
    public void checkCanDropSchema(ConnectorSecurityContext context, String schemaName)
    {
    }

    @Override
    public void checkCanRenameSchema(ConnectorSecurityContext context, String schemaName, String newSchemaName)
    {
    }

    @Override
    public void checkCanShowSchemas(ConnectorSecurityContext context)
    {
    }

    @Override
    public Set<String> filterSchemas(ConnectorSecurityContext context, Set<String> schemaNames)
    {
        return schemaNames;
    }

    @Override
    public void checkCanCreateTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
    }

    @Override
    public void checkCanDropTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        if (!allowDropTable) {
            denyDropTable(tableName.toString());
        }

        Optional<Table> target = metastoreProvider.apply(((HiveTransactionHandle) context.getTransactionHandle())).getTable(new HiveIdentity(context.getIdentity()), tableName.getSchemaName(), tableName.getTableName());

        if (!target.isPresent()) {
            denyDropTable(tableName.toString(), "Table not found");
        }

        if (!context.getIdentity().getUser().equals(target.get().getOwner())) {
            denyDropTable(tableName.toString(), format("Owner of the table ('%s') is different from session user ('%s')", target.get().getOwner(), context.getIdentity().getUser()));
        }
    }

    @Override
    public void checkCanRenameTable(ConnectorSecurityContext context, SchemaTableName tableName, SchemaTableName newTableName)
    {
        if (!allowRenameTable) {
            denyRenameTable(tableName.toString(), newTableName.toString());
        }
    }

    @Override
    public void checkCanSetTableComment(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        if (!allowCommentTable) {
            denyCommentTable(tableName.toString());
        }
    }

    @Override
    public void checkCanShowTablesMetadata(ConnectorSecurityContext context, String schemaName)
    {
    }

    @Override
    public Set<SchemaTableName> filterTables(ConnectorSecurityContext context, Set<SchemaTableName> tableNames)
    {
        return tableNames;
    }

    @Override
    public void checkCanShowColumnsMetadata(ConnectorSecurityContext context, SchemaTableName tableName)
    {
    }

    @Override
    public List<ColumnMetadata> filterColumns(ConnectorSecurityContext context, SchemaTableName tableName, List<ColumnMetadata> columns)
    {
        return columns;
    }

    @Override
    public void checkCanAddColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        if (!allowAddColumn) {
            denyAddColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanDropColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        if (!allowDropColumn) {
            denyDropColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanRenameColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        if (!allowRenameColumn) {
            denyRenameColumn(tableName.toString());
        }
    }

    @Override
    public void checkCanSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
    }

    @Override
    public void checkCanInsertIntoTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
    }

    @Override
    public void checkCanDeleteFromTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
    }

    @Override
    public void checkCanCreateView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
    }

    @Override
    public void checkCanDropView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
    }

    @Override
    public void checkCanSetCatalogSessionProperty(ConnectorSecurityContext context, String propertyName)
    {
    }

    @Override
    public void checkCanGrantTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal grantee, boolean withGrantOption)
    {
    }

    @Override
    public void checkCanRevokeTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal revokee, boolean grantOptionFor)
    {
    }

    @Override
    public void checkCanCreateRole(ConnectorSecurityContext context, String role, Optional<PrestoPrincipal> grantor)
    {
    }

    @Override
    public void checkCanDropRole(ConnectorSecurityContext context, String role)
    {
    }

    @Override
    public void checkCanGrantRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean withAdminOption, Optional<PrestoPrincipal> grantor, String catalogName)
    {
    }

    @Override
    public void checkCanRevokeRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOptionFor, Optional<PrestoPrincipal> grantor, String catalogName)
    {
    }

    @Override
    public void checkCanSetRole(ConnectorSecurityContext context, String role, String catalogName)
    {
    }

    @Override
    public void checkCanShowRoles(ConnectorSecurityContext context, String catalogName)
    {
    }

    @Override
    public void checkCanShowCurrentRoles(ConnectorSecurityContext context, String catalogName)
    {
    }

    @Override
    public void checkCanShowRoleGrants(ConnectorSecurityContext context, String catalogName)
    {
    }
}
