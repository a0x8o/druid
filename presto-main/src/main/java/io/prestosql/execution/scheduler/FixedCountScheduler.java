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
package io.prestosql.execution.scheduler;

import com.google.common.annotations.VisibleForTesting;
import io.prestosql.execution.RemoteTask;
import io.prestosql.execution.SqlStageExecution;
import io.prestosql.metadata.InternalNode;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class FixedCountScheduler
        implements StageScheduler
{
    public interface TaskScheduler
    {
        Optional<RemoteTask> scheduleTask(InternalNode node, int partition, OptionalInt totalPartitions);
    }

    private final TaskScheduler taskScheduler;
    private final List<InternalNode> partitionToNode;

    public FixedCountScheduler(SqlStageExecution stage, List<InternalNode> partitionToNode)
    {
        requireNonNull(stage, "stage is null");
        this.taskScheduler = stage::scheduleTask;
        this.partitionToNode = requireNonNull(partitionToNode, "partitionToNode is null");
    }

    @VisibleForTesting
    public FixedCountScheduler(TaskScheduler taskScheduler, List<InternalNode> partitionToNode)
    {
        this.taskScheduler = requireNonNull(taskScheduler, "taskScheduler is null");
        this.partitionToNode = requireNonNull(partitionToNode, "partitionToNode is null");
    }

    @Override
    public ScheduleResult schedule()
    {
        OptionalInt totalPartitions = OptionalInt.of(partitionToNode.size());
        List<RemoteTask> newTasks = IntStream.range(0, partitionToNode.size())
                .mapToObj(partition -> taskScheduler.scheduleTask(partitionToNode.get(partition), partition, totalPartitions))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toImmutableList());

        return new ScheduleResult(true, newTasks, 0);
    }
}
