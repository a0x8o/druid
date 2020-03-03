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
package io.prestosql.plugin.phoenix;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.prestosql.plugin.jdbc.JdbcTableHandle;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorTableLayoutHandle;
import io.prestosql.spi.predicate.TupleDomain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class PhoenixTableLayoutHandle
        implements ConnectorTableLayoutHandle
{
    private final JdbcTableHandle table;
    private final TupleDomain<ColumnHandle> tupleDomain;
    private final Optional<Set<ColumnHandle>> desiredColumns;

    @JsonCreator
    public PhoenixTableLayoutHandle(
            @JsonProperty("table") JdbcTableHandle table,
            @JsonProperty("tupleDomain") TupleDomain<ColumnHandle> tupleDomain,
            @JsonProperty("desiredColumns") Optional<Set<ColumnHandle>> desiredColumns)
    {
        this.table = table;
        this.tupleDomain = tupleDomain;
        this.desiredColumns = desiredColumns;
    }

    @JsonProperty
    public JdbcTableHandle getTable()
    {
        return table;
    }

    @JsonProperty
    public TupleDomain<ColumnHandle> getTupleDomain()
    {
        return tupleDomain;
    }

    @JsonProperty
    public Optional<Set<ColumnHandle>> getDesiredColumns()
    {
        return desiredColumns;
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
        if (!super.equals(o)) {
            return false;
        }
        PhoenixTableLayoutHandle that = (PhoenixTableLayoutHandle) o;
        return Objects.equals(table, that.table) &&
                Objects.equals(tupleDomain, that.tupleDomain) &&
                Objects.equals(desiredColumns, that.desiredColumns);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(table, tupleDomain, desiredColumns);
    }
}
