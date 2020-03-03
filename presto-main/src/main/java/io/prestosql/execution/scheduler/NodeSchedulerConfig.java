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

import io.airlift.configuration.Config;
import io.airlift.configuration.DefunctConfig;
import io.airlift.configuration.LegacyConfig;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import static java.util.Locale.ENGLISH;

@DefunctConfig({"node-scheduler.location-aware-scheduling-enabled", "node-scheduler.multiple-tasks-per-node-enabled"})
public class NodeSchedulerConfig
{
    public enum NodeSchedulerPolicy
    {
        UNIFORM, TOPOLOGY
    }

    private int minCandidates = 10;
    private boolean includeCoordinator = true;
    private int maxSplitsPerNode = 100;
    private int maxPendingSplitsPerTask = 10;
    private NodeSchedulerPolicy nodeSchedulerPolicy = NodeSchedulerPolicy.UNIFORM;
    private boolean optimizedLocalScheduling = true;

    @NotNull
    public NodeSchedulerPolicy getNodeSchedulerPolicy()
    {
        return nodeSchedulerPolicy;
    }

    @LegacyConfig("node-scheduler.network-topology")
    @Config("node-scheduler.policy")
    public NodeSchedulerConfig setNodeSchedulerPolicy(String nodeSchedulerPolicy)
    {
        this.nodeSchedulerPolicy = toNodeSchedulerPolicy(nodeSchedulerPolicy);
        return this;
    }

    private static NodeSchedulerPolicy toNodeSchedulerPolicy(String nodeSchedulerPolicy)
    {
        // "legacy" and "flat" are here for backward compatibility
        switch (nodeSchedulerPolicy.toLowerCase(ENGLISH)) {
            case "legacy":
            case "uniform":
                return NodeSchedulerPolicy.UNIFORM;
            case "flat":
            case "topology":
                return NodeSchedulerPolicy.TOPOLOGY;
            default:
                throw new IllegalArgumentException("Unknown node scheduler policy: " + nodeSchedulerPolicy);
        }
    }

    @Min(1)
    public int getMinCandidates()
    {
        return minCandidates;
    }

    @Config("node-scheduler.min-candidates")
    public NodeSchedulerConfig setMinCandidates(int candidates)
    {
        this.minCandidates = candidates;
        return this;
    }

    public boolean isIncludeCoordinator()
    {
        return includeCoordinator;
    }

    @Config("node-scheduler.include-coordinator")
    public NodeSchedulerConfig setIncludeCoordinator(boolean includeCoordinator)
    {
        this.includeCoordinator = includeCoordinator;
        return this;
    }

    @Config("node-scheduler.max-pending-splits-per-task")
    @LegacyConfig({"node-scheduler.max-pending-splits-per-node-per-task", "node-scheduler.max-pending-splits-per-node-per-stage"})
    public NodeSchedulerConfig setMaxPendingSplitsPerTask(int maxPendingSplitsPerTask)
    {
        this.maxPendingSplitsPerTask = maxPendingSplitsPerTask;
        return this;
    }

    public int getMaxPendingSplitsPerTask()
    {
        return maxPendingSplitsPerTask;
    }

    public int getMaxSplitsPerNode()
    {
        return maxSplitsPerNode;
    }

    @Config("node-scheduler.max-splits-per-node")
    public NodeSchedulerConfig setMaxSplitsPerNode(int maxSplitsPerNode)
    {
        this.maxSplitsPerNode = maxSplitsPerNode;
        return this;
    }

    public boolean getOptimizedLocalScheduling()
    {
        return optimizedLocalScheduling;
    }

    @Config("node-scheduler.optimized-local-scheduling")
    public NodeSchedulerConfig setOptimizedLocalScheduling(boolean optimizedLocalScheduling)
    {
        this.optimizedLocalScheduling = optimizedLocalScheduling;
        return this;
    }
}
