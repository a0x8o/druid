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
package io.prestosql.plugin.tpch;

import com.google.common.collect.ImmutableList;
import io.airlift.tpch.TpchColumn;
import io.airlift.tpch.TpchColumnType;
import io.airlift.tpch.TpchEntity;
import io.airlift.tpch.TpchTable;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorRecordSetProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.RecordSet;
import io.prestosql.spi.predicate.TupleDomain;

import java.util.List;

import static io.airlift.tpch.TpchColumnTypes.IDENTIFIER;
import static io.prestosql.plugin.tpch.TpchRecordSet.createTpchRecordSet;

public class TpchRecordSetProvider
        implements ConnectorRecordSetProvider
{
    @Override
    public RecordSet getRecordSet(ConnectorTransactionHandle transaction, ConnectorSession session, ConnectorSplit split, ConnectorTableHandle table, List<? extends ColumnHandle> columns)
    {
        TpchSplit tpchSplit = (TpchSplit) split;
        TpchTableHandle tpchTable = (TpchTableHandle) table;

        return getRecordSet(
                TpchTable.getTable(tpchTable.getTableName()),
                columns,
                tpchTable.getScaleFactor(),
                tpchSplit.getPartNumber(),
                tpchSplit.getTotalParts(),
                tpchTable.getConstraint());
    }

    public <E extends TpchEntity> RecordSet getRecordSet(
            TpchTable<E> table,
            List<? extends ColumnHandle> columns,
            double scaleFactor,
            int partNumber,
            int totalParts,
            TupleDomain<ColumnHandle> predicate)
    {
        ImmutableList.Builder<TpchColumn<E>> builder = ImmutableList.builder();
        for (ColumnHandle column : columns) {
            String columnName = ((TpchColumnHandle) column).getColumnName();
            if (columnName.equalsIgnoreCase(TpchMetadata.ROW_NUMBER_COLUMN_NAME)) {
                builder.add(new RowNumberTpchColumn<E>());
            }
            else {
                builder.add(table.getColumn(columnName));
            }
        }

        return createTpchRecordSet(table, builder.build(), scaleFactor, partNumber + 1, totalParts, predicate);
    }

    private static class RowNumberTpchColumn<E extends TpchEntity>
            implements TpchColumn<E>
    {
        @Override
        public String getColumnName()
        {
            return TpchMetadata.ROW_NUMBER_COLUMN_NAME;
        }

        @Override
        public TpchColumnType getType()
        {
            return IDENTIFIER;
        }

        @Override
        public double getDouble(E entity)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getIdentifier(E entity)
        {
            return entity.getRowNumber();
        }

        @Override
        public int getInteger(E entity)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getString(E entity)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getDate(E entity)
        {
            throw new UnsupportedOperationException();
        }
    }
}
