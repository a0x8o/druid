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
package io.prestosql.tests.tpch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import io.prestosql.plugin.tpch.TpchMetadata;
import io.prestosql.plugin.tpch.TpchTableHandle;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorResolvedIndex;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.predicate.TupleDomain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static io.prestosql.tests.tpch.TpchIndexProvider.handleToNames;
import static java.util.Objects.requireNonNull;

public class TpchIndexMetadata
        extends TpchMetadata
{
    private final TpchIndexedData indexedData;

    public TpchIndexMetadata(TpchIndexedData indexedData)
    {
        this.indexedData = requireNonNull(indexedData, "indexedData is null");
    }

    @Override
    public Optional<ConnectorResolvedIndex> resolveIndex(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            Set<ColumnHandle> indexableColumns,
            Set<ColumnHandle> outputColumns,
            TupleDomain<ColumnHandle> tupleDomain)
    {
        TpchTableHandle tpchTableHandle = (TpchTableHandle) tableHandle;

        // Keep the fixed values that don't overlap with the indexableColumns
        // Note: technically we could more efficiently utilize the overlapped columns, but this way is simpler for now

        Map<ColumnHandle, NullableValue> fixedValues = TupleDomain.extractFixedValues(tupleDomain).orElse(ImmutableMap.of())
                .entrySet().stream()
                .filter(entry -> !indexableColumns.contains(entry.getKey()))
                .filter(entry -> !entry.getValue().isNull()) // strip nulls since meaningless in index join lookups
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // determine all columns available for index lookup
        Set<String> lookupColumnNames = ImmutableSet.<String>builder()
                .addAll(handleToNames(ImmutableList.copyOf(indexableColumns)))
                .addAll(handleToNames(ImmutableList.copyOf(fixedValues.keySet())))
                .build();

        // do we have an index?
        if (!indexedData.getIndexedTable(tpchTableHandle.getTableName(), tpchTableHandle.getScaleFactor(), lookupColumnNames).isPresent()) {
            return Optional.empty();
        }

        TupleDomain<ColumnHandle> filteredTupleDomain = tupleDomain;
        if (!tupleDomain.isNone()) {
            filteredTupleDomain = TupleDomain.withColumnDomains(Maps.filterKeys(tupleDomain.getDomains().get(), not(in(fixedValues.keySet()))));
        }
        TpchIndexHandle indexHandle = new TpchIndexHandle(
                tpchTableHandle.getTableName(),
                tpchTableHandle.getScaleFactor(),
                lookupColumnNames,
                TupleDomain.fromFixedValues(fixedValues));
        return Optional.of(new ConnectorResolvedIndex(indexHandle, filteredTupleDomain));
    }
}
