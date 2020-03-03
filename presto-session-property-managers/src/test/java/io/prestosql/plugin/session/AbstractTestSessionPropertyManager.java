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
package io.prestosql.plugin.session;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.spi.resourcegroups.QueryType;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.spi.session.SessionConfigurationContext;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Test
public abstract class AbstractTestSessionPropertyManager
{
    protected static final SessionConfigurationContext CONTEXT = new SessionConfigurationContext(
            "user",
            Optional.of("source"),
            ImmutableSet.of("tag1", "tag2"),
            Optional.of(QueryType.DATA_DEFINITION.toString()),
            new ResourceGroupId(ImmutableList.of("global", "pipeline", "user_foo", "bar")));

    protected abstract void assertProperties(Map<String, String> properties, SessionMatchSpec... spec)
            throws Exception;

    @Test
    public void testResourceGroupMatch()
            throws Exception
    {
        Map<String, String> properties = ImmutableMap.of("PROPERTY1", "VALUE1", "PROPERTY2", "VALUE2");
        SessionMatchSpec spec = new SessionMatchSpec(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Pattern.compile("global.pipeline.user_.*")),
                properties);

        assertProperties(properties, spec);
    }

    @Test
    public void testClientTagMatch()
            throws Exception
    {
        ImmutableMap<String, String> properties = ImmutableMap.of("PROPERTY", "VALUE");
        SessionMatchSpec spec = new SessionMatchSpec(
                Optional.empty(),
                Optional.empty(),
                Optional.of(ImmutableList.of("tag2")),
                Optional.empty(),
                Optional.empty(),
                properties);

        assertProperties(properties, spec);
    }

    @Test
    public void testMultipleMatch()
            throws Exception
    {
        SessionMatchSpec spec1 = new SessionMatchSpec(
                Optional.empty(),
                Optional.empty(),
                Optional.of(ImmutableList.of("tag2")),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of("PROPERTY1", "VALUE1", "PROPERTY3", "VALUE3"));
        SessionMatchSpec spec2 = new SessionMatchSpec(
                Optional.empty(),
                Optional.empty(),
                Optional.of(ImmutableList.of("tag1", "tag2")),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of("PROPERTY1", "VALUE1", "PROPERTY2", "VALUE2"));

        assertProperties(ImmutableMap.of("PROPERTY1", "VALUE1", "PROPERTY2", "VALUE2", "PROPERTY3", "VALUE3"), spec1, spec2);
    }

    @Test
    public void testNoMatch()
            throws Exception
    {
        SessionMatchSpec spec = new SessionMatchSpec(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Pattern.compile("global.interactive.user_.*")),
                ImmutableMap.of("PROPERTY", "VALUE"));

        assertProperties(ImmutableMap.of(), spec);
    }
}
