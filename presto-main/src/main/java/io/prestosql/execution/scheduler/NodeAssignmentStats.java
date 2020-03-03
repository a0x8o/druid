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

import io.prestosql.execution.NodeTaskMap;
import io.prestosql.execution.RemoteTask;
import io.prestosql.metadata.InternalNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class NodeAssignmentStats
{
    private final NodeTaskMap nodeTaskMap;
    private final Map<InternalNode, Integer> assignmentCount = new HashMap<>();
    private final Map<InternalNode, Integer> splitCountByNode = new HashMap<>();
    private final Map<String, Integer> queuedSplitCountByNode = new HashMap<>();

    public NodeAssignmentStats(NodeTaskMap nodeTaskMap, NodeMap nodeMap, List<RemoteTask> existingTasks)
    {
        this.nodeTaskMap = requireNonNull(nodeTaskMap, "nodeTaskMap is null");

        // pre-populate the assignment counts with zeros. This makes getOrDefault() faster
        for (InternalNode node : nodeMap.getNodesByHostAndPort().values()) {
            assignmentCount.put(node, 0);
        }

        for (RemoteTask task : existingTasks) {
            checkArgument(queuedSplitCountByNode.put(task.getNodeId(), task.getQueuedPartitionedSplitCount()) == null, "A single stage may not have multiple tasks running on the same node");
        }
    }

    public int getTotalSplitCount(InternalNode node)
    {
        return assignmentCount.getOrDefault(node, 0) + splitCountByNode.computeIfAbsent(node, nodeTaskMap::getPartitionedSplitsOnNode);
    }

    public int getQueuedSplitCountForStage(InternalNode node)
    {
        return queuedSplitCountByNode.getOrDefault(node.getNodeIdentifier(), 0) + assignmentCount.getOrDefault(node, 0);
    }

    public void addAssignedSplit(InternalNode node)
    {
        assignmentCount.merge(node, 1, (x, y) -> x + y);
    }

    public void removeAssignedSplit(InternalNode node)
    {
        assignmentCount.merge(node, 1, (x, y) -> x - y);
    }
}
