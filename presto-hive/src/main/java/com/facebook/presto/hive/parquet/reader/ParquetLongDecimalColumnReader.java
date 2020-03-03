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
package com.facebook.presto.hive.parquet.reader;

import com.facebook.presto.hive.parquet.RichColumnDescriptor;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.Type;
import parquet.io.api.Binary;

import java.math.BigInteger;

public class ParquetLongDecimalColumnReader
        extends ParquetPrimitiveColumnReader
{
    ParquetLongDecimalColumnReader(RichColumnDescriptor descriptor)
    {
        super(descriptor);
    }

    @Override
    protected void readValue(BlockBuilder blockBuilder, Type type)
    {
        if (definitionLevel == columnDescriptor.getMaxDefinitionLevel()) {
            Binary value = valuesReader.readBytes();
            type.writeSlice(blockBuilder, Decimals.encodeUnscaledValue(new BigInteger(value.getBytes())));
        }
        else if (isValueNull()) {
            blockBuilder.appendNull();
        }
    }

    @Override
    protected void skipValue()
    {
        if (definitionLevel == columnDescriptor.getMaxDefinitionLevel()) {
            valuesReader.readBytes();
        }
    }
}
