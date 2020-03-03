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
package com.facebook.presto.verifier.resolver;

import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.verifier.prestoaction.PrestoAction;
import com.facebook.presto.verifier.prestoaction.PrestoResourceClient;
import io.airlift.units.Duration;

import static java.util.Objects.requireNonNull;

public class FailureResolverFactoryContext
{
    private final SqlParser sqlParser;
    private final PrestoAction prestoAction;
    private final PrestoResourceClient testResourceClient;
    private final int maxBucketPerWriter;
    private final Duration clusterSizeExpiration;

    public FailureResolverFactoryContext(
            SqlParser sqlParser,
            PrestoAction prestoAction,
            PrestoResourceClient testResourceClient,
            FailureResolverConfig config)
    {
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.prestoAction = requireNonNull(prestoAction, "prestoAction is null");
        this.testResourceClient = requireNonNull(testResourceClient, "testResourceClient is null");
        this.maxBucketPerWriter = config.getMaxBucketsPerWriter();
        this.clusterSizeExpiration = config.getClusterSizeExpiration();
    }

    public SqlParser getSqlParser()
    {
        return sqlParser;
    }

    public PrestoAction getPrestoAction()
    {
        return prestoAction;
    }

    public PrestoResourceClient getTestResourceClient()
    {
        return testResourceClient;
    }

    public int getMaxBucketPerWriter()
    {
        return maxBucketPerWriter;
    }

    public Duration getClusterSizeExpiration()
    {
        return clusterSizeExpiration;
    }
}
