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

package io.prestosql.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.RowBlock;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.type.NamedTypeSignature;
import io.prestosql.spi.type.RowFieldName;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.testing.TestingConnectorSession;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.orc.OrcTester.HIVE_STORAGE_TIME_ZONE;
import static io.prestosql.orc.OrcTester.READER_OPTIONS;
import static io.prestosql.orc.OrcWriteValidation.OrcWriteValidationMode.BOTH;
import static io.prestosql.orc.TestingOrcPredicate.ORC_ROW_GROUP_SIZE;
import static io.prestosql.orc.TestingOrcPredicate.ORC_STRIPE_SIZE;
import static io.prestosql.orc.metadata.CompressionKind.NONE;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

@Test(singleThreaded = true)
public class TestStructStreamReader
{
    private static final Metadata METADATA = createTestMetadataManager();

    private static final Type TEST_DATA_TYPE = VARCHAR;

    private static final String STRUCT_COL_NAME = "struct_col";

    private static final ConnectorSession SESSION = new TestingConnectorSession(ImmutableList.of());

    private TempFile tempFile;

    @BeforeMethod
    public void setUp()
    {
        tempFile = new TempFile();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws IOException
    {
        tempFile.close();
    }

    /**
     * Reader and writer have the same fields. Checks that fields are read in correctly
     */
    @Test
    public void testValuesAreReadInCorrectly()
            throws IOException
    {
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerData = new ArrayList<>(Arrays.asList("field_a_value", "field_b_value", "field_c_value"));
        Type readerType = getType(readerFields);
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        RowBlock readBlock = read(tempFile, readerType);
        List<?> actual = (List<?>) readerType.getObjectValue(SESSION, readBlock, 0);

        assertEquals(actual.size(), readerFields.size());
        assertEquals(actual.get(0), "field_a_value");
        assertEquals(actual.get(1), "field_b_value");
        assertEquals(actual.get(2), "field_c_value");
    }

    /**
     * The writer has fields with upper case characters, reader has same names downcased.
     */
    @Test
    public void testReaderLowerCasesFieldNamesFromStream()
            throws IOException
    {
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_A", "field_B", "field_C"));
        List<String> writerData = new ArrayList<>(Arrays.asList("fieldAValue", "fieldBValue", "fieldCValue"));
        Type readerType = getType(readerFields);
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        RowBlock readBlock = read(tempFile, readerType);
        List<?> actual = (List<?>) readerType.getObjectValue(SESSION, readBlock, 0);

        assertEquals(actual.size(), readerFields.size());
        assertEquals(actual.get(0), "fieldAValue");
        assertEquals(actual.get(1), "fieldBValue");
        assertEquals(actual.get(2), "fieldCValue");
    }

    /**
     * Reader has fields with upper case characters, writer has same names downcased.
     */
    @Test
    public void testReaderLowerCasesFieldNamesFromType()
            throws IOException
    {
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_A", "field_B", "field_C"));
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerData = new ArrayList<>(Arrays.asList("fieldAValue", "fieldBValue", "fieldCValue"));
        Type readerType = getType(readerFields);
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        RowBlock readBlock = read(tempFile, readerType);
        List<?> actual = (List<?>) readerType.getObjectValue(SESSION, readBlock, 0);

        assertEquals(actual.size(), readerFields.size());
        assertEquals(actual.get(0), "fieldAValue");
        assertEquals(actual.get(1), "fieldBValue");
        assertEquals(actual.get(2), "fieldCValue");
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp =
            "ROW type does not have field names declared: row\\(varchar,varchar,varchar\\)")
    public void testThrowsExceptionWhenFieldNameMissing()
            throws IOException
    {
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerData = new ArrayList<>(Arrays.asList("field_a_value", "field_b_value", "field_c_value"));
        Type readerType = getTypeNullName(readerFields.size());
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        read(tempFile, readerType);
    }

    /**
     * The reader has a field that is missing from the ORC file
     */
    @Test
    public void testExtraFieldsInReader()
            throws IOException
    {
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));

        // field_b is missing
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_a", "field_c"));
        List<String> writerData = new ArrayList<>(Arrays.asList("field_a_value", "field_c_value"));
        Type readerType = getType(readerFields);
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        RowBlock readBlock = read(tempFile, readerType);
        List<?> actual = (List<?>) readerType.getObjectValue(SESSION, readBlock, 0);

