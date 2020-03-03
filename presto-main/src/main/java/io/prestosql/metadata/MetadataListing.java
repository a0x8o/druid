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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.GrantInfo;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

public final class MetadataListing
{
    private MetadataListing() {}

    public static SortedMap<String, CatalogName> listCatalogs(Session session, Metadata metadata, AccessControl accessControl)
    {
        Map<String, CatalogName> catalogNames = metadata.getCatalogNames(session);
        Set<String> allowedCatalogs = accessControl.filterCatalogs(session.getIdentity(), catalogNames.keySet());

        ImmutableSortedMap.Builder<String, CatalogName> result = ImmutableSortedMap.naturalOrder();
        for (Map.Entry<String, CatalogName> entry : catalogNames.entrySet()) {
            if (allowedCatalogs.contains(entry.getKey())) {
                result.put(entry);
            }
        }
        return result.build();
    }

    public static SortedSet<String> listSchemas(Session session, Metadata metadata, AccessControl accessControl, String catalogName)
    {
        Set<String> schemaNames = ImmutableSet.copyOf(metadata.listSchemaNames(session, catalogName));
        return ImmutableSortedSet.copyOf(accessControl.filterSchemas(session.toSecurityContext(), catalogName, schemaNames));
    }

    public static Set<SchemaTableName> listTables(Session session, Metadata metadata, AccessControl accessControl, QualifiedTablePrefix prefix)
    {
        Set<SchemaTableName> tableNames = metadata.listTables(session, prefix).stream()
                .map(QualifiedObjectName::asSchemaTableName)
                .collect(toImmutableSet());
        return accessControl.filterTables(session.toSecurityContext(), prefix.getCatalogName(), tableNames);
    }

    public static Set<SchemaTableName> listViews(Session session, Metadata metadata, AccessControl accessControl, QualifiedTablePrefix prefix)
    {
        Set<SchemaTableName> tableNames = metadata.listViews(session, prefix).stream()
                .map(QualifiedObjectName::asSchemaTableName)
                .collect(toImmutableSet());
        return accessControl.filterTables(session.toSecurityContext(), prefix.getCatalogName(), tableNames);
    }

    public static Set<GrantInfo> listTablePrivileges(Session session, Metadata metadata, AccessControl accessControl, QualifiedTablePrefix prefix)
    {
        List<GrantInfo> grants = metadata.listTablePrivileges(session, prefix);
        Set<SchemaTableName> allowedTables = accessControl.filterTables(
                session.toSecurityContext(),
                prefix.getCatalogName(),
                grants.stream().map(GrantInfo::getSchemaTableName).collect(toImmutableSet()));

        return grants.stream()
                .filter(grantInfo -> allowedTables.contains(grantInfo.getSchemaTableName()))
                .collect(toImmutableSet());
    }

    public static Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(Session session, Metadata metadata, AccessControl accessControl, QualifiedTablePrefix prefix)
    {
        Map<SchemaTableName, List<ColumnMetadata>> tableColumns = metadata.listTableColumns(session, prefix).entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().asSchemaTableName(), Entry::getValue));
        Set<SchemaTableName> allowedTables = accessControl.filterTables(
                session.toSecurityContext(),
                prefix.getCatalogName(),
                tableColumns.keySet());

        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> result = ImmutableMap.builder();
        for (Entry<SchemaTableName, List<ColumnMetadata>> entry : tableColumns.entrySet()) {
            if (allowedTables.contains(entry.getKey())) {
                result.put(entry.getKey(), accessControl.filterColumns(
                        session.toSecurityContext(),
                        new CatalogSchemaTableName(prefix.getCatalogName(), entry.getKey()),
                        entry.getValue()));
            }
        }
        return result.build();
    }
}
