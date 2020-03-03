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
package io.prestosql.memory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.execution.QueryState;
import io.prestosql.server.BasicQueryInfo;
import io.prestosql.server.BasicQueryStats;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalDouble;

import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.execution.QueryState.FINISHED;
import static io.prestosql.execution.QueryState.RUNNING;
import static io.prestosql.memory.LocalMemoryManager.GENERAL_POOL;
import static io.prestosql.operator.BlockedReason.WAITING_FOR_MEMORY;
import static org.testng.Assert.assertEquals;

@Test
public class TestClusterMemoryLeakDetector
{
    @Test
    public void testLeakDetector()
    {
        QueryId testQuery = new QueryId("test");
        ClusterMemoryLeakDetector leakDetector = new ClusterMemoryLeakDetector();

        leakDetector.checkForMemoryLeaks(ImmutableList::of, ImmutableMap.of());
        assertEquals(leakDetector.getNumberOfLeakedQueries(), 0);

        // the leak detector should report no leaked queries as the query is still running
        leakDetector.checkForMemoryLeaks(() -> ImmutableList.of(createQueryInfo(testQuery.getId(), RUNNING)), ImmutableMap.of(testQuery, 1L));
        assertEquals(leakDetector.getNumberOfLeakedQueries(), 0);

        // the leak detector should report exactly one leaked query since the query is finished, and its end time is way in the past
        leakDetector.checkForMemoryLeaks(() -> ImmutableList.of(createQueryInfo(testQuery.getId(), FINISHED)), ImmutableMap.of(testQuery, 1L));
        assertEquals(leakDetector.getNumberOfLeakedQueries(), 1);

        // the leak detector should report no leaked queries as the query doesn't have any memory reservation
        leakDetector.checkForMemoryLeaks(() -> ImmutableList.of(createQueryInfo(testQuery.getId(), FINISHED)), ImmutableMap.of(testQuery, 0L));
        assertEquals(leakDetector.getNumberOfLeakedQueries(), 0);

        // the leak detector should report exactly one leaked query since the coordinator doesn't know of any query
        leakDetector.checkForMemoryLeaks(ImmutableList::of, ImmutableMap.of(testQuery, 1L));
        assertEquals(leakDetector.getNumberOfLeakedQueries(), 1);
    }

    private static BasicQueryInfo createQueryInfo(String queryId, QueryState state)
    {
        return new BasicQueryInfo(
                new QueryId(queryId),
                TEST_SESSION.toSessionRepresentation(),
                Optional.of(new ResourceGroupId("global")),
                state,
                GENERAL_POOL,
                true,
                URI.create("1"),
                "",
                Optional.empty(),
                new BasicQueryStats(
                        DateTime.parse("1991-09-06T05:00-05:30"),
                        DateTime.parse("1991-09-06T05:01-05:30"),
                        Duration.valueOf("8m"),
                        Duration.valueOf("7m"),
                        Duration.valueOf("34m"),
                        13,
                        14,
                        15,
                        100,
                        DataSize.valueOf("21GB"),
                        22,
                        23,
                        DataSize.valueOf("23GB"),
                        DataSize.valueOf("24GB"),
                        DataSize.valueOf("25GB"),
                        DataSize.valueOf("26GB"),
                        Duration.valueOf("23m"),
                        Duration.valueOf("24m"),
                        true,
                        ImmutableSet.of(WAITING_FOR_MEMORY),
                        OptionalDouble.of(20)),
                null,
                null);
    }
}
