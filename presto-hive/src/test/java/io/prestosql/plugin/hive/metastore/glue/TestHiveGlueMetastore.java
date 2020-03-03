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

import io.prestosql.plugin.hive.AbstractTestHiveLocal;
import io.prestosql.plugin.hive.metastore.HiveMetastore;

import java.io.File;

import static io.prestosql.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static java.util.Locale.ENGLISH;
import static java.util.UUID.randomUUID;

public class TestHiveGlueMetastore
        extends AbstractTestHiveLocal
{
    public TestHiveGlueMetastore()
    {
        super("test_glue" + randomUUID().toString().toLowerCase(ENGLISH).replace("-", ""));
    }

    /**
     * GlueHiveMetastore currently uses AWS Default Credential Provider Chain,
     * See https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
     * on ways to set your AWS credentials which will be needed to run this test.
     */
    @Override
    protected HiveMetastore createMetastore(File tempDir)
    {
        GlueHiveMetastoreConfig glueConfig = new GlueHiveMetastoreConfig();
        glueConfig.setDefaultWarehouseDir(tempDir.toURI().toString());

        return new GlueHiveMetastore(HDFS_ENVIRONMENT, glueConfig);
    }

    @Override
    public void testRenameTable()
    {
        // rename table is not yet supported by Glue
    }

    @Override
    public void testPartitionStatisticsSampling()
            throws Exception
    {
        // Glue metastore does not support column level statistics
    }

    @Override
    public void testUpdateTableColumnStatistics()
    {
        // column statistics are not supported by Glue
    }

    @Override
    public void testUpdateTableColumnStatisticsEmptyOptionalFields()
    {
        // column statistics are not supported by Glue
    }

    @Override
    public void testUpdatePartitionColumnStatistics()
    {
        // column statistics are not supported by Glue
    }

    @Override
    public void testUpdatePartitionColumnStatisticsEmptyOptionalFields()
    {
        // column statistics are not supported by Glue
    }

    @Override
    public void testStorePartitionWithStatistics()
            throws Exception
    {
        testStorePartitionWithStatistics(STATISTICS_PARTITIONED_TABLE_COLUMNS, BASIC_STATISTICS_1, BASIC_STATISTICS_2, BASIC_STATISTICS_1, EMPTY_TABLE_STATISTICS);
    }
}
