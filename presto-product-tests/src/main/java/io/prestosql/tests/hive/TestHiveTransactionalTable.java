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
package io.prestosql.tests.hive;

import io.prestosql.tempto.ProductTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.utils.QueryExecutors.onHive;
import static java.util.Locale.ENGLISH;

public class TestHiveTransactionalTable
        extends ProductTest
{
    @Test(dataProvider = "transactionalTableType")
    public void testSelectFromTransactionalTable(TransactionalTableType type)
    {
        String tableName = "test_select_from_transactional_table_" + type.name().toLowerCase(ENGLISH);
        onHive().executeQuery("" +
                "CREATE TABLE " + tableName + "(a bigint)" +
                "CLUSTERED BY(a) INTO 4 BUCKETS STORED AS ORC TBLPROPERTIES (" + type.getTableProperties() + ")");
        try {
            assertThat(() -> query("SELECT * FROM " + tableName))
                    .failsWithMessage("Hive transactional tables are not supported: default." + tableName);
        }
        finally {
            onHive().executeQuery("DROP TABLE " + tableName);
        }
    }

    @Test(dataProvider = "transactionalTableType")
    public void testInsertIntoTransactionalTable(TransactionalTableType type)
    {
        String tableName = "test_insert_into_transactional_table_" + type.name().toLowerCase(ENGLISH);
        onHive().executeQuery("" +
                "CREATE TABLE " + tableName + "(a bigint)" +
                "CLUSTERED BY(a) INTO 4 BUCKETS STORED AS ORC TBLPROPERTIES (" + type.getTableProperties() + ")");

        try {
            assertThat(() -> query("INSERT INTO " + tableName + " (a) VALUES (42)"))
                    .failsWithMessage("Hive transactional tables are not supported: default." + tableName);
        }
        finally {
            onHive().executeQuery("DROP TABLE " + tableName);
        }
    }

    @DataProvider
    public Object[][] transactionalTableType()
    {
        return new Object[][] {
                {TransactionalTableType.ACID},
                {TransactionalTableType.INSERT_ONLY},
        };
    }

    private enum TransactionalTableType
    {
        ACID {
            @Override
            String getTableProperties()
            {
                // TODO (https://github.com/prestosql/presto/issues/538) remove bucketing_version
                return "'transactional'='true', 'bucketing_version'='1'";
            }
        },
        INSERT_ONLY {
            @Override
            String getTableProperties()
            {
                // TODO (https://github.com/prestosql/presto/issues/538) remove bucketing_version
                return "'transactional'='true', 'transactional_properties'='insert_only', 'bucketing_version'='1'";
            }
        },
        /**/;

        abstract String getTableProperties();
    }
}
