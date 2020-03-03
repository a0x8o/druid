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
package io.prestosql.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.tests.AbstractTestIntegrationSmokeTest;
import org.bson.Document;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static io.airlift.tpch.TpchTable.ORDERS;
import static io.prestosql.plugin.mongodb.MongoQueryRunner.createMongoQueryRunner;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

@Test(singleThreaded = true)
public class TestMongoIntegrationSmokeTest
        extends AbstractTestIntegrationSmokeTest
{
    private MongoQueryRunner mongoQueryRunner;

    public TestMongoIntegrationSmokeTest()
    {
        super(() -> createMongoQueryRunner(ORDERS));
    }

    @BeforeClass
    public void setUp()
    {
        mongoQueryRunner = (MongoQueryRunner) getQueryRunner();
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        mongoQueryRunner.shutdown();
        mongoQueryRunner = null;
    }

    @Test
    public void createTableWithEveryType()
    {
        String query = "" +
                "CREATE TABLE test_types_table AS " +
                "SELECT" +
                " 'foo' _varchar" +
                ", cast('bar' as varbinary) _varbinary" +
                ", cast(1 as bigint) _bigint" +
                ", 3.14E0 _double" +
                ", true _boolean" +
                ", DATE '1980-05-07' _date" +
                ", TIMESTAMP '1980-05-07 11:22:33.456' _timestamp" +
                ", ObjectId('ffffffffffffffffffffffff') _objectid";

        assertUpdate(query, 1);

        MaterializedResult results = getQueryRunner().execute(getSession(), "SELECT * FROM test_types_table").toTestTypes();
        assertEquals(results.getRowCount(), 1);
        MaterializedRow row = results.getMaterializedRows().get(0);
        assertEquals(row.getField(0), "foo");
        assertEquals(row.getField(1), "bar".getBytes(UTF_8));
        assertEquals(row.getField(2), 1L);
        assertEquals(row.getField(3), 3.14);
        assertEquals(row.getField(4), true);
        assertEquals(row.getField(5), LocalDate.of(1980, 5, 7));
        assertEquals(row.getField(6), LocalDateTime.of(1980, 5, 7, 11, 22, 33, 456_000_000));
        assertUpdate("DROP TABLE test_types_table");

        assertFalse(getQueryRunner().tableExists(getSession(), "test_types_table"));
    }

    @Test
    public void testInsertWithEveryType()
            throws Exception
    {
        String createSql = "" +
                "CREATE TABLE test_insert_types_table " +
                "(" +
                "  vc varchar" +
                ", vb varbinary" +
                ", bi bigint" +
                ", d double" +
                ", b boolean" +
                ", dt  date" +
                ", ts  timestamp" +
                ", objid objectid" +
                ")";
        getQueryRunner().execute(getSession(), createSql);

        String insertSql = "" +
                "INSERT INTO test_insert_types_table " +
                "SELECT" +
                " 'foo' _varchar" +
                ", cast('bar' as varbinary) _varbinary" +
                ", cast(1 as bigint) _bigint" +
                ", 3.14E0 _double" +
                ", true _boolean" +
                ", DATE '1980-05-07' _date" +
                ", TIMESTAMP '1980-05-07 11:22:33.456' _timestamp" +
                ", ObjectId('ffffffffffffffffffffffff') _objectid";
        getQueryRunner().execute(getSession(), insertSql);

        MaterializedResult results = getQueryRunner().execute(getSession(), "SELECT * FROM test_insert_types_table").toTestTypes();
        assertEquals(results.getRowCount(), 1);
        MaterializedRow row = results.getMaterializedRows().get(0);
        assertEquals(row.getField(0), "foo");
        assertEquals(row.getField(1), "bar".getBytes(UTF_8));
        assertEquals(row.getField(2), 1L);
        assertEquals(row.getField(3), 3.14);
        assertEquals(row.getField(4), true);
        assertEquals(row.getField(5), LocalDate.of(1980, 5, 7));
        assertEquals(row.getField(6), LocalDateTime.of(1980, 5, 7, 11, 22, 33, 456_000_000));
        assertUpdate("DROP TABLE test_insert_types_table");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_insert_types_table"));
    }

    @Test
    public void testArrays()
    {
        assertUpdate("CREATE TABLE tmp_array1 AS SELECT ARRAY[1, 2, NULL] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array1", "SELECT 2");
        assertQuery("SELECT col[3] FROM tmp_array1", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array2 AS SELECT ARRAY[1.0E0, 2.5E0, 3.5E0] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array2", "SELECT 2.5");

        assertUpdate("CREATE TABLE tmp_array3 AS SELECT ARRAY['puppies', 'kittens', NULL] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array3", "SELECT 'kittens'");
        assertQuery("SELECT col[3] FROM tmp_array3", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array4 AS SELECT ARRAY[TRUE, NULL] AS col", 1);
        assertQuery("SELECT col[1] FROM tmp_array4", "SELECT TRUE");
        assertQuery("SELECT col[2] FROM tmp_array4", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array5 AS SELECT ARRAY[ARRAY[1, 2], NULL, ARRAY[3, 4]] AS col", 1);
        assertQuery("SELECT col[1][2] FROM tmp_array5", "SELECT 2");

        assertUpdate("CREATE TABLE tmp_array6 AS SELECT ARRAY[ARRAY['\"hi\"'], NULL, ARRAY['puppies']] AS col", 1);
        assertQuery("SELECT col[1][1] FROM tmp_array6", "SELECT '\"hi\"'");
        assertQuery("SELECT col[3][1] FROM tmp_array6", "SELECT 'puppies'");
    }

    @Test
    public void testTemporalArrays()
    {
        assertUpdate("CREATE TABLE tmp_array7 AS SELECT ARRAY[DATE '2014-09-30'] AS col", 1);
        assertOneNotNullResult("SELECT col[1] FROM tmp_array7");
        assertUpdate("CREATE TABLE tmp_array8 AS SELECT ARRAY[TIMESTAMP '2001-08-22 03:04:05.321'] AS col", 1);
        assertOneNotNullResult("SELECT col[1] FROM tmp_array8");
    }

    @Test
    public void testMaps()
    {
        assertUpdate("CREATE TABLE tmp_map1 AS SELECT MAP(ARRAY[0,1], ARRAY[2,NULL]) AS col", 1);
        assertQuery("SELECT col[0] FROM tmp_map1", "SELECT 2");
        assertQuery("SELECT col[1] FROM tmp_map1", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_map2 AS SELECT MAP(ARRAY[1.0E0], ARRAY[2.5E0]) AS col", 1);
        assertQuery("SELECT col[1.0] FROM tmp_map2", "SELECT 2.5");

        assertUpdate("CREATE TABLE tmp_map3 AS SELECT MAP(ARRAY['puppies'], ARRAY['kittens']) AS col", 1);
        assertQuery("SELECT col['puppies'] FROM tmp_map3", "SELECT 'kittens'");

        assertUpdate("CREATE TABLE tmp_map4 AS SELECT MAP(ARRAY[TRUE], ARRAY[FALSE]) AS col", "SELECT 1");
        assertQuery("SELECT col[TRUE] FROM tmp_map4", "SELECT FALSE");

        assertUpdate("CREATE TABLE tmp_map5 AS SELECT MAP(ARRAY[1.0E0], ARRAY[ARRAY[1, 2]]) AS col", 1);
        assertQuery("SELECT col[1.0][2] FROM tmp_map5", "SELECT 2");

        assertUpdate("CREATE TABLE tmp_map6 AS SELECT MAP(ARRAY[DATE '2014-09-30'], ARRAY[DATE '2014-09-29']) AS col", 1);
        assertOneNotNullResult("SELECT col[DATE '2014-09-30'] FROM tmp_map6");
        assertUpdate("CREATE TABLE tmp_map7 AS SELECT MAP(ARRAY[TIMESTAMP '2001-08-22 03:04:05.321'], ARRAY[TIMESTAMP '2001-08-22 03:04:05.321']) AS col", 1);
        assertOneNotNullResult("SELECT col[TIMESTAMP '2001-08-22 03:04:05.321'] FROM tmp_map7");

        assertUpdate("CREATE TABLE test.tmp_map8 (col MAP<VARCHAR, VARCHAR>)");
        mongoQueryRunner.getMongoClient().getDatabase("test").getCollection("tmp_map8").insertOne(new Document(
                ImmutableMap.of("col", new Document(ImmutableMap.of("key1", "value1", "key2", "value2")))));
        assertQuery("SELECT col['key1'] FROM test.tmp_map8", "SELECT 'value1'");

        assertUpdate("CREATE TABLE test.tmp_map9 (col VARCHAR)");
        mongoQueryRunner.getMongoClient().getDatabase("test").getCollection("tmp_map9").insertOne(new Document(
                ImmutableMap.of("col", new Document(ImmutableMap.of("key1", "value1", "key2", "value2")))));
        assertQuery("SELECT col FROM test.tmp_map9", "SELECT '{ \"key1\" : \"value1\", \"key2\" : \"value2\" }'");

        assertUpdate("CREATE TABLE test.tmp_map10 (col VARCHAR)");
        mongoQueryRunner.getMongoClient().getDatabase("test").getCollection("tmp_map10").insertOne(new Document(
                ImmutableMap.of("col", ImmutableList.of(new Document(ImmutableMap.of("key1", "value1", "key2", "value2")),
                        new Document(ImmutableMap.of("key3", "value3", "key4", "value4"))))));
        assertQuery("SELECT col FROM test.tmp_map10", "SELECT '[{ \"key1\" : \"value1\", \"key2\" : \"value2\" }, { \"key3\" : \"value3\", \"key4\" : \"value4\" }]'");

        assertUpdate("CREATE TABLE test.tmp_map11 (col VARCHAR)");
        mongoQueryRunner.getMongoClient().getDatabase("test").getCollection("tmp_map11").insertOne(new Document(
                ImmutableMap.of("col", 10)));
        assertQuery("SELECT col FROM test.tmp_map11", "SELECT '10'");

        assertUpdate("CREATE TABLE test.tmp_map12 (col VARCHAR)");
        mongoQueryRunner.getMongoClient().getDatabase("test").getCollection("tmp_map12").insertOne(new Document(
                ImmutableMap.of("col", Arrays.asList(10, null, 11))));
        assertQuery("SELECT col FROM test.tmp_map12", "SELECT '[10, null, 11]'");
    }

    @Test
    public void testCollectionNameContainsDots()
    {
        assertUpdate("CREATE TABLE \"tmp.dot1\" AS SELECT 'foo' _varchar", 1);
        assertQuery("SELECT _varchar FROM \"tmp.dot1\"", "SELECT 'foo'");
        assertUpdate("DROP TABLE \"tmp.dot1\"");
    }

    @Test
    public void testObjectIds()
    {
        String values = "VALUES " +
                " (10, NULL, NULL)," +
                " (11, ObjectId('ffffffffffffffffffffffff'), ObjectId('ffffffffffffffffffffffff'))," +
                " (12, ObjectId('ffffffffffffffffffffffff'), ObjectId('aaaaaaaaaaaaaaaaaaaaaaaa'))," +
                " (13, ObjectId('000000000000000000000000'), ObjectId('000000000000000000000000'))," +
                " (14, ObjectId('ffffffffffffffffffffffff'), NULL)," +
                " (15, NULL, ObjectId('ffffffffffffffffffffffff'))";
        String inlineTable = format("(%s) AS t(i, one, two)", values);

        assertUpdate("DROP TABLE IF EXISTS tmp_objectid");
        assertUpdate("CREATE TABLE tmp_objectid AS SELECT * FROM " + inlineTable, 6);

        // IS NULL
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS NULL", "VALUES 10, 15");
        assertQuery("SELECT i FROM tmp_objectid WHERE one IS NULL", "SELECT 0 WHERE false"); // NULL gets replaced with new unique ObjectId in MongoPageSink, this affects other test cases

        // CAST AS varchar
        assertQuery(
                "SELECT i, CAST(one AS varchar) FROM " + inlineTable + " WHERE i <= 13",
                "VALUES (10, NULL), (11, 'ffffffffffffffffffffffff'), (12, 'ffffffffffffffffffffffff'), (13, '000000000000000000000000')");

        // EQUAL
        assertQuery("SELECT i FROM tmp_objectid WHERE one = two", "VALUES 11, 13");
        assertQuery("SELECT i FROM tmp_objectid WHERE one = ObjectId('ffffffffffffffffffffffff')", "VALUES 11, 12, 14");

        // IS DISTINCT FROM
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS DISTINCT FROM two", "VALUES 12, 14, 15");
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS NOT DISTINCT FROM two", "VALUES 10, 11, 13");

        assertQuery("SELECT i FROM tmp_objectid WHERE one IS DISTINCT FROM two", "VALUES 10, 12, 14, 15");
        assertQuery("SELECT i FROM tmp_objectid WHERE one IS NOT DISTINCT FROM two", "VALUES 11, 13");

        // Join on ObjectId
        assertQuery(
                format("SELECT l.i, r.i FROM (%1$s) AS l(i, one, two) JOIN (%1$s) AS r(i, one, two) ON l.one = r.two", values),
                "VALUES (11, 11), (14, 11), (11, 15), (12, 15), (12, 11), (14, 15), (13, 13)");

        // Group by ObjectId (IS DISTINCT FROM)
        assertQuery("SELECT array_agg(i ORDER BY i) FROM " + inlineTable + " GROUP BY one", "VALUES ((10, 15)), ((11, 12, 14)), ((13))");
        assertQuery("SELECT i FROM " + inlineTable + " GROUP BY one, i", "VALUES 10, 11, 12, 13, 14, 15");

        // Group by Row(ObjectId) (ID DISTINCT FROM in @OperatorDependency)
        assertQuery(
                "SELECT r.i, count(*) FROM (SELECT CAST(row(one, i) AS row(one ObjectId, i bigint)) r FROM " + inlineTable + ") GROUP BY r",
                "VALUES (10, 1), (11, 1), (12, 1), (13, 1), (14, 1), (15, 1)");
        assertQuery(
                "SELECT r.x, CAST(r.one AS varchar), count(*) FROM (SELECT CAST(row(one, i / 3 * 3) AS row(one ObjectId, x bigint)) r FROM " + inlineTable + ") GROUP BY r",
                "VALUES (9, NULL, 1), (9, 'ffffffffffffffffffffffff', 1), (12, 'ffffffffffffffffffffffff', 2), (12, '000000000000000000000000', 1), (15, NULL, 1)");

        assertUpdate("DROP TABLE tmp_objectid");
    }

    private void assertOneNotNullResult(String query)
    {
        MaterializedResult results = getQueryRunner().execute(getSession(), query).toTestTypes();
        assertEquals(results.getRowCount(), 1);
        assertEquals(results.getMaterializedRows().get(0).getFieldCount(), 1);
        assertNotNull(results.getMaterializedRows().get(0).getField(0));
    }
}
