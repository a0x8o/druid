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
package io.prestosql.spi.connector;

import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.prestosql.spi.security.AccessDeniedException.denyAddColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyCommentTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateRole;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateView;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static io.prestosql.spi.security.AccessDeniedException.denyDeleteTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyDropRole;
import static io.prestosql.spi.security.AccessDeniedException.denyDropSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyDropTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropView;
import static io.prestosql.spi.security.AccessDeniedException.denyGrantRoles;
import static io.prestosql.spi.security.AccessDeniedException.denyGrantTablePrivilege;
import static io.prestosql.spi.security.AccessDeniedException.denyInsertTable;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameTable;
import static io.prestosql.spi.security.AccessDeniedException.denyRevokeRoles;
import static io.prestosql.spi.security.AccessDeniedException.denyRevokeTablePrivilege;
import static io.prestosql.spi.security.AccessDeniedException.denySelectColumns;
import static io.prestosql.spi.security.AccessDeniedException.denySetCatalogSessionProperty;
import static io.prestosql.spi.security.AccessDeniedException.denySetRole;
import static io.prestosql.spi.security.AccessDeniedException.denyShowColumnsMetadata;
import static io.prestosql.spi.security.AccessDeniedException.denyShowCurrentRoles;
import static io.prestosql.spi.security.AccessDeniedException.denyShowRoleGrants;
import static io.prestosql.spi.security.AccessDeniedException.denyShowRoles;
import static io.prestosql.spi.security.AccessDeniedException.denyShowSchemas;
import static io.prestosql.spi.security.AccessDeniedException.denyShowTablesMetadata;
import static java.util.Collections.emptySet;

public interface ConnectorAccessControl
{
    /**
     * Check if identity is allowed to create the specified schema in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanCreateSchema(ConnectorSecurityContext context, String schemaName)
    {
        denyCreateSchema(schemaName);
    }

    /**
     * Check if identity is allowed to drop the specified schema in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanDropSchema(ConnectorSecurityContext context, String schemaName)
    {
        denyDropSchema(schemaName);
    }

    /**
     * Check if identity is allowed to rename the specified schema in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanRenameSchema(ConnectorSecurityContext context, String schemaName, String newSchemaName)
    {
        denyRenameSchema(schemaName, newSchemaName);
    }

    /**
     * Check if identity is allowed to execute SHOW SCHEMAS in a catalog.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterSchemas} method must handle filter all results for unauthorized users,
     * since there are multiple way to list schemas.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowSchemas(ConnectorSecurityContext context)
    {
        denyShowSchemas();
    }

    /**
     * Filter the list of schemas to those visible to the identity.
     */
    default Set<String> filterSchemas(ConnectorSecurityContext context, Set<String> schemaNames)
    {
        return emptySet();
    }

    /**
     * Check if identity is allowed to create the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanCreateTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyCreateTable(tableName.toString());
    }

    /**
     * Check if identity is allowed to drop the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanDropTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyDropTable(tableName.toString());
    }

    /**
     * Check if identity is allowed to rename the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanRenameTable(ConnectorSecurityContext context, SchemaTableName tableName, SchemaTableName newTableName)
    {
        denyRenameTable(tableName.toString(), newTableName.toString());
    }

    /**
     * Check if identity is allowed to comment the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanSetTableComment(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyCommentTable(tableName.toString());
    }

    /**
     * Check if identity is allowed to show metadata of tables by executing SHOW TABLES, SHOW GRANTS etc. in a catalog.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterTables} method must filter all results for unauthorized users,
     * since there are multiple ways to list tables.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowTablesMetadata(ConnectorSecurityContext context, String schemaName)
    {
        denyShowTablesMetadata(schemaName);
    }

    /**
     * Filter the list of tables and views to those visible to the identity.
     */
    default Set<SchemaTableName> filterTables(ConnectorSecurityContext context, Set<SchemaTableName> tableNames)
    {
        return emptySet();
    }

