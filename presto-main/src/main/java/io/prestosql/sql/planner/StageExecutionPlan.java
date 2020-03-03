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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.execution.TableInfo;
import io.prestosql.split.SplitSource;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNodeId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class StageExecutionPlan
{
    private final PlanFragment fragment;
    private final Map<PlanNodeId, SplitSource> splitSources;
    private final List<StageExecutionPlan> subStages;
    private final Optional<List<String>> fieldNames;
    private final Map<PlanNodeId, TableInfo> tables;

    public StageExecutionPlan(
            PlanFragment fragment,
            Map<PlanNodeId, SplitSource> splitSources,
            List<StageExecutionPlan> subStages, Map<PlanNodeId, TableInfo> tables)
    {
        this.fragment = requireNonNull(fragment, "fragment is null");
        this.splitSources = requireNonNull(splitSources, "dataSource is null");
        this.subStages = ImmutableList.copyOf(requireNonNull(subStages, "dependencies is null"));

        fieldNames = (fragment.getRoot() instanceof OutputNode) ?
                Optional.of(ImmutableList.copyOf(((OutputNode) fragment.getRoot()).getColumnNames())) :
                Optional.empty();

        this.tables = ImmutableMap.copyOf(requireNonNull(tables, "tables is null"));
    }

    public List<String> getFieldNames()
    {
        checkState(fieldNames.isPresent(), "cannot get field names from non-output stage");
        return fieldNames.get();
    }

    public PlanFragment getFragment()
    {
        return fragment;
    }

    public Map<PlanNodeId, SplitSource> getSplitSources()
    {
        return splitSources;
    }

    public List<StageExecutionPlan> getSubStages()
    {
        return subStages;
    }

    public Map<PlanNodeId, TableInfo> getTables()
    {
        return tables;
    }

    public StageExecutionPlan withBucketToPartition(Optional<int[]> bucketToPartition)
    {
        return new StageExecutionPlan(fragment.withBucketToPartition(bucketToPartition), splitSources, subStages, tables);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("fragment", fragment)
                .add("splitSources", splitSources)
                .add("subStages", subStages)
                .toString();
    }
}
