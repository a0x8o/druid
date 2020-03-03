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
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorPageSourceProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.DateType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.FixedWidthType;
import io.prestosql.spi.type.IntegerType;
import io.prestosql.spi.type.RealType;
import io.prestosql.spi.type.SmallintType;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TinyintType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarbinaryType;
import io.prestosql.spi.type.VarcharType;

import java.util.Arrays;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.type.Decimals.encodeScaledValue;
import static io.prestosql.spi.type.Decimals.isLongDecimal;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static java.math.BigDecimal.ZERO;
import static java.util.Objects.requireNonNull;

public final class BlackHolePageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final ListeningScheduledExecutorService executorService;

    public BlackHolePageSourceProvider(ListeningScheduledExecutorService executorService)
    {
        this.executorService = requireNonNull(executorService, "executorService is null");
    }

    @Override
    public ConnectorPageSource createPageSource(
            ConnectorTransactionHandle transactionHandle,
            ConnectorSession session,
            ConnectorSplit split,
            ConnectorTableHandle tableHandle,
            List<ColumnHandle> columns)
    {
        BlackHoleTableHandle table = (BlackHoleTableHandle) tableHandle;

        ImmutableList.Builder<Type> builder = ImmutableList.builder();

        for (ColumnHandle column : columns) {
            builder.add(((BlackHoleColumnHandle) column).getColumnType());
        }
        List<Type> types = builder.build();

        Page page = generateZeroPage(types, table.getRowsPerPage(), table.getFieldsLength());
        return new BlackHolePageSource(page, table.getPagesPerSplit(), executorService, table.getPageProcessingDelay());
    }

    private Page generateZeroPage(List<Type> types, int rowsCount, int fieldLength)
    {
        byte[] constantBytes = new byte[fieldLength];
        Arrays.fill(constantBytes, (byte) 42);
        Slice constantSlice = Slices.wrappedBuffer(constantBytes);

        Block[] blocks = new Block[types.size()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = createZeroBlock(types.get(i), rowsCount, constantSlice);
        }

        return new Page(rowsCount, blocks);
    }

    private Block createZeroBlock(Type type, int rowsCount, Slice constantSlice)
    {
        checkArgument(isSupportedType(type), "Unsupported type [%s]", type);

        Slice slice;
        // do not exceed varchar limit
        if (isVarcharType(type) && !((VarcharType) type).isUnbounded()) {
            slice = constantSlice.slice(0, Math.min(((VarcharType) type).getBoundedLength(), constantSlice.length()));
        }
        else if (isLongDecimal(type)) {
            slice = encodeScaledValue(ZERO);
        }
        else {
            slice = constantSlice;
        }

        BlockBuilder builder;
        if (type instanceof FixedWidthType) {
            builder = type.createBlockBuilder(null, rowsCount);
        }
        else {
            builder = type.createBlockBuilder(null, rowsCount, slice.length());
        }

        for (int i = 0; i < rowsCount; i++) {
            Class<?> javaType = type.getJavaType();
            if (javaType == boolean.class) {
                type.writeBoolean(builder, false);
            }
            else if (javaType == long.class) {
                type.writeLong(builder, 0);
            }
            else if (javaType == double.class) {
                type.writeDouble(builder, 0.0);
            }
            else if (javaType == Slice.class) {
                requireNonNull(slice, "slice is null");
                type.writeSlice(builder, slice, 0, slice.length());
            }
            else {
                throw new UnsupportedOperationException("Unknown javaType: " + javaType.getName());
            }
        }
        return builder.build();
    }

    private static boolean isSupportedType(Type type)
    {
        return isNumericType(type) ||
                type instanceof BooleanType ||
                type instanceof DateType ||
                type instanceof TimestampType ||
                type instanceof VarcharType ||
                type instanceof VarbinaryType;
    }

    public static boolean isNumericType(Type type)
    {
        return type instanceof TinyintType ||
                type instanceof SmallintType ||
                type instanceof IntegerType ||
                type instanceof BigintType ||
                type instanceof RealType ||
                type instanceof DoubleType ||
                type instanceof DecimalType;
    }
}
