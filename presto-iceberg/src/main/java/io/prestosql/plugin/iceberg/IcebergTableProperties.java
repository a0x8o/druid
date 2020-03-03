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
package io.prestosql.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.ArrayType;
import org.apache.iceberg.FileFormat;

import javax.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.session.PropertyMetadata.enumProperty;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Locale.ENGLISH;

public class IcebergTableProperties
{
    public static final String FILE_FORMAT_PROPERTY = "format";
    public static final String PARTITIONING_PROPERTY = "partitioning";

    private final List<PropertyMetadata<?>> tableProperties;

    @Inject
    public IcebergTableProperties()
    {
        tableProperties = ImmutableList.<PropertyMetadata<?>>builder()
                .add(enumProperty(
                        FILE_FORMAT_PROPERTY,
                        "File format for the table",
                        FileFormat.class,
                        FileFormat.PARQUET,
                        false))
                .add(new PropertyMetadata<>(
                        PARTITIONING_PROPERTY,
                        "Partition transforms",
                        new ArrayType(VARCHAR),
                        List.class,
                        ImmutableList.of(),
                        false,
                        value -> ((Collection<?>) value).stream()
                                .map(name -> ((String) name).toLowerCase(ENGLISH))
                                .collect(toImmutableList()),
                        value -> value))
                .build();
    }

    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties;
    }

    public static FileFormat getFileFormat(Map<String, Object> tableProperties)
    {
        return (FileFormat) tableProperties.get(FILE_FORMAT_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getPartitioning(Map<String, Object> tableProperties)
    {
        List<String> partitioning = (List<String>) tableProperties.get(PARTITIONING_PROPERTY);
        return partitioning == null ? ImmutableList.of() : ImmutableList.copyOf(partitioning);
    }
}
