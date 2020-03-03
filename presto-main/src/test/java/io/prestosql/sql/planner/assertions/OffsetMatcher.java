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
package io.prestosql.sql.planner.assertions;

import io.prestosql.Session;
import io.prestosql.cost.StatsProvider;
import io.prestosql.metadata.Metadata;
import io.prestosql.sql.planner.plan.OffsetNode;
import io.prestosql.sql.planner.plan.PlanNode;

import static com.google.common.base.Preconditions.checkState;

public class OffsetMatcher
        implements Matcher
{
    private final long rowCount;

    public OffsetMatcher(long rowCount)
    {
        this.rowCount = rowCount;
    }

    @Override
    public boolean shapeMatches(PlanNode node)
    {
        if (!(node instanceof OffsetNode)) {
            return false;
        }

        OffsetNode offsetNode = (OffsetNode) node;
        return offsetNode.getCount() == rowCount;
    }

    @Override
    public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
    {
        checkState(shapeMatches(node));
        return MatchResult.match();
    }
}
