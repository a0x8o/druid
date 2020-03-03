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
package io.prestosql.plugin.blackhole;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.slice.Slice;
import io.airlift.units.Duration;
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
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.SchemaNotFoundException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.SchemaTablePrefix;
import io.prestosql.spi.statistics.ColumnStatistics;
import io.prestosql.spi.statistics.ComputedStatistics;
import io.prestosql.spi.statistics.DoubleRange;
import io.prestosql.spi.statistics.Estimate;
import io.prestosql.spi.statistics.TableStatistics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.prestosql.plugin.blackhole.BlackHoleConnector.DISTRIBUTED_ON;
import static io.prestosql.plugin.blackhole.BlackHoleConnector.FIELD_LENGTH_PROPERTY;
import static io.prestosql.plugin.blackhole.BlackHoleConnector.PAGES_PER_SPLIT_PROPERTY;
import static io.prestosql.plugin.blackhole.BlackHoleConnector.PAGE_PROCESSING_DELAY;
import static io.prestosql.plugin.blackhole.BlackHoleConnector.ROWS_PER_PAGE_PROPERTY;
import static io.prestosql.plugin.blackhole.BlackHoleConnector.SPLIT_COUNT_PROPERTY;
import static io.prestosql.plugin.blackhole.BlackHolePageSourceProvider.isNumericType;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class BlackHoleMetadata
        implements ConnectorMetadata
{
    public static final String SCHEMA_NAME = "default";

    private final List<String> schemas = new ArrayList<>();
    private final Map<SchemaTableName, BlackHoleTableHandle> tables = new ConcurrentHashMap<>();

    public BlackHoleMetadata()
    {
        schemas.add(SCHEMA_NAME);
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return ImmutableList.copyOf(schemas);
    }

    @Override
    public synchronized void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties)
    {
        if (schemas.contains(schemaName)) {
            throw new PrestoException(ALREADY_EXISTS, format("Schema [%s] already exists", schemaName));
        }
        schemas.add(schemaName);
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return tables.get(tableName);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        BlackHoleTableHandle blackHoleTableHandle = (BlackHoleTableHandle) tableHandle;
        return blackHoleTableHandle.toTableMetadata();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        return tables.values().stream()
                .filter(table -> !schemaName.isPresent() || table.getSchemaName().equals(schemaName.get()))
                .map(BlackHoleTableHandle::toSchemaTableName)
                .collect(toList());
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        BlackHoleTableHandle blackHoleTableHandle = (BlackHoleTableHandle) tableHandle;
        return blackHoleTableHandle.getColumnHandles().stream()
                .collect(toMap(BlackHoleColumnHandle::getName, column -> column));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        BlackHoleColumnHandle blackHoleColumnHandle = (BlackHoleColumnHandle) columnHandle;
        return blackHoleColumnHandle.toColumnMetadata();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        return tables.values().stream()
                .filter(table -> prefix.matches(table.toSchemaTableName()))
                .collect(toMap(BlackHoleTableHandle::toSchemaTableName, handle -> handle.toTableMetadata().getColumns()));
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint constraint)
    {
        BlackHoleTableHandle table = (BlackHoleTableHandle) tableHandle;
        TableStatistics.Builder tableStats = TableStatistics.builder();

        double rows = (double) table.getSplitCount() * table.getPagesPerSplit() * table.getRowsPerPage();
        tableStats.setRowCount(Estimate.of(rows));

        for (BlackHoleColumnHandle column : table.getColumnHandles()) {
            ColumnStatistics.Builder stats = ColumnStatistics.builder()
                    .setDistinctValuesCount(Estimate.of(1))
                    .setNullsFraction(Estimate.of(0));
            if (isNumericType(column.getColumnType())) {
                stats.setRange(new DoubleRange(0, 0));
            }
            tableStats.setColumnStatistics(column, stats.build());
        }

        return tableStats.build();
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        BlackHoleTableHandle blackHoleTableHandle = (BlackHoleTableHandle) tableHandle;
        tables.remove(blackHoleTableHandle.toSchemaTableName());
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        BlackHoleTableHandle oldTableHandle = (BlackHoleTableHandle) tableHandle;
        BlackHoleTableHandle newTableHandle = new BlackHoleTableHandle(
                oldTableHandle.getSchemaName(),
                newTableName.getTableName(),
                oldTableHandle.getColumnHandles(),
                oldTableHandle.getSplitCount(),
                oldTableHandle.getPagesPerSplit(),
                oldTableHandle.getRowsPerPage(),
                oldTableHandle.getFieldsLength(),
                oldTableHandle.getPageProcessingDelay());
        tables.remove(oldTableHandle.toSchemaTableName());
        tables.put(newTableName, newTableHandle);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        ConnectorOutputTableHandle outputTableHandle = beginCreateTable(session, tableMetadata, Optional.empty());
        finishCreateTable(session, outputTableHandle, ImmutableList.of(), ImmutableList.of());
    }

    @Override
    public Optional<ConnectorNewTableLayout> getNewTableLayout(ConnectorSession connectorSession, ConnectorTableMetadata tableMetadata)
    {
        List<String> distributeColumns = (List<String>) tableMetadata.getProperties().get(DISTRIBUTED_ON);
        if (distributeColumns.isEmpty()) {
            return Optional.empty();
        }

        Set<String> undefinedColumns = Sets.difference(
                ImmutableSet.copyOf(distributeColumns),
                tableMetadata.getColumns().stream()
                        .map(ColumnMetadata::getName)
                        .collect(toSet()));
        if (!undefinedColumns.isEmpty()) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, "Distribute columns not defined on table: " + undefinedColumns);
        }

        return Optional.of(new ConnectorNewTableLayout(BlackHolePartitioningHandle.INSTANCE, distributeColumns));
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        checkSchemaExists(tableMetadata.getTable().getSchemaName());
        int splitCount = (Integer) tableMetadata.getProperties().get(SPLIT_COUNT_PROPERTY);
        int pagesPerSplit = (Integer) tableMetadata.getProperties().get(PAGES_PER_SPLIT_PROPERTY);
        int rowsPerPage = (Integer) tableMetadata.getProperties().get(ROWS_PER_PAGE_PROPERTY);
        int fieldsLength = (Integer) tableMetadata.getProperties().get(FIELD_LENGTH_PROPERTY);

        if (splitCount < 0) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, SPLIT_COUNT_PROPERTY + " property is negative");
        }
        if (pagesPerSplit < 0) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, PAGES_PER_SPLIT_PROPERTY + " property is negative");
        }
        if (rowsPerPage < 0) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, ROWS_PER_PAGE_PROPERTY + " property is negative");
        }

        if (((splitCount > 0) || (pagesPerSplit > 0) || (rowsPerPage > 0)) &&
                ((splitCount == 0) || (pagesPerSplit == 0) || (rowsPerPage == 0))) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, format("All properties [%s, %s, %s] must be set if any are set",
                    SPLIT_COUNT_PROPERTY, PAGES_PER_SPLIT_PROPERTY, ROWS_PER_PAGE_PROPERTY));
        }

        Duration pageProcessingDelay = (Duration) tableMetadata.getProperties().get(PAGE_PROCESSING_DELAY);

        BlackHoleTableHandle handle = new BlackHoleTableHandle(
                tableMetadata,
                splitCount,
                pagesPerSplit,
                rowsPerPage,
                fieldsLength,
                pageProcessingDelay);
        return new BlackHoleOutputTableHandle(handle, pageProcessingDelay);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        BlackHoleOutputTableHandle blackHoleOutputTableHandle = (BlackHoleOutputTableHandle) tableHandle;
        BlackHoleTableHandle table = blackHoleOutputTableHandle.getTable();
        tables.put(table.toSchemaTableName(), table);
        return Optional.empty();
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        BlackHoleTableHandle handle = (BlackHoleTableHandle) tableHandle;
        return new BlackHoleInsertTableHandle(handle.getPageProcessingDelay());
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

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

    private void checkSchemaExists(String schemaName)
    {
        if (!schemas.contains(schemaName)) {
            throw new SchemaNotFoundException(schemaName);
        }
    }
}
