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
package io.prestosql.plugin.jdbc;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.type.Type;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_NON_TRANSIENT_ERROR;
import static java.lang.String.format;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class JdbcPageSink
        implements ConnectorPageSink
{
    private final Connection connection;
    private final PreparedStatement statement;

    private final List<Type> columnTypes;
    private final List<WriteFunction> columnWriters;
    private final List<WriteNullFunction> nullWriters;
    private int batchSize;

    public JdbcPageSink(ConnectorSession session, JdbcOutputTableHandle handle, JdbcClient jdbcClient)
    {
        try {
            connection = jdbcClient.getConnection(JdbcIdentity.from(session), handle);
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }

        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(jdbcClient.buildInsertSql(handle));
        }
        catch (SQLException e) {
            closeWithSuppression(connection, e);
            throw new PrestoException(JDBC_ERROR, e);
        }

        columnTypes = handle.getColumnTypes();

        if (!handle.getJdbcColumnTypes().isPresent()) {
            List<WriteMapping> writeMappings = columnTypes.stream()
                    .map(type -> {
                        WriteMapping writeMapping = jdbcClient.toWriteMapping(session, type);
                        WriteFunction writeFunction = writeMapping.getWriteFunction();
                        verify(
                                type.getJavaType() == writeFunction.getJavaType(),
                                "Presto type %s is not compatible with write function %s accepting %s",
                                type,
                                writeFunction,
                                writeFunction.getJavaType());
                        return writeMapping;
                    })
                    .collect(toImmutableList());

            columnWriters = writeMappings.stream()
                    .map(WriteMapping::getWriteFunction)
                    .collect(toImmutableList());

            nullWriters = writeMappings.stream()
                    .map(WriteMapping::getWriteNullFunction)
                    .collect(toImmutableList());
        }
        else {
            List<ColumnMapping> columnMappings = handle.getJdbcColumnTypes().get().stream()
                    .map(typeHandle -> {
                        Optional<ColumnMapping> columnMapping = jdbcClient.toPrestoType(session, connection, typeHandle);
                        checkState(columnMapping.isPresent(), "missing column mapping");
                        return columnMapping.get();
                    })
                    .collect(toImmutableList());

            columnWriters = columnMappings.stream()
                    .map(ColumnMapping::getWriteFunction)
                    .collect(toImmutableList());

            nullWriters = columnMappings.stream()
                    .map(ColumnMapping::getWriteNullFunction)
                    .collect(toImmutableList());
        }
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        try {
            for (int position = 0; position < page.getPositionCount(); position++) {
                for (int channel = 0; channel < page.getChannelCount(); channel++) {
                    appendColumn(page, position, channel);
                }

                statement.addBatch();
                batchSize++;

                if (batchSize >= 1000) {
                    statement.executeBatch();
                    connection.commit();
                    connection.setAutoCommit(false);
                    batchSize = 0;
                }
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
        return NOT_BLOCKED;
    }

    private void appendColumn(Page page, int position, int channel)
            throws SQLException
    {
        Block block = page.getBlock(channel);
        int parameterIndex = channel + 1;

        if (block.isNull(position)) {
            nullWriters.get(channel).setNull(statement, parameterIndex);
            return;
        }

        Type type = columnTypes.get(channel);
        Class<?> javaType = type.getJavaType();
        WriteFunction writeFunction = columnWriters.get(channel);
        if (javaType == boolean.class) {
            ((BooleanWriteFunction) writeFunction).set(statement, parameterIndex, type.getBoolean(block, position));
        }
        else if (javaType == long.class) {
            ((LongWriteFunction) writeFunction).set(statement, parameterIndex, type.getLong(block, position));
        }
        else if (javaType == double.class) {
            ((DoubleWriteFunction) writeFunction).set(statement, parameterIndex, type.getDouble(block, position));
        }
        else if (javaType == Slice.class) {
            ((SliceWriteFunction) writeFunction).set(statement, parameterIndex, type.getSlice(block, position));
        }
        else if (javaType == Block.class) {
            ((BlockWriteFunction) writeFunction).set(statement, parameterIndex, (Block) type.getObject(block, position));
        }
        else {
            throw new VerifyException(format("Unexpected type %s with java type %s", type, javaType.getName()));
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        // commit and close
        try (Connection connection = this.connection;
                PreparedStatement statement = this.statement) {
            if (batchSize > 0) {
                statement.executeBatch();
                connection.commit();
            }
        }
        catch (SQLNonTransientException e) {
            throw new PrestoException(JDBC_NON_TRANSIENT_ERROR, e);
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
        // the committer does not need any additional info
        return completedFuture(ImmutableList.of());
    }

    @SuppressWarnings("unused")
    @Override
    public void abort()
    {
        // rollback and close
        try (Connection connection = this.connection;
                PreparedStatement statement = this.statement) {
            // skip rollback if implicitly closed due to an error
            if (!connection.isClosed()) {
                connection.rollback();
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @SuppressWarnings("ObjectEquality")
    private static void closeWithSuppression(Connection connection, Throwable throwable)
    {
        try {
            connection.close();
        }
        catch (Throwable t) {
            // Self-suppression not permitted
            if (throwable != t) {
                throwable.addSuppressed(t);
            }
        }
    }
}
