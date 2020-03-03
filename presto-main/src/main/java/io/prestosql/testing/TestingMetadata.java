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
package io.prestosql.testing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.airlift.slice.Slice;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorNewTableLayout;
import io.prestosql.spi.connector.ConnectorOutputMetadata;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorTableProperties;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.SchemaTablePrefix;
import io.prestosql.spi.connector.ViewNotFoundException;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.statistics.ComputedStatistics;
import io.prestosql.spi.type.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static java.util.Objects.requireNonNull;

public class TestingMetadata
        implements ConnectorMetadata
{
    private final ConcurrentMap<SchemaTableName, ConnectorTableMetadata> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<SchemaTableName, ConnectorViewDefinition> views = new ConcurrentHashMap<>();

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        Set<String> schemaNames = new HashSet<>();

        for (SchemaTableName schemaTableName : tables.keySet()) {
            schemaNames.add(schemaTableName.getSchemaName());
        }

        return ImmutableList.copyOf(schemaNames);
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        if (!tables.containsKey(tableName)) {
            return null;
        }
        return new TestingTableHandle(tableName);
    }

    @Override
    public ConnectorTableHandle getTableHandleForStatisticsCollection(ConnectorSession session, SchemaTableName tableName, Map<String, Object> analyzeProperties)
    {
        return getTableHandle(session, tableName);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        SchemaTableName tableName = getTableName(tableHandle);
        ConnectorTableMetadata tableMetadata = tables.get(tableName);
        checkArgument(tableMetadata != null, "Table %s does not exist", tableName);
        return tableMetadata;
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ImmutableMap.Builder<String, ColumnHandle> builder = ImmutableMap.builder();
        int index = 0;
        for (ColumnMetadata columnMetadata : getTableMetadata(session, tableHandle).getColumns()) {
            builder.put(columnMetadata.getName(), new TestingColumnHandle(columnMetadata.getName(), index, columnMetadata.getType()));
            index++;
        }
        return builder.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");

        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> tableColumns = ImmutableMap.builder();
        for (SchemaTableName tableName : listTables(session, prefix.getSchema())) {
            ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
            for (ColumnMetadata column : tables.get(tableName).getColumns()) {
                columns.add(new ColumnMetadata(column.getName(), column.getType()));
            }
            tableColumns.put(tableName, columns.build());
        }
        return tableColumns.build();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        SchemaTableName tableName = getTableName(tableHandle);
        int columnIndex = ((TestingColumnHandle) columnHandle).getOrdinalPosition();
        return tables.get(tableName).getColumns().get(columnIndex);
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (SchemaTableName tableName : tables.keySet()) {
            if (schemaName.map(tableName.getSchemaName()::equals).orElse(true)) {
                builder.add(tableName);
            }
        }
        return builder.build();
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        // TODO: use locking to do this properly
        ConnectorTableMetadata table = getTableMetadata(session, tableHandle);
        if (tables.putIfAbsent(newTableName, table) != null) {
            throw new IllegalArgumentException("Target table already exists: " + newTableName);
        }
        tables.remove(table.getTable(), table);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        ConnectorTableMetadata existingTable = tables.putIfAbsent(tableMetadata.getTable(), tableMetadata);
        if (existingTable != null && !ignoreExisting) {
            throw new IllegalArgumentException("Target table already exists: " + tableMetadata.getTable());
        }
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        tables.remove(getTableName(tableHandle));
    }

    @Override
    public void createView(ConnectorSession session, SchemaTableName viewName, ConnectorViewDefinition definition, boolean replace)
    {
        if (replace) {
            views.put(viewName, definition);
        }
        else if (views.putIfAbsent(viewName, definition) != null) {
            throw new PrestoException(ALREADY_EXISTS, "View already exists: " + viewName);
        }
    }

    @Override
    public void dropView(ConnectorSession session, SchemaTableName viewName)
    {
        if (views.remove(viewName) == null) {
            throw new ViewNotFoundException(viewName);
        }
    }

    @Override
    public List<SchemaTableName> listViews(ConnectorSession session, Optional<String> schemaName)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (SchemaTableName viewName : views.keySet()) {
            if (schemaName.map(viewName.getSchemaName()::equals).orElse(true)) {
                builder.add(viewName);
            }
        }
        return builder.build();
    }

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews(ConnectorSession session, Optional<String> schemaName)
    {
        SchemaTablePrefix prefix = schemaName.map(SchemaTablePrefix::new).orElseGet(SchemaTablePrefix::new);
        return ImmutableMap.copyOf(Maps.filterKeys(views, prefix::matches));
    }

    @Override
    public Optional<ConnectorViewDefinition> getView(ConnectorSession session, SchemaTableName viewName)
    {
        return Optional.ofNullable(views.get(viewName));
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        createTable(session, tableMetadata, false);
        return TestingHandle.INSTANCE;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return TestingHandle.INSTANCE;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        ConnectorTableMetadata tableMetadata = getTableMetadata(session, tableHandle);
        SchemaTableName tableName = getTableName(tableHandle);
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        columns.addAll(tableMetadata.getColumns());
        columns.add(column);
        tables.put(tableName, new ConnectorTableMetadata(tableName, columns.build(), tableMetadata.getProperties(), tableMetadata.getComment()));
    }

    @Override
    public void renameColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle source, String target)
    {
        ConnectorTableMetadata tableMetadata = getTableMetadata(session, tableHandle);
        SchemaTableName tableName = getTableName(tableHandle);
        ColumnMetadata columnMetadata = getColumnMetadata(session, tableHandle, source);
        List<ColumnMetadata> columns = new ArrayList<>(tableMetadata.getColumns());
        columns.set(columns.indexOf(columnMetadata), new ColumnMetadata(target, columnMetadata.getType(), columnMetadata.getComment(), columnMetadata.isHidden()));
        tables.put(tableName, new ConnectorTableMetadata(tableName, ImmutableList.copyOf(columns), tableMetadata.getProperties(), tableMetadata.getComment()));
    }

    @Override
    public void grantTablePrivileges(ConnectorSession session, SchemaTableName tableName, Set<Privilege> privileges, PrestoPrincipal grantee, boolean grantOption) {}

    @Override
    public void revokeTablePrivileges(ConnectorSession session, SchemaTableName tableName, Set<Privilege> privileges, PrestoPrincipal grantee, boolean grantOption) {}

    @Override
    public boolean usesLegacyTableLayouts()
    {
        return false;
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        return new ConnectorTableProperties();
    }

    public void clear()
    {
        views.clear();
        tables.clear();
    }

    private static SchemaTableName getTableName(ConnectorTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        checkArgument(tableHandle instanceof TestingTableHandle, "tableHandle is not an instance of TestingTableHandle");
        TestingTableHandle testingTableHandle = (TestingTableHandle) tableHandle;
        return testingTableHandle.getTableName();
    }

    public static class TestingTableHandle
            implements ConnectorTableHandle
    {
        private final SchemaTableName tableName;

        public TestingTableHandle()
        {
            this(new SchemaTableName("test-schema", "test-table"));
        }

        @JsonCreator
        public TestingTableHandle(@JsonProperty("tableName") SchemaTableName schemaTableName)
        {
            this.tableName = requireNonNull(schemaTableName, "schemaTableName is null");
        }

        @JsonProperty
        public SchemaTableName getTableName()
        {
            return tableName;
        }
    }

    public static class TestingColumnHandle
            implements ColumnHandle
    {
        private final String name;
        private final OptionalInt ordinalPosition;
        private final Optional<Type> type;

        public TestingColumnHandle(String name)
        {
            this(name, OptionalInt.empty(), Optional.empty());
        }

        public TestingColumnHandle(String name, int ordinalPosition, Type type)
        {
            this(name, OptionalInt.of(ordinalPosition), Optional.of(type));
        }

        @JsonCreator
        public TestingColumnHandle(
                @JsonProperty("name") String name,
                @JsonProperty("ordinalPosition") OptionalInt ordinalPosition,
                @JsonProperty("type") Optional<Type> type)
        {
            this.name = requireNonNull(name, "name is null");
            this.ordinalPosition = requireNonNull(ordinalPosition, "ordinalPosition is null");
            this.type = requireNonNull(type, "type is null");
        }

        @JsonProperty
        public String getName()
        {
            return name;
        }

        public int getOrdinalPosition()
        {
            return ordinalPosition.orElseThrow(() -> new UnsupportedOperationException("Testing handle was created without ordinal position"));
        }

        public Type getType()
        {
            return type.orElseThrow(() -> new UnsupportedOperationException("Testing handle was created without type"));
        }

        @JsonProperty("ordinalPosition")
        public OptionalInt getJsonOrdinalPosition()
        {
            return ordinalPosition;
        }

        @JsonProperty("type")
        public Optional<Type> getJsonType()
        {
            return type;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestingColumnHandle that = (TestingColumnHandle) o;
            return Objects.equals(name, that.name) &&
                    Objects.equals(ordinalPosition, that.ordinalPosition) &&
                    Objects.equals(type, that.type);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name, ordinalPosition, type);
        }
    }
}
