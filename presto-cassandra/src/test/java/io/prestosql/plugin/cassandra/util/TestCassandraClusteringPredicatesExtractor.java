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
package io.prestosql.plugin.cassandra.util;

import com.datastax.driver.core.VersionNumber;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.plugin.cassandra.CassandraClusteringPredicatesExtractor;
import io.prestosql.plugin.cassandra.CassandraColumnHandle;
import io.prestosql.plugin.cassandra.CassandraTable;
import io.prestosql.plugin.cassandra.CassandraTableHandle;
import io.prestosql.plugin.cassandra.CassandraType;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static org.testng.Assert.assertEquals;

public class TestCassandraClusteringPredicatesExtractor
{
    private static CassandraColumnHandle col1;
    private static CassandraColumnHandle col2;
    private static CassandraColumnHandle col3;
    private static CassandraColumnHandle col4;
    private static CassandraTable cassandraTable;
    private static VersionNumber cassandraVersion;

    @BeforeTest
    void setUp()
    {
        col1 = new CassandraColumnHandle("partitionKey1", 1, CassandraType.BIGINT, true, false, false, false);
        col2 = new CassandraColumnHandle("clusteringKey1", 2, CassandraType.BIGINT, false, true, false, false);
        col3 = new CassandraColumnHandle("clusteringKey2", 3, CassandraType.BIGINT, false, true, false, false);
        col4 = new CassandraColumnHandle("clusteringKe3", 4, CassandraType.BIGINT, false, true, false, false);

        cassandraTable = new CassandraTable(
                new CassandraTableHandle("test", "records"), ImmutableList.of(col1, col2, col3, col4));

        cassandraVersion = VersionNumber.parse("2.1.5");
    }

    @Test
    public void testBuildClusteringPredicate()
    {
        TupleDomain<ColumnHandle> tupleDomain = TupleDomain.withColumnDomains(
                ImmutableMap.of(
                        col1, Domain.singleValue(BIGINT, 23L),
                        col2, Domain.singleValue(BIGINT, 34L),
                        col4, Domain.singleValue(BIGINT, 26L)));
        CassandraClusteringPredicatesExtractor predicatesExtractor = new CassandraClusteringPredicatesExtractor(cassandraTable.getClusteringKeyColumns(), tupleDomain, cassandraVersion);
        String predicate = predicatesExtractor.getClusteringKeyPredicates();
        assertEquals(predicate, "\"clusteringKey1\" = 34");
    }

    @Test
    public void testGetUnenforcedPredicates()
    {
        TupleDomain<ColumnHandle> tupleDomain = TupleDomain.withColumnDomains(
                ImmutableMap.of(
                        col2, Domain.singleValue(BIGINT, 34L),
                        col4, Domain.singleValue(BIGINT, 26L)));
        CassandraClusteringPredicatesExtractor predicatesExtractor = new CassandraClusteringPredicatesExtractor(cassandraTable.getClusteringKeyColumns(), tupleDomain, cassandraVersion);
        TupleDomain<ColumnHandle> unenforcedPredicates = TupleDomain.withColumnDomains(ImmutableMap.of(col4, Domain.singleValue(BIGINT, 26L)));
        assertEquals(predicatesExtractor.getUnenforcedConstraints(), unenforcedPredicates);
    }
}
