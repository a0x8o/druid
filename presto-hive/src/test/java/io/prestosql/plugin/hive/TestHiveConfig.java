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
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.airlift.units.DataSize.Unit;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.prestosql.plugin.hive.util.TestHiveUtil.nonDefaultTimeZone;

public class TestHiveConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(HiveConfig.class)
                .setTimeZone(TimeZone.getDefault().getID())
                .setMaxSplitSize(new DataSize(64, Unit.MEGABYTE))
                .setMaxPartitionsPerScan(100_000)
                .setMaxOutstandingSplits(1_000)
                .setMaxOutstandingSplitsSize(new DataSize(256, Unit.MEGABYTE))
                .setMaxSplitIteratorThreads(1_000)
                .setAllowCorruptWritesForTesting(false)
                .setPerTransactionMetastoreCacheMaximumSize(1000)
                .setMinPartitionBatchSize(10)
                .setMaxPartitionBatchSize(100)
                .setMaxInitialSplits(200)
                .setMaxInitialSplitSize(new DataSize(32, Unit.MEGABYTE))
                .setSplitLoaderConcurrency(4)
                .setMaxSplitsPerSecond(null)
                .setDomainCompactionThreshold(100)
                .setWriterSortBufferSize(new DataSize(64, Unit.MEGABYTE))
                .setForceLocalScheduling(false)
                .setMaxConcurrentFileRenames(20)
                .setRecursiveDirWalkerEnabled(false)
                .setHiveStorageFormat(HiveStorageFormat.ORC)
                .setHiveCompressionCodec(HiveCompressionCodec.GZIP)
                .setRespectTableFormat(true)
                .setImmutablePartitions(false)
                .setCreateEmptyBucketFiles(true)
                .setSortedWritingEnabled(true)
                .setMaxPartitionsPerWriter(100)
                .setMaxOpenSortFiles(50)
                .setWriteValidationThreads(16)
                .setTextMaxLineLength(new DataSize(100, Unit.MEGABYTE))
                .setUseParquetColumnNames(false)
                .setAssumeCanonicalPartitionKeys(false)
                .setRcfileWriterValidate(false)
                .setSkipDeletionForAlter(false)
                .setSkipTargetCleanupOnRollback(false)
                .setBucketExecutionEnabled(true)
                .setTableStatisticsEnabled(true)
                .setOptimizeMismatchedBucketCount(false)
                .setWritesToNonManagedTablesEnabled(false)
                .setCreatesOfNonManagedTablesEnabled(true)
                .setPartitionStatisticsSampleSize(100)
                .setIgnoreCorruptedStatistics(false)
                .setRecordingPath(null)
                .setRecordingDuration(new Duration(10, TimeUnit.MINUTES))
                .setReplay(false)
                .setCollectColumnStatisticsOnWrite(true)
                .setS3SelectPushdownEnabled(false)
                .setS3SelectPushdownMaxConnections(500)
                .setTemporaryStagingDirectoryEnabled(true)
                .setTemporaryStagingDirectoryPath("/tmp/presto-${USER}")
                .setFileStatusCacheExpireAfterWrite(new Duration(1, TimeUnit.MINUTES))
                .setFileStatusCacheMaxSize(1000 * 1000)
                .setFileStatusCacheTables(""));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("hive.time-zone", nonDefaultTimeZone().getID())
                .put("hive.max-split-size", "256MB")
                .put("hive.max-partitions-per-scan", "123")
                .put("hive.max-outstanding-splits", "10")
                .put("hive.max-outstanding-splits-size", "32MB")
                .put("hive.max-split-iterator-threads", "10")
                .put("hive.allow-corrupt-writes-for-testing", "true")
                .put("hive.per-transaction-metastore-cache-maximum-size", "500")
                .put("hive.metastore.partition-batch-size.min", "1")
                .put("hive.metastore.partition-batch-size.max", "1000")
                .put("hive.max-initial-splits", "10")
                .put("hive.max-initial-split-size", "16MB")
                .put("hive.split-loader-concurrency", "1")
                .put("hive.max-splits-per-second", "1")
                .put("hive.domain-compaction-threshold", "42")
                .put("hive.writer-sort-buffer-size", "13MB")
                .put("hive.recursive-directories", "true")
                .put("hive.storage-format", "SEQUENCEFILE")
                .put("hive.compression-codec", "NONE")
                .put("hive.respect-table-format", "false")
                .put("hive.immutable-partitions", "true")
                .put("hive.create-empty-bucket-files", "false")
                .put("hive.max-partitions-per-writers", "222")
                .put("hive.max-open-sort-files", "333")
                .put("hive.write-validation-threads", "11")
                .put("hive.force-local-scheduling", "true")
                .put("hive.max-concurrent-file-renames", "100")
                .put("hive.assume-canonical-partition-keys", "true")
                .put("hive.text.max-line-length", "13MB")
                .put("hive.parquet.use-column-names", "true")
                .put("hive.rcfile.writer.validate", "true")
                .put("hive.skip-deletion-for-alter", "true")
                .put("hive.skip-target-cleanup-on-rollback", "true")
                .put("hive.bucket-execution", "false")
                .put("hive.sorted-writing", "false")
                .put("hive.table-statistics-enabled", "false")
                .put("hive.optimize-mismatched-bucket-count", "true")
                .put("hive.non-managed-table-writes-enabled", "true")
                .put("hive.non-managed-table-creates-enabled", "false")
                .put("hive.partition-statistics-sample-size", "1234")
                .put("hive.ignore-corrupted-statistics", "true")
                .put("hive.metastore-recording-path", "/foo/bar")
                .put("hive.metastore-recording-duration", "42s")
                .put("hive.replay-metastore-recording", "true")
                .put("hive.collect-column-statistics-on-write", "false")
                .put("hive.s3select-pushdown.enabled", "true")
                .put("hive.s3select-pushdown.max-connections", "1234")
                .put("hive.temporary-staging-directory-enabled", "false")
                .put("hive.temporary-staging-directory-path", "updated")
                .put("hive.file-status-cache-tables", "foo.bar1, foo.bar2")
                .put("hive.file-status-cache-size", "1000")
                .put("hive.file-status-cache-expire-time", "30m")
                .build();

        HiveConfig expected = new HiveConfig()
                .setTimeZone(nonDefaultTimeZone().toTimeZone().getID())
                .setMaxSplitSize(new DataSize(256, Unit.MEGABYTE))
                .setMaxPartitionsPerScan(123)
                .setMaxOutstandingSplits(10)
                .setMaxOutstandingSplitsSize(new DataSize(32, Unit.MEGABYTE))
                .setMaxSplitIteratorThreads(10)
                .setAllowCorruptWritesForTesting(true)
                .setPerTransactionMetastoreCacheMaximumSize(500)
                .setMinPartitionBatchSize(1)
                .setMaxPartitionBatchSize(1000)
                .setMaxInitialSplits(10)
                .setMaxInitialSplitSize(new DataSize(16, Unit.MEGABYTE))
                .setSplitLoaderConcurrency(1)
                .setMaxSplitsPerSecond(1)
                .setDomainCompactionThreshold(42)
                .setWriterSortBufferSize(new DataSize(13, Unit.MEGABYTE))
                .setForceLocalScheduling(true)
                .setMaxConcurrentFileRenames(100)
                .setRecursiveDirWalkerEnabled(true)
                .setHiveStorageFormat(HiveStorageFormat.SEQUENCEFILE)
                .setHiveCompressionCodec(HiveCompressionCodec.NONE)
                .setRespectTableFormat(false)
                .setImmutablePartitions(true)
                .setCreateEmptyBucketFiles(false)
                .setMaxPartitionsPerWriter(222)
                .setMaxOpenSortFiles(333)
                .setWriteValidationThreads(11)
                .setTextMaxLineLength(new DataSize(13, Unit.MEGABYTE))
                .setUseParquetColumnNames(true)
                .setAssumeCanonicalPartitionKeys(true)
                .setRcfileWriterValidate(true)
                .setSkipDeletionForAlter(true)
                .setSkipTargetCleanupOnRollback(true)
                .setBucketExecutionEnabled(false)
                .setSortedWritingEnabled(false)
                .setTableStatisticsEnabled(false)
                .setOptimizeMismatchedBucketCount(true)
                .setWritesToNonManagedTablesEnabled(true)
                .setCreatesOfNonManagedTablesEnabled(false)
                .setPartitionStatisticsSampleSize(1234)
                .setIgnoreCorruptedStatistics(true)
                .setRecordingPath("/foo/bar")
                .setRecordingDuration(new Duration(42, TimeUnit.SECONDS))
                .setReplay(true)
                .setCollectColumnStatisticsOnWrite(false)
                .setS3SelectPushdownEnabled(true)
                .setS3SelectPushdownMaxConnections(1234)
                .setTemporaryStagingDirectoryEnabled(false)
                .setTemporaryStagingDirectoryPath("updated")
                .setFileStatusCacheTables("foo.bar1,foo.bar2")
                .setFileStatusCacheMaxSize(1000)
                .setFileStatusCacheExpireAfterWrite(new Duration(30, TimeUnit.MINUTES));

        assertFullMapping(properties, expected);
    }
}
