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

import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.query.QueryResult;
import org.testng.annotations.Test;

import static io.prestosql.tempto.Requirements.compose;
import static io.prestosql.tempto.fulfillment.table.MutableTableRequirement.State.CREATED;
import static io.prestosql.tempto.fulfillment.table.MutableTablesState.mutableTablesState;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.mutableTable;
import static io.prestosql.tempto.fulfillment.table.hive.tpch.TpchTableDefinitions.NATION;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.SMOKE;
import static io.prestosql.tests.hive.HiveTableDefinitions.NATION_PARTITIONED_BY_BIGINT_REGIONKEY;
import static io.prestosql.tests.hive.HiveTableDefinitions.NATION_PARTITIONED_BY_REGIONKEY_NUMBER_OF_LINES_PER_SPLIT;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class TestTablePartitioningInsertInto
        extends HivePartitioningTest
        implements RequirementsProvider
{
    private static final String TARGET_NATION_NAME = "target_nation_test";

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return compose(
                mutableTable(NATION_PARTITIONED_BY_BIGINT_REGIONKEY),
                mutableTable(NATION, TARGET_NATION_NAME, CREATED));
    }

    @Test(groups = {SMOKE})
    public void selectFromPartitionedNation()
            throws Exception
    {
        // read all data
        testQuerySplitsNumber("p_nationkey < 40", 3);

        // read no partitions
        testQuerySplitsNumber("p_regionkey = 42", 0);

        // read one partition
        testQuerySplitsNumber("p_regionkey = 2 AND p_nationkey < 40", 1);
        // read two partitions
        testQuerySplitsNumber("p_regionkey = 2 AND p_nationkey < 40 or p_regionkey = 3", 2);
        // read all (three) partitions
        testQuerySplitsNumber("p_regionkey = 2 OR p_nationkey < 40", 3);

        // range read two partitions
        testQuerySplitsNumber("p_regionkey <= 2", 2);
        testQuerySplitsNumber("p_regionkey <= 1 OR p_regionkey >= 3", 2);
    }

    private void testQuerySplitsNumber(String condition, int expectedProcessedSplits)
            throws Exception
    {
        String partitionedNation = mutableTablesState().get(NATION_PARTITIONED_BY_BIGINT_REGIONKEY.getTableHandle()).getNameInDatabase();
        String targetNation = mutableTablesState().get(TARGET_NATION_NAME).getNameInDatabase();
        String query = format(
                "INSERT INTO %s SELECT p_nationkey, p_name, p_regionkey, p_comment FROM %s WHERE %s",
                targetNation,
                partitionedNation,
                condition);
        QueryResult queryResult = query(query);

        long processedLinesCount = getProcessedLinesCount(query, queryResult);
        int expectedLinesCount = expectedProcessedSplits * NATION_PARTITIONED_BY_REGIONKEY_NUMBER_OF_LINES_PER_SPLIT;
        assertThat(processedLinesCount).isEqualTo(expectedLinesCount);
    }
}
