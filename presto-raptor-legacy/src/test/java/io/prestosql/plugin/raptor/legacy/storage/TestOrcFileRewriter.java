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
package io.prestosql.plugin.raptor.legacy.storage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.prestosql.orc.OrcDataSource;
import io.prestosql.orc.OrcRecordReader;
import io.prestosql.plugin.raptor.legacy.storage.OrcFileRewriter.OrcFileInfo;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeId;
import io.prestosql.spi.type.TypeSignatureParameter;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.BitSet;
import java.util.List;

import static com.google.common.io.Files.createTempDir;
import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.raptor.legacy.storage.OrcTestingUtil.createReader;
import static io.prestosql.plugin.raptor.legacy.storage.OrcTestingUtil.fileOrcDataSource;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.tests.StructuralTestUtil.arrayBlockOf;
import static io.prestosql.tests.StructuralTestUtil.arrayBlocksEqual;
import static io.prestosql.tests.StructuralTestUtil.mapBlockOf;
import static io.prestosql.tests.StructuralTestUtil.mapBlocksEqual;
import static java.nio.file.Files.readAllBytes;
import static java.util.UUID.randomUUID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestOrcFileRewriter
{
    private static final JsonCodec<OrcFileMetadata> METADATA_CODEC = jsonCodec(OrcFileMetadata.class);

    private File temporary;

    @BeforeClass
    public void setup()
    {
        temporary = createTempDir();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        deleteRecursively(temporary.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testRewrite()
            throws Exception
    {
        ArrayType arrayType = new ArrayType(BIGINT);
        ArrayType arrayOfArrayType = new ArrayType(arrayType);
        Type mapType = createTestMetadataManager().getParameterizedType(StandardTypes.MAP, ImmutableList.of(
                TypeSignatureParameter.typeParameter(createVarcharType(5).getTypeSignature()),
                TypeSignatureParameter.typeParameter(BOOLEAN.getTypeSignature())));
        List<Long> columnIds = ImmutableList.of(3L, 7L, 9L, 10L, 11L, 12L);
        DecimalType decimalType = DecimalType.createDecimalType(4, 4);

        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(20), arrayType, mapType, arrayOfArrayType, decimalType);

        File file = new File(temporary, randomUUID().toString());
        try (OrcFileWriter writer = new OrcFileWriter(columnIds, columnTypes, file)) {
            List<Page> pages = rowPagesBuilder(columnTypes)
                    .row(123L, "hello", arrayBlockOf(BIGINT, 1, 2), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5)), new BigDecimal("2.3"))
                    .row(777L, "sky", arrayBlockOf(BIGINT, 3, 4), mapBlockOf(createVarcharType(5), BOOLEAN, "k2", false), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 6)), new BigDecimal("2.3"))
                    .row(456L, "bye", arrayBlockOf(BIGINT, 5, 6), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7)), new BigDecimal("2.3"))
                    .row(888L, "world", arrayBlockOf(BIGINT, 7, 8), mapBlockOf(createVarcharType(5), BOOLEAN, "k4", true), arrayBlockOf(arrayType, null, arrayBlockOf(BIGINT, 8), null), new BigDecimal("2.3"))
                    .row(999L, "done", arrayBlockOf(BIGINT, 9, 10), mapBlockOf(createVarcharType(5), BOOLEAN, "k5", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 9, 10)), new BigDecimal("2.3"))
                    .build();
            writer.appendPages(pages);
        }

        try (OrcDataSource dataSource = fileOrcDataSource(file)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 5);
            assertEquals(reader.getFileRowCount(), 5);
            assertEquals(reader.getSplitLength(), file.length());

            assertEquals(reader.nextBatch(), 5);

            Block column0 = reader.readBlock(0);
            assertEquals(column0.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column0.isNull(i), false);
            }
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 777L);
            assertEquals(BIGINT.getLong(column0, 2), 456L);
            assertEquals(BIGINT.getLong(column0, 3), 888L);
            assertEquals(BIGINT.getLong(column0, 4), 999L);

            Block column1 = reader.readBlock(1);
            assertEquals(column1.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column1.isNull(i), false);
            }
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(20).getSlice(column1, 1), utf8Slice("sky"));
            assertEquals(createVarcharType(20).getSlice(column1, 2), utf8Slice("bye"));
            assertEquals(createVarcharType(20).getSlice(column1, 3), utf8Slice("world"));
            assertEquals(createVarcharType(20).getSlice(column1, 4), utf8Slice("done"));

            Block column2 = reader.readBlock(2);
            assertEquals(column2.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column2.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 0), arrayBlockOf(BIGINT, 1, 2)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 1), arrayBlockOf(BIGINT, 3, 4)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 2), arrayBlockOf(BIGINT, 5, 6)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 3), arrayBlockOf(BIGINT, 7, 8)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 4), arrayBlockOf(BIGINT, 9, 10)));

            Block column3 = reader.readBlock(3);
            assertEquals(column3.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column3.isNull(i), false);
            }
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 0), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 1), mapBlockOf(createVarcharType(5), BOOLEAN, "k2", false)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 2), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 3), mapBlockOf(createVarcharType(5), BOOLEAN, "k4", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 4), mapBlockOf(createVarcharType(5), BOOLEAN, "k5", true)));

            Block column4 = reader.readBlock(4);
            assertEquals(column4.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column4.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 0), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 1), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 6))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 2), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 3), arrayBlockOf(arrayType, null, arrayBlockOf(BIGINT, 8), null)));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 4), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 9, 10))));

            assertEquals(reader.nextBatch(), -1);

            OrcFileMetadata orcFileMetadata = METADATA_CODEC.fromJson(reader.getUserMetadata().get(OrcFileMetadata.KEY).getBytes());
            assertEquals(orcFileMetadata, new OrcFileMetadata(ImmutableMap.<Long, TypeId>builder()
                    .put(3L, BIGINT.getTypeId())
                    .put(7L, createVarcharType(20).getTypeId())
                    .put(9L, arrayType.getTypeId())
                    .put(10L, mapType.getTypeId())
                    .put(11L, arrayOfArrayType.getTypeId())
                    .put(12L, decimalType.getTypeId())
                    .build()));
        }

        BitSet rowsToDelete = new BitSet(5);
        rowsToDelete.set(1);
        rowsToDelete.set(3);
        rowsToDelete.set(4);

        File newFile = new File(temporary, randomUUID().toString());
        OrcFileInfo info = OrcFileRewriter.rewrite(file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 2);
        assertEquals(info.getUncompressedSize(), 94);

        try (OrcDataSource dataSource = fileOrcDataSource(newFile)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 2);
            assertEquals(reader.getFileRowCount(), 2);
            assertEquals(reader.getSplitLength(), newFile.length());

            assertEquals(reader.nextBatch(), 2);

            Block column0 = reader.readBlock(0);
            assertEquals(column0.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column0.isNull(i), false);
            }
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 456L);

            Block column1 = reader.readBlock(1);
            assertEquals(column1.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column1.isNull(i), false);
            }
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(20).getSlice(column1, 1), utf8Slice("bye"));

            Block column2 = reader.readBlock(2);
            assertEquals(column2.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column2.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 0), arrayBlockOf(BIGINT, 1, 2)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 1), arrayBlockOf(BIGINT, 5, 6)));

            Block column3 = reader.readBlock(3);
            assertEquals(column3.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column3.isNull(i), false);
            }
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 0), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 1), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true)));

            Block column4 = reader.readBlock(4);
            assertEquals(column4.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column4.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 0), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 1), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7))));

            assertEquals(reader.nextBatch(), -1);

            OrcFileMetadata orcFileMetadata = METADATA_CODEC.fromJson(reader.getUserMetadata().get(OrcFileMetadata.KEY).getBytes());
            assertEquals(orcFileMetadata, new OrcFileMetadata(ImmutableMap.<Long, TypeId>builder()
                    .put(3L, BIGINT.getTypeId())
                    .put(7L, createVarcharType(20).getTypeId())
                    .put(9L, arrayType.getTypeId())
                    .put(10L, mapType.getTypeId())
                    .put(11L, arrayOfArrayType.getTypeId())
                    .put(12L, decimalType.getTypeId())
                    .build()));
        }
    }

    @Test
    public void testRewriteWithoutMetadata()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(20));

        File file = new File(temporary, randomUUID().toString());
        try (OrcFileWriter writer = new OrcFileWriter(columnIds, columnTypes, file, false)) {
            List<Page> pages = rowPagesBuilder(columnTypes)
                    .row(123L, "hello")
                    .row(777L, "sky")
                    .build();
            writer.appendPages(pages);
        }

        try (OrcDataSource dataSource = fileOrcDataSource(file)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 2);
            assertEquals(reader.getFileRowCount(), 2);
            assertEquals(reader.getSplitLength(), file.length());

            assertEquals(reader.nextBatch(), 2);

            Block column0 = reader.readBlock(0);
            assertEquals(column0.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column0.isNull(i), false);
            }
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 777L);

            Block column1 = reader.readBlock(1);
            assertEquals(column1.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column1.isNull(i), false);
            }
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(20).getSlice(column1, 1), utf8Slice("sky"));

            assertFalse(reader.getUserMetadata().containsKey(OrcFileMetadata.KEY));
        }

        BitSet rowsToDelete = new BitSet(5);
        rowsToDelete.set(1);

        File newFile = new File(temporary, randomUUID().toString());
        OrcFileInfo info = OrcFileRewriter.rewrite(file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 1);
        assertEquals(info.getUncompressedSize(), 13);

        try (OrcDataSource dataSource = fileOrcDataSource(newFile)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 1);
            assertEquals(reader.getFileRowCount(), 1);
            assertEquals(reader.getSplitLength(), newFile.length());

            assertEquals(reader.nextBatch(), 1);

            Block column0 = reader.readBlock(0);
            assertEquals(column0.getPositionCount(), 1);
            assertEquals(column0.isNull(0), false);
            assertEquals(BIGINT.getLong(column0, 0), 123L);

            Block column1 = reader.readBlock(1);
            assertEquals(column1.getPositionCount(), 1);
            assertEquals(column1.isNull(0), false);
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));

            assertFalse(reader.getUserMetadata().containsKey(OrcFileMetadata.KEY));
        }
    }

    @Test
    public void testRewriteAllRowsDeleted()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(3L);
        List<Type> columnTypes = ImmutableList.of(BIGINT);

        File file = new File(temporary, randomUUID().toString());
        try (OrcFileWriter writer = new OrcFileWriter(columnIds, columnTypes, file)) {
            writer.appendPages(rowPagesBuilder(columnTypes).row(123L).row(456L).build());
        }

        BitSet rowsToDelete = new BitSet();
        rowsToDelete.set(0);
        rowsToDelete.set(1);

        File newFile = new File(temporary, randomUUID().toString());
        OrcFileInfo info = OrcFileRewriter.rewrite(file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 0);
        assertEquals(info.getUncompressedSize(), 0);

        assertFalse(newFile.exists());
    }

    @Test
    public void testRewriteNoRowsDeleted()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(3L);
        List<Type> columnTypes = ImmutableList.of(BIGINT);

        File file = new File(temporary, randomUUID().toString());
        try (OrcFileWriter writer = new OrcFileWriter(columnIds, columnTypes, file)) {
            writer.appendPages(rowPagesBuilder(columnTypes).row(123L).row(456L).build());
        }

        BitSet rowsToDelete = new BitSet();

        File newFile = new File(temporary, randomUUID().toString());
        OrcFileInfo info = OrcFileRewriter.rewrite(file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 2);
        assertEquals(info.getUncompressedSize(), 16);

        assertEquals(readAllBytes(newFile.toPath()), readAllBytes(file.toPath()));
    }

    @Test
    public void testUncompressedSize()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(1L, 2L, 3L, 4L, 5L);
        List<Type> columnTypes = ImmutableList.of(BOOLEAN, BIGINT, DOUBLE, createVarcharType(10), VARBINARY);

        File file = new File(temporary, randomUUID().toString());
        try (OrcFileWriter writer = new OrcFileWriter(columnIds, columnTypes, file)) {
            List<Page> pages = rowPagesBuilder(columnTypes)
                    .row(true, 123L, 98.7, "hello", utf8Slice("abc"))
                    .row(false, 456L, 65.4, "world", utf8Slice("xyz"))
                    .row(null, null, null, null, null)
                    .build();
            writer.appendPages(pages);
        }

        File newFile = new File(temporary, randomUUID().toString());
        OrcFileInfo info = OrcFileRewriter.rewrite(file, newFile, new BitSet());
        assertEquals(info.getRowCount(), 3);
        assertEquals(info.getUncompressedSize(), 55);
    }
}
