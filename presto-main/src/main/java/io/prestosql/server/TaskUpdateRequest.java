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
package io.prestosql.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.prestosql.SessionRepresentation;
import io.prestosql.execution.TaskSource;
import io.prestosql.execution.buffer.OutputBuffers;
import io.prestosql.sql.planner.PlanFragment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class TaskUpdateRequest
{
    private final SessionRepresentation session;
    // extraCredentials is stored separately from SessionRepresentation to avoid being leaked
    private final Map<String, String> extraCredentials;
    private final Optional<PlanFragment> fragment;
    private final List<TaskSource> sources;
    private final OutputBuffers outputIds;
    private final OptionalInt totalPartitions;

    @JsonCreator
    public TaskUpdateRequest(
            @JsonProperty("session") SessionRepresentation session,
            @JsonProperty("extraCredentials") Map<String, String> extraCredentials,
            @JsonProperty("fragment") Optional<PlanFragment> fragment,
            @JsonProperty("sources") List<TaskSource> sources,
            @JsonProperty("outputIds") OutputBuffers outputIds,
            @JsonProperty("totalPartitions") OptionalInt totalPartitions)
    {
        requireNonNull(session, "session is null");
        requireNonNull(extraCredentials, "credentials is null");
        requireNonNull(fragment, "fragment is null");
        requireNonNull(sources, "sources is null");
        requireNonNull(outputIds, "outputIds is null");
        requireNonNull(totalPartitions, "totalPartitions is null");

        this.session = session;
        this.extraCredentials = extraCredentials;
        this.fragment = fragment;
        this.sources = ImmutableList.copyOf(sources);
        this.outputIds = outputIds;
        this.totalPartitions = totalPartitions;
    }

    @JsonProperty
    public SessionRepresentation getSession()
    {
        return session;
    }

    @JsonProperty
    public Map<String, String> getExtraCredentials()
    {
        return extraCredentials;
    }

    @JsonProperty
    public Optional<PlanFragment> getFragment()
    {
        return fragment;
    }

    @JsonProperty
    public List<TaskSource> getSources()
    {
        return sources;
    }

    @JsonProperty
    public OutputBuffers getOutputIds()
    {
        return outputIds;
    }

    @JsonProperty
    public OptionalInt getTotalPartitions()
    {
        return totalPartitions;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("session", session)
                .add("extraCredentials", extraCredentials.keySet())
                .add("fragment", fragment)
                .add("sources", sources)
                .add("outputIds", outputIds)
                .add("totalPartitions", totalPartitions)
                .toString();
    }
}
