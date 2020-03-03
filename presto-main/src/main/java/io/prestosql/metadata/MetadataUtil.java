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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.prestosql.Session;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.tree.GrantorSpecification;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.PrincipalSpecification;
import io.prestosql.sql.tree.QualifiedName;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.StandardErrorCode.MISSING_CATALOG_NAME;
import static io.prestosql.spi.StandardErrorCode.MISSING_SCHEMA_NAME;
import static io.prestosql.spi.StandardErrorCode.SYNTAX_ERROR;
import static io.prestosql.spi.security.PrincipalType.ROLE;
import static io.prestosql.spi.security.PrincipalType.USER;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public final class MetadataUtil
{
    private MetadataUtil() {}

    public static void checkTableName(String catalogName, Optional<String> schemaName, Optional<String> tableName)
    {
        checkCatalogName(catalogName);
        schemaName.ifPresent(name -> checkLowerCase(name, "schemaName"));
        tableName.ifPresent(name -> checkLowerCase(name, "tableName"));

        checkArgument(schemaName.isPresent() || !tableName.isPresent(), "tableName specified but schemaName is missing");
    }

    public static String checkCatalogName(String catalogName)
    {
        return checkLowerCase(catalogName, "catalogName");
    }

    public static String checkSchemaName(String schemaName)
    {
        return checkLowerCase(schemaName, "schemaName");
    }

    public static String checkTableName(String tableName)
    {
        return checkLowerCase(tableName, "tableName");
    }

    public static void checkObjectName(String catalogName, String schemaName, String objectName)
    {
        checkLowerCase(catalogName, "catalogName");
        checkLowerCase(schemaName, "schemaName");
        checkLowerCase(objectName, "objectName");
    }

    public static String checkLowerCase(String value, String name)
    {
        if (value == null) {
            throw new NullPointerException(format("%s is null", name));
        }
        checkArgument(value.equals(value.toLowerCase(ENGLISH)), "%s is not lowercase: %s", name, value);
        return value;
    }

    public static ColumnMetadata findColumnMetadata(ConnectorTableMetadata tableMetadata, String columnName)
    {
        for (ColumnMetadata columnMetadata : tableMetadata.getColumns()) {
            if (columnName.equals(columnMetadata.getName())) {
                return columnMetadata;
            }
        }
        return null;
    }

    public static String createCatalogName(Session session, Node node)
    {
        Optional<String> sessionCatalog = session.getCatalog();

        if (!sessionCatalog.isPresent()) {
            throw semanticException(MISSING_CATALOG_NAME, node, "Session catalog must be set");
        }

        return sessionCatalog.get();
    }

    public static CatalogSchemaName createCatalogSchemaName(Session session, Node node, Optional<QualifiedName> schema)
    {
        String catalogName = session.getCatalog().orElse(null);
        String schemaName = session.getSchema().orElse(null);

        if (schema.isPresent()) {
            List<String> parts = schema.get().getParts();
            if (parts.size() > 2) {
                throw semanticException(SYNTAX_ERROR, node, "Too many parts in schema name: %s", schema.get());
            }
            if (parts.size() == 2) {
                catalogName = parts.get(0);
            }
            schemaName = schema.get().getSuffix();
        }

        if (catalogName == null) {
            throw semanticException(MISSING_CATALOG_NAME, node, "Catalog must be specified when session catalog is not set");
        }
        if (schemaName == null) {
            throw semanticException(MISSING_SCHEMA_NAME, node, "Schema must be specified when session schema is not set");
        }

        return new CatalogSchemaName(catalogName, schemaName);
    }

    public static QualifiedObjectName createQualifiedObjectName(Session session, Node node, QualifiedName name)
    {
        requireNonNull(session, "session is null");
        requireNonNull(name, "name is null");
        if (name.getParts().size() > 3) {
            throw new PrestoException(SYNTAX_ERROR, format("Too many dots in table name: %s", name));
        }

        List<String> parts = Lists.reverse(name.getParts());
        String objectName = parts.get(0);
        String schemaName = (parts.size() > 1) ? parts.get(1) : session.getSchema().orElseThrow(() ->
                semanticException(MISSING_SCHEMA_NAME, node, "Schema must be specified when session schema is not set"));
        String catalogName = (parts.size() > 2) ? parts.get(2) : session.getCatalog().orElseThrow(() ->
                semanticException(MISSING_CATALOG_NAME, node, "Catalog must be specified when session catalog is not set"));

        return new QualifiedObjectName(catalogName, schemaName, objectName);
    }

    public static PrestoPrincipal createPrincipal(Session session, GrantorSpecification specification)
    {
        GrantorSpecification.Type type = specification.getType();
        switch (type) {
            case PRINCIPAL:
                return createPrincipal(specification.getPrincipal().get());
            case CURRENT_USER:
                return new PrestoPrincipal(USER, session.getIdentity().getUser());
            case CURRENT_ROLE:
                // TODO: will be implemented once the "SET ROLE" statement is introduced
                throw new UnsupportedOperationException("CURRENT_ROLE is not yet supported");
            default:
                throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    public static PrestoPrincipal createPrincipal(PrincipalSpecification specification)
    {
        PrincipalSpecification.Type type = specification.getType();
        switch (type) {
            case UNSPECIFIED:
            case USER:
                return new PrestoPrincipal(USER, specification.getName().getValue());
            case ROLE:
                return new PrestoPrincipal(ROLE, specification.getName().getValue());
            default:
                throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    public static boolean tableExists(Metadata metadata, Session session, String table)
    {
        if (!session.getCatalog().isPresent() || !session.getSchema().isPresent()) {
            return false;
        }
        QualifiedObjectName name = new QualifiedObjectName(session.getCatalog().get(), session.getSchema().get(), table);
        return metadata.getTableHandle(session, name).isPresent();
    }

    public static class SchemaMetadataBuilder
    {
        public static SchemaMetadataBuilder schemaMetadataBuilder()
        {
            return new SchemaMetadataBuilder();
        }

        private final ImmutableMap.Builder<SchemaTableName, ConnectorTableMetadata> tables = ImmutableMap.builder();

        public SchemaMetadataBuilder table(ConnectorTableMetadata tableMetadata)
        {
            tables.put(tableMetadata.getTable(), tableMetadata);
            return this;
        }

        public ImmutableMap<SchemaTableName, ConnectorTableMetadata> build()
        {
            return tables.build();
        }
    }

    public static class TableMetadataBuilder
    {
        public static TableMetadataBuilder tableMetadataBuilder(String schemaName, String tableName)
        {
            return new TableMetadataBuilder(new SchemaTableName(schemaName, tableName));
        }

        public static TableMetadataBuilder tableMetadataBuilder(SchemaTableName tableName)
        {
            return new TableMetadataBuilder(tableName);
        }

        private final SchemaTableName tableName;
        private final ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        private final ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        private final Optional<String> comment;

        private TableMetadataBuilder(SchemaTableName tableName)
        {
            this(tableName, Optional.empty());
        }

        private TableMetadataBuilder(SchemaTableName tableName, Optional<String> comment)
        {
            this.tableName = tableName;
            this.comment = comment;
        }

        public TableMetadataBuilder column(String columnName, Type type)
        {
            columns.add(new ColumnMetadata(columnName, type));
            return this;
        }

        public TableMetadataBuilder hiddenColumn(String columnName, Type type)
        {
            columns.add(new ColumnMetadata(columnName, type, null, true));
            return this;
        }

        public TableMetadataBuilder property(String name, Object value)
        {
            properties.put(name, value);
            return this;
        }

        public ConnectorTableMetadata build()
        {
            return new ConnectorTableMetadata(tableName, columns.build(), properties.build(), comment);
        }
    }
}
