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
package io.prestosql.plugin.hive.metastore.glue;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestGlueHiveMetastoreConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(GlueHiveMetastoreConfig.class)
                .setGlueRegion(null)
                .setPinGlueClientToCurrentRegion(false)
                .setMaxGlueConnections(5)
                .setDefaultWarehouseDir(null)
                .setIamRole(null)
                .setAwsAccessKey(null)
                .setAwsSecretKey(null)
                .setAwsCredentialsProvider(null)
                .setCatalogId(null)
                .setUseInstanceCredentials(false));
    }

    @Test
    public void testExplicitPropertyMapping()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("hive.metastore.glue.region", "us-east-1")
                .put("hive.metastore.glue.pin-client-to-current-region", "true")
                .put("hive.metastore.glue.max-connections", "10")
                .put("hive.metastore.glue.default-warehouse-dir", "/location")
                .put("hive.metastore.glue.iam-role", "role")
                .put("hive.metastore.glue.aws-access-key", "ABC")
                .put("hive.metastore.glue.aws-secret-key", "DEF")
                .put("hive.metastore.glue.aws-credentials-provider", "custom")
                .put("hive.metastore.glue.catalogid", "0123456789")
                .put("hive.metastore.glue.use-instance-credentials", "true")
                .build();

        GlueHiveMetastoreConfig expected = new GlueHiveMetastoreConfig()
                .setGlueRegion("us-east-1")
                .setPinGlueClientToCurrentRegion(true)
                .setMaxGlueConnections(10)
                .setDefaultWarehouseDir("/location")
                .setIamRole("role")
                .setAwsAccessKey("ABC")
                .setAwsSecretKey("DEF")
                .setAwsCredentialsProvider("custom")
                .setCatalogId("0123456789")
                .setUseInstanceCredentials(true);

        assertFullMapping(properties, expected);
    }
}
