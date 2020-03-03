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
package io.prestosql.plugin.hive.metastore;

import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.spi.connector.SchemaTableName;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HivePageSinkMetadataProvider
{
    private final HiveIdentity identity;
    private final HiveMetastore delegate;
    private final SchemaTableName schemaTableName;
    private final Optional<Table> table;
    private final Map<List<String>, Optional<Partition>> modifiedPartitions;

    public HivePageSinkMetadataProvider(HivePageSinkMetadata pageSinkMetadata, HiveMetastore delegate, HiveIdentity identity)
    {
        requireNonNull(pageSinkMetadata, "pageSinkMetadata is null");
        this.delegate = delegate;
        this.identity = requireNonNull(identity, "identity is null");
        this.schemaTableName = pageSinkMetadata.getSchemaTableName();
        this.table = pageSinkMetadata.getTable();
        this.modifiedPartitions = pageSinkMetadata.getModifiedPartitions();
    }

    public Optional<Table> getTable()
    {
        return table;
    }

    public Optional<Partition> getPartition(List<String> partitionValues)
    {
        if (!table.isPresent() || table.get().getPartitionColumns().isEmpty()) {
            throw new IllegalArgumentException(
                    format("Unexpected call to getPartition. Table name: %s", schemaTableName));
        }
        Optional<Partition> modifiedPartition = modifiedPartitions.get(partitionValues);
        if (modifiedPartition == null) {
            return delegate.getPartition(identity, schemaTableName.getSchemaName(), schemaTableName.getTableName(), partitionValues);
        }
        else {
            return modifiedPartition;
        }
    }
}
