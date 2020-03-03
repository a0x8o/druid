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
package com.facebook.presto.parquet.reader;

import com.facebook.presto.parquet.RichColumnDescriptor;
import com.facebook.presto.spi.type.DecimalType;

public final class DecimalColumnReaderFactory
{
    private DecimalColumnReaderFactory() {}

    public static PrimitiveColumnReader createReader(RichColumnDescriptor descriptor, int precision, int scale)
    {
        DecimalType decimalType = DecimalType.createDecimalType(precision, scale);
        if (decimalType.isShort()) {
            return new ShortDecimalColumnReader(descriptor);
        }
        else {
            return new LongDecimalColumnReader(descriptor);
        }
    }
}