    /**
     * Check if identity is allowed to show columns of tables by executing SHOW COLUMNS, DESCRIBE etc.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterColumns} method must filter all results for unauthorized users,
     * since there are multiple ways to list columns.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowColumnsMetadata(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyShowColumnsMetadata(tableName.getTableName());
    }

    /**
     * Filter the list of columns to those visible to the identity.
     */
    default List<ColumnMetadata> filterColumns(ConnectorSecurityContext context, SchemaTableName tableName, List<ColumnMetadata> columns)
    {
        return Collections.emptyList();
    }

    /**
     * Check if identity is allowed to add columns to the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanAddColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyAddColumn(tableName.toString());
    }

    /**
     * Check if identity is allowed to drop columns from the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanDropColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyDropColumn(tableName.toString());
    }

    /**
     * Check if identity is allowed to rename a column in the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanRenameColumn(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyRenameColumn(tableName.toString());
    }

    /**
     * Check if identity is allowed to select from the specified columns in a relation.  The column set can be empty.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
        denySelectColumns(tableName.toString(), columnNames);
    }

    /**
     * Check if identity is allowed to insert into the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanInsertIntoTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyInsertTable(tableName.toString());
    }

    /**
     * Check if identity is allowed to delete from the specified table in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanDeleteFromTable(ConnectorSecurityContext context, SchemaTableName tableName)
    {
        denyDeleteTable(tableName.toString());
    }

    /**
     * Check if identity is allowed to create the specified view in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanCreateView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
        denyCreateView(viewName.toString());
    }

    /**
     * Check if identity is allowed to drop the specified view in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanDropView(ConnectorSecurityContext context, SchemaTableName viewName)
    {
        denyDropView(viewName.toString());
    }

    /**
     * Check if identity is allowed to create a view that selects from the specified columns in a relation.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanCreateViewWithSelectFromColumns(ConnectorSecurityContext context, SchemaTableName tableName, Set<String> columnNames)
    {
        denyCreateViewWithSelect(tableName.toString(), context.getIdentity());
    }

    /**
     * Check if identity is allowed to set the specified property in this catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanSetCatalogSessionProperty(ConnectorSecurityContext context, String propertyName)
    {
        denySetCatalogSessionProperty(propertyName);
    }

    /**
     * Check if identity is allowed to grant to any other user the specified privilege on the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanGrantTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal grantee, boolean withGrantOption)
    {
        denyGrantTablePrivilege(privilege.toString(), tableName.toString());
    }

    /**
     * Check if identity is allowed to revoke the specified privilege on the specified table from any user.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanRevokeTablePrivilege(ConnectorSecurityContext context, Privilege privilege, SchemaTableName tableName, PrestoPrincipal revokee, boolean grantOptionFor)
    {
        denyRevokeTablePrivilege(privilege.toString(), tableName.toString());
    }

    default void checkCanCreateRole(ConnectorSecurityContext context, String role, Optional<PrestoPrincipal> grantor)
    {
        denyCreateRole(role);
    }

    default void checkCanDropRole(ConnectorSecurityContext context, String role)
    {
        denyDropRole(role);
    }

    default void checkCanGrantRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean withAdminOption, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        denyGrantRoles(roles, grantees);
    }

    default void checkCanRevokeRoles(ConnectorSecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOptionFor, Optional<PrestoPrincipal> grantor, String catalogName)
    {
        denyRevokeRoles(roles, grantees);
    }

    default void checkCanSetRole(ConnectorSecurityContext context, String role, String catalogName)
    {
        denySetRole(role);
    }

    /**
     * Check if identity is allowed to show roles on the specified catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowRoles(ConnectorSecurityContext context, String catalogName)
    {
        denyShowRoles(catalogName);
    }

    /**
     * Check if identity is allowed to show current roles on the specified catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowCurrentRoles(ConnectorSecurityContext context, String catalogName)
    {
        denyShowCurrentRoles(catalogName);
    }

    /**
     * Check if identity is allowed to show its own role grants on the specified catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    default void checkCanShowRoleGrants(ConnectorSecurityContext context, String catalogName)
    {
        denyShowRoleGrants(catalogName);
    }
}
