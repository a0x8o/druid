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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.Session;
import io.prestosql.metadata.AbstractMockMetadata;
import io.prestosql.metadata.TableHandle;
import io.prestosql.spi.connector.SampleType;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.SampleNode.Type;
import io.prestosql.sql.planner.plan.TableScanNode;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.sql.planner.assertions.PlanMatchPattern.node;

public class TestPushSampleIntoTableScan
        extends BaseRuleTest
{
    @Test
    public void testDoesNotFire()
    {
        tester().assertThat(new PushSampleIntoTableScan(new TestMetadata(false)))
                .on(p ->
                        p.sample(
                                0.15,
                                Type.SYSTEM,
                                p.tableScan(ImmutableList.of(), ImmutableMap.of())))
                .doesNotFire();
    }

    @Test
    public void test()
    {
        tester().assertThat(new PushSampleIntoTableScan(new TestMetadata(true)))
                .on(p ->
                        p.sample(
                                0.15,
                                Type.SYSTEM,
                                p.tableScan(ImmutableList.of(), ImmutableMap.of())))
                .matches(node(TableScanNode.class));
    }

    private static class TestMetadata
            extends AbstractMockMetadata
    {
        private boolean samplePushdown;

        public TestMetadata(boolean samplePushdown)
        {
            this.samplePushdown = samplePushdown;
        }

        @Override
        public Optional<TableHandle> applySample(Session session, TableHandle table, SampleType sampleType, double sampleRatio)
        {
            if (samplePushdown) {
                return Optional.of(table);
            }
            return Optional.empty();
        }
    }
}
