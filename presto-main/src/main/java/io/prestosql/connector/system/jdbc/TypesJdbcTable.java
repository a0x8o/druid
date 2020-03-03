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
package io.prestosql.connector.system.jdbc;

import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.InMemoryRecordSet;
import io.prestosql.spi.connector.InMemoryRecordSet.Builder;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.ParametricType;
import io.prestosql.spi.type.Type;

import javax.inject.Inject;

import java.sql.DatabaseMetaData;
import java.sql.Types;

import static io.prestosql.connector.system.jdbc.ColumnJdbcTable.columnSize;
import static io.prestosql.connector.system.jdbc.ColumnJdbcTable.jdbcDataType;
import static io.prestosql.connector.system.jdbc.ColumnJdbcTable.numPrecRadix;
import static io.prestosql.metadata.MetadataUtil.TableMetadataBuilder.tableMetadataBuilder;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static java.util.Objects.requireNonNull;

public class TypesJdbcTable
        extends JdbcTable
{
    public static final SchemaTableName NAME = new SchemaTableName("jdbc", "types");

    public static final ConnectorTableMetadata METADATA = tableMetadataBuilder(NAME)
            .column("type_name", createUnboundedVarcharType())
            .column("data_type", BIGINT)
            .column("precision", BIGINT)
            .column("literal_prefix", createUnboundedVarcharType())
            .column("literal_suffix", createUnboundedVarcharType())
            .column("create_params", createUnboundedVarcharType())
            .column("nullable", BIGINT)
            .column("case_sensitive", BOOLEAN)
            .column("searchable", BIGINT)
            .column("unsigned_attribute", BOOLEAN)
            .column("fixed_prec_scale", BOOLEAN)
            .column("auto_increment", BOOLEAN)
            .column("local_type_name", createUnboundedVarcharType())
            .column("minimum_scale", BIGINT)
            .column("maximum_scale", BIGINT)
            .column("sql_data_type", BIGINT)
            .column("sql_datetime_sub", BIGINT)
            .column("num_prec_radix", BIGINT)
            .build();

    private final Metadata metadata;

    @Inject
    public TypesJdbcTable(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public ConnectorTableMetadata getTableMetadata()
    {
        return METADATA;
    }

    @Override
    public RecordCursor cursor(ConnectorTransactionHandle transactionHandle, ConnectorSession connectorSession, TupleDomain<Integer> constraint)
    {
        Builder table = InMemoryRecordSet.builder(METADATA);
        for (Type type : metadata.getTypes()) {
            addTypeRow(table, type);
        }
        for (ParametricType type : metadata.getParametricTypes()) {
            addTypeRow(table, type);
        }
        return table.build().cursor();
    }

    private static void addTypeRow(Builder builder, Type type)
    {
        builder.addRow(
                type.getDisplayName(),
                jdbcDataType(type),
                columnSize(type),
                null,
                null,
                null,
                DatabaseMetaData.typeNullable,
                false,
                type.isComparable() ? DatabaseMetaData.typeSearchable : DatabaseMetaData.typePredNone,
                null,
                false,
                null,
                null,
                0,
                0,
                null,
                null,
                numPrecRadix(type));
    }

    private static void addTypeRow(Builder builder, ParametricType type)
    {
        String typeName = type.getName();
        builder.addRow(
                typeName,
                typeName.equalsIgnoreCase("array") ? Types.ARRAY : Types.JAVA_OBJECT,
                null,
                null,
                null,
                null,
                DatabaseMetaData.typeNullable,
                false,
                DatabaseMetaData.typePredNone,
                null,
                false,
                null,
                null,
                0,
                0,
                null,
                null,
                null);
    }
}
