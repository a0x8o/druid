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
package io.prestosql.server.security;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.prestosql.server.security.SecurityConfig.AuthenticationType.KERBEROS;
import static io.prestosql.server.security.SecurityConfig.AuthenticationType.PASSWORD;

public class TestSecurityConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(SecurityConfig.class)
                .setEnableForwardingHttps(false)
                .setAuthenticationTypes(""));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("http-server.authentication.type", "KERBEROS,PASSWORD")
                .put("http-server.authentication.allow-forwarded-https", "true")
                .build();

        SecurityConfig expected = new SecurityConfig()
                .setEnableForwardingHttps(true)
                .setAuthenticationTypes(ImmutableList.of(KERBEROS, PASSWORD));

        assertFullMapping(properties, expected);
    }
}
