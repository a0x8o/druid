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
package io.prestosql.parquet.reader;

import io.prestosql.parquet.RichColumnDescriptor;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;

import static io.prestosql.parquet.ParquetTypeUtils.getShortDecimalValue;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;

public class ShortDecimalColumnReader
        extends PrimitiveColumnReader
{
    ShortDecimalColumnReader(RichColumnDescriptor descriptor)
    {
        super(descriptor);
    }

    @Override
    protected void readValue(BlockBuilder blockBuilder, Type type)
    {
        if (definitionLevel == columnDescriptor.getMaxDefinitionLevel()) {
            long decimalValue;
            // When decimals are encoded with primitive types Parquet stores unscaled values
            if (columnDescriptor.getType().equals(INT32)) {
                decimalValue = valuesReader.readInteger();
            }
            else if (columnDescriptor.getType().equals(INT64)) {
                decimalValue = valuesReader.readLong();
            }
            else {
                decimalValue = getShortDecimalValue(valuesReader.readBytes().getBytes());
            }
            type.writeLong(blockBuilder, decimalValue);
        }
        else if (isValueNull()) {
            blockBuilder.appendNull();
        }
    }

    @Override
    protected void skipValue()
    {
        if (definitionLevel == columnDescriptor.getMaxDefinitionLevel()) {
            if (columnDescriptor.getType().equals(INT32)) {
                valuesReader.readInteger();
            }
            else if (columnDescriptor.getType().equals(INT64)) {
                valuesReader.readLong();
            }
            else {
                valuesReader.readBytes();
            }
        }
    }
}
