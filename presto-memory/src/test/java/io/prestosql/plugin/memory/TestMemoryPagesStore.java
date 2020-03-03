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
package io.prestosql.plugin.memory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.DataSize;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.testing.TestingConnectorSession;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.OptionalDouble;
import java.util.OptionalLong;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestMemoryPagesStore
{
    public static final ConnectorSession SESSION = new TestingConnectorSession(ImmutableList.of());
    private static final int POSITIONS_PER_PAGE = 0;

    private MemoryPagesStore pagesStore;
    private MemoryPageSinkProvider pageSinkProvider;

    @BeforeMethod
    public void setUp()
    {
        pagesStore = new MemoryPagesStore(new MemoryConfig().setMaxDataPerNode(new DataSize(1, DataSize.Unit.MEGABYTE)));
        pageSinkProvider = new MemoryPageSinkProvider(pagesStore, HostAddress.fromString("localhost:8080"));
    }

    @Test
    public void testCreateEmptyTable()
    {
        createTable(0L, 0L);
        assertEquals(pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), 0, OptionalLong.empty(), OptionalDouble.empty()), ImmutableList.of());
    }

    @Test
    public void testInsertPage()
    {
        createTable(0L, 0L);
        insertToTable(0L, 0L);
        assertEquals(pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), POSITIONS_PER_PAGE, OptionalLong.empty(), OptionalDouble.empty()).size(), 1);
    }

    @Test
    public void testInsertPageWithoutCreate()
    {
        insertToTable(0L, 0L);
        assertEquals(pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), POSITIONS_PER_PAGE, OptionalLong.empty(), OptionalDouble.empty()).size(), 1);
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testReadFromUnknownTable()
    {
        pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), 0, OptionalLong.empty(), OptionalDouble.empty());
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testTryToReadFromEmptyTable()
    {
        createTable(0L, 0L);
        assertEquals(pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), 0, OptionalLong.empty(), OptionalDouble.empty()), ImmutableList.of());
        pagesStore.getPages(0L, 0, 1, ImmutableList.of(0), 42, OptionalLong.empty(), OptionalDouble.empty());
    }

    @Test
    public void testCleanUp()
    {
        createTable(0L, 0L);
        createTable(1L, 0L, 1L);
        createTable(2L, 0L, 1L, 2L);

        assertTrue(pagesStore.contains(0L));
        assertTrue(pagesStore.contains(1L));
        assertTrue(pagesStore.contains(2L));

        insertToTable(1L, 0L, 1L);

        assertTrue(pagesStore.contains(0L));
        assertTrue(pagesStore.contains(1L));
        assertTrue(pagesStore.contains(2L));

        insertToTable(2L, 0L, 2L);

        assertTrue(pagesStore.contains(0L));
        assertFalse(pagesStore.contains(1L));
        assertTrue(pagesStore.contains(2L));
    }

    @Test(expectedExceptions = PrestoException.class)
    public void testMemoryLimitExceeded()
    {
        createTable(0L, 0L);
        insertToTable(0L, createOneMegaBytePage(), 0L);
        insertToTable(0L, createOneMegaBytePage(), 0L);
    }

    private void insertToTable(long tableId, Long... activeTableIds)
    {
        insertToTable(tableId, createPage(), activeTableIds);
    }

    private void insertToTable(long tableId, Page page, Long... activeTableIds)
    {
        ConnectorPageSink pageSink = pageSinkProvider.createPageSink(
                MemoryTransactionHandle.INSTANCE,
                SESSION,
                createMemoryInsertTableHandle(tableId, activeTableIds));
        pageSink.appendPage(page);
        pageSink.finish();
    }

    private void createTable(long tableId, Long... activeTableIds)
    {
        ConnectorPageSink pageSink = pageSinkProvider.createPageSink(
                MemoryTransactionHandle.INSTANCE,
                SESSION,
                createMemoryOutputTableHandle(tableId, activeTableIds));
        pageSink.finish();
    }

    private static ConnectorOutputTableHandle createMemoryOutputTableHandle(long tableId, Long... activeTableIds)
    {
        return new MemoryOutputTableHandle(tableId, ImmutableSet.copyOf(activeTableIds));
    }

    private static ConnectorInsertTableHandle createMemoryInsertTableHandle(long tableId, Long[] activeTableIds)
    {
        return new MemoryInsertTableHandle(tableId, ImmutableSet.copyOf(activeTableIds));
    }

    private static Page createPage()
    {
        BlockBuilder blockBuilder = BIGINT.createFixedSizeBlockBuilder(POSITIONS_PER_PAGE);
        BIGINT.writeLong(blockBuilder, 42L);
        return new Page(0, blockBuilder.build());
    }

    private static Page createOneMegaBytePage()
    {
        BlockBuilder blockBuilder = BIGINT.createFixedSizeBlockBuilder(POSITIONS_PER_PAGE);
        while (blockBuilder.getRetainedSizeInBytes() < 1024 * 1024) {
            BIGINT.writeLong(blockBuilder, 42L);
        }
        return new Page(0, blockBuilder.build());
    }
}