        assertEquals(actual.size(), readerFields.size());
        assertEquals(actual.get(0), "field_a_value");
        assertNull(actual.get(1));
        assertEquals(actual.get(2), "field_c_value");
    }

    /**
     * The ORC file has a field that is missing from the reader
     */
    @Test
    public void testExtraFieldsInWriter()
            throws IOException
    {
        // field_b is missing
        List<String> readerFields = new ArrayList<>(Arrays.asList("field_a", "field_c"));
        List<String> writerFields = new ArrayList<>(Arrays.asList("field_a", "field_b", "field_c"));
        List<String> writerData = new ArrayList<>(Arrays.asList("field_a_value", "field_b_value", "field_c_value"));
        Type readerType = getType(readerFields);
        Type writerType = getType(writerFields);

        write(tempFile, writerType, writerData);
        RowBlock readBlock = read(tempFile, readerType);
        List<?> actual = (List<?>) readerType.getObjectValue(SESSION, readBlock, 0);

        assertEquals(actual.size(), readerFields.size());
        assertEquals(actual.get(0), "field_a_value");
        assertEquals(actual.get(1), "field_c_value");
    }

    private void write(TempFile tempFile, Type writerType, List<String> data)
            throws IOException
    {
        OrcWriter writer = new OrcWriter(
                new OutputStreamOrcDataSink(new FileOutputStream(tempFile.getFile())),
                ImmutableList.of(STRUCT_COL_NAME),
                ImmutableList.of(writerType),
                NONE,
                new OrcWriterOptions()
                        .withStripeMinSize(new DataSize(0, MEGABYTE))
                        .withStripeMaxSize(new DataSize(32, MEGABYTE))
                        .withStripeMaxRowCount(ORC_STRIPE_SIZE)
                        .withRowGroupMaxRowCount(ORC_ROW_GROUP_SIZE)
                        .withDictionaryMaxMemory(new DataSize(32, MEGABYTE)),
                false,
                ImmutableMap.of(),
                HIVE_STORAGE_TIME_ZONE,
                true,
                BOTH,
                new OrcWriterStats());

        // write down some data with unsorted streams
        Block[] fieldBlocks = new Block[data.size()];

        int entries = 10;
        boolean[] rowIsNull = new boolean[entries];
        Arrays.fill(rowIsNull, false);

        BlockBuilder blockBuilder = TEST_DATA_TYPE.createBlockBuilder(null, entries);
        for (int i = 0; i < data.size(); i++) {
            byte[] bytes = data.get(i).getBytes();
            for (int j = 0; j < entries; j++) {
                blockBuilder.writeBytes(Slices.wrappedBuffer(bytes), 0, bytes.length);
                blockBuilder.closeEntry();
            }
            fieldBlocks[i] = blockBuilder.build();
            blockBuilder = blockBuilder.newBlockBuilderLike(null);
        }
        Block rowBlock = RowBlock.fromFieldBlocks(rowIsNull.length, Optional.of(rowIsNull), fieldBlocks);
        writer.write(new Page(rowBlock));
        writer.close();
    }

    private RowBlock read(TempFile tempFile, Type readerType)
            throws IOException
    {
        OrcDataSource orcDataSource = new FileOrcDataSource(tempFile.getFile(), READER_OPTIONS);
        OrcReader orcReader = new OrcReader(orcDataSource, READER_OPTIONS);

        Map<Integer, Type> includedColumns = new HashMap<>();
        includedColumns.put(0, readerType);

        OrcRecordReader recordReader = orcReader.createRecordReader(includedColumns, OrcPredicate.TRUE, UTC, newSimpleAggregatedMemoryContext(), OrcReader.INITIAL_BATCH_SIZE);

        recordReader.nextBatch();
        RowBlock block = (RowBlock) recordReader.readBlock(0);
        recordReader.close();
        return block;
    }

    private Type getType(List<String> fieldNames)
    {
        ImmutableList.Builder<TypeSignatureParameter> typeSignatureParameters = ImmutableList.builder();
        for (String fieldName : fieldNames) {
            typeSignatureParameters.add(TypeSignatureParameter.namedTypeParameter(new NamedTypeSignature(Optional.of(new RowFieldName(fieldName, false)), TEST_DATA_TYPE.getTypeSignature())));
        }
        return METADATA.getParameterizedType(StandardTypes.ROW, typeSignatureParameters.build());
    }

    private Type getTypeNullName(int numFields)
    {
        ImmutableList.Builder<TypeSignatureParameter> typeSignatureParameters = ImmutableList.builder();

        for (int i = 0; i < numFields; i++) {
            typeSignatureParameters.add(TypeSignatureParameter.namedTypeParameter(new NamedTypeSignature(Optional.empty(), TEST_DATA_TYPE.getTypeSignature())));
        }
        return METADATA.getParameterizedType(StandardTypes.ROW, typeSignatureParameters.build());
    }
}
