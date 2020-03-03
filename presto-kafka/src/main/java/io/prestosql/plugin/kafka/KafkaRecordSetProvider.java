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
package io.prestosql.plugin.kafka;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.decoder.DispatchingRowDecoderFactory;
import io.prestosql.decoder.RowDecoder;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorRecordSetProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.RecordSet;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.plugin.kafka.KafkaHandleResolver.convertSplit;
import static java.util.Objects.requireNonNull;

/**
 * Factory for Kafka specific {@link RecordSet} instances.
 */
public class KafkaRecordSetProvider
        implements ConnectorRecordSetProvider
{
    private DispatchingRowDecoderFactory decoderFactory;
    private final KafkaSimpleConsumerManager consumerManager;

    @Inject
    public KafkaRecordSetProvider(DispatchingRowDecoderFactory decoderFactory, KafkaSimpleConsumerManager consumerManager)
    {
        this.decoderFactory = requireNonNull(decoderFactory, "decoderFactory is null");
        this.consumerManager = requireNonNull(consumerManager, "consumerManager is null");
    }

    @Override
    public RecordSet getRecordSet(ConnectorTransactionHandle transaction, ConnectorSession session, ConnectorSplit split, ConnectorTableHandle table, List<? extends ColumnHandle> columns)
    {
        KafkaSplit kafkaSplit = convertSplit(split);

        List<KafkaColumnHandle> kafkaColumns = columns.stream()
                .map(KafkaHandleResolver::convertColumnHandle)
                .collect(ImmutableList.toImmutableList());

        RowDecoder keyDecoder = decoderFactory.create(
                kafkaSplit.getKeyDataFormat(),
                getDecoderParameters(kafkaSplit.getKeyDataSchemaContents()),
                kafkaColumns.stream()
                        .filter(col -> !col.isInternal())
                        .filter(KafkaColumnHandle::isKeyDecoder)
                        .collect(toImmutableSet()));

        RowDecoder messageDecoder = decoderFactory.create(
                kafkaSplit.getMessageDataFormat(),
                getDecoderParameters(kafkaSplit.getMessageDataSchemaContents()),
                kafkaColumns.stream()
                        .filter(col -> !col.isInternal())
                        .filter(col -> !col.isKeyDecoder())
                        .collect(toImmutableSet()));

        return new KafkaRecordSet(kafkaSplit, consumerManager, kafkaColumns, keyDecoder, messageDecoder);
    }

    private Map<String, String> getDecoderParameters(Optional<String> dataSchema)
    {
        ImmutableMap.Builder<String, String> parameters = ImmutableMap.builder();
        dataSchema.ifPresent(schema -> parameters.put("dataSchema", schema));
        return parameters.build();
    }
}
