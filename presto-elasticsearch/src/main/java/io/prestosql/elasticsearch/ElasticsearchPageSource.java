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
package io.prestosql.elasticsearch;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.prestosql.elasticsearch.client.ElasticsearchClient;
import io.prestosql.elasticsearch.decoders.BigintDecoder;
import io.prestosql.elasticsearch.decoders.BooleanDecoder;
import io.prestosql.elasticsearch.decoders.Decoder;
import io.prestosql.elasticsearch.decoders.DoubleDecoder;
import io.prestosql.elasticsearch.decoders.IdColumnDecoder;
import io.prestosql.elasticsearch.decoders.IntegerDecoder;
import io.prestosql.elasticsearch.decoders.RealDecoder;
import io.prestosql.elasticsearch.decoders.RowDecoder;
import io.prestosql.elasticsearch.decoders.ScoreColumnDecoder;
import io.prestosql.elasticsearch.decoders.SmallintDecoder;
import io.prestosql.elasticsearch.decoders.SourceColumnDecoder;
import io.prestosql.elasticsearch.decoders.TimestampDecoder;
import io.prestosql.elasticsearch.decoders.TinyintDecoder;
import io.prestosql.elasticsearch.decoders.VarbinaryDecoder;
import io.prestosql.elasticsearch.decoders.VarcharDecoder;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.elasticsearch.BuiltinColumns.ID;
import static io.prestosql.elasticsearch.BuiltinColumns.SCORE;
import static io.prestosql.elasticsearch.BuiltinColumns.SOURCE;
import static io.prestosql.elasticsearch.ElasticsearchQueryBuilder.buildSearchQuery;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.Collectors.toList;

public class ElasticsearchPageSource
        implements ConnectorPageSource
{
    private static final Logger LOG = Logger.get(ElasticsearchPageSource.class);

    private final List<Decoder> decoders;

    private final SearchHitIterator iterator;
    private final BlockBuilder[] columnBuilders;
    private final List<ElasticsearchColumnHandle> columns;
    private long totalBytes;
    private long readTimeNanos;
    private boolean finished;

    public ElasticsearchPageSource(
            ElasticsearchClient client,
            ConnectorSession session,
            ElasticsearchTableHandle table,
            ElasticsearchSplit split,
            List<ElasticsearchColumnHandle> columns)
    {
        requireNonNull(client, "client is null");
        requireNonNull(columns, "columns is null");

        this.columns = ImmutableList.copyOf(columns);

        decoders = createDecoders(session, columns);

        // When the _source field is requested, we need to bypass column pruning when fetching the document
        boolean needAllFields = columns.stream()
                .map(ElasticsearchColumnHandle::getName)
                .anyMatch(isEqual(SOURCE.getName()));

        // Columns to fetch as doc_fields instead of pulling them out of the JSON source
        // This is convenient for types such as DATE, TIMESTAMP, etc, which have multiple possible
        // representations in JSON, but a single normalized representation as doc_field.
        List<String> documentFields = flattenFields(columns).entrySet().stream()
                .filter(entry -> entry.getValue().equals(TIMESTAMP))
                .map(Map.Entry::getKey)
                .collect(toImmutableList());

        columnBuilders = columns.stream()
                .map(ElasticsearchColumnHandle::getType)
                .map(type -> type.createBlockBuilder(null, 1))
                .toArray(BlockBuilder[]::new);

        List<String> requiredFields = columns.stream()
                .map(ElasticsearchColumnHandle::getName)
                .filter(name -> !BuiltinColumns.NAMES.contains(name))
                .collect(toList());

        long start = System.nanoTime();
        SearchResponse searchResponse = client.beginSearch(
                table.getIndex(),
                split.getShard(),
                buildSearchQuery(table.getConstraint(), columns, table.getQuery()),
                needAllFields ? Optional.empty() : Optional.of(requiredFields),
                documentFields);
        readTimeNanos += System.nanoTime() - start;
        this.iterator = new SearchHitIterator(client, () -> searchResponse);
    }

    @Override
    public long getCompletedBytes()
    {
        return totalBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return readTimeNanos + iterator.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return finished || !iterator.hasNext();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return 0;
    }

    @Override
    public void close()
    {
        iterator.close();
    }

    @Override
    public Page getNextPage()
    {
        if (columnBuilders.length == 0) {
            // TODO: emit "count" query against Elasticsearch
            int count = 0;
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }

            finished = true;
            return new Page(count);
        }

        long size = 0;
        while (size < PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES && iterator.hasNext()) {
            SearchHit hit = iterator.next();
            Map<String, Object> document = hit.getSourceAsMap();

            for (int i = 0; i < decoders.size(); i++) {
                String field = columns.get(i).getName();
                decoders.get(i).decode(hit, () -> getField(document, field), columnBuilders[i]);
            }

            if (hit.getSourceRef() != null) {
                totalBytes += hit.getSourceRef().length();
            }

            size = Arrays.stream(columnBuilders)
                    .mapToLong(BlockBuilder::getSizeInBytes)
                    .sum();
        }

        Block[] blocks = new Block[columnBuilders.length];
        for (int i = 0; i < columnBuilders.length; i++) {
            blocks[i] = columnBuilders[i].build();
            columnBuilders[i] = columnBuilders[i].newBlockBuilderLike(null);
        }

        return new Page(blocks);
    }

    public static Object getField(Map<String, Object> document, String field)
    {
        Object value = document.get(field);
        if (value == null) {
            Map<String, Object> result = new HashMap<>();
            String prefix = field + ".";
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(prefix)) {
                    result.put(key.substring(prefix.length()), entry.getValue());
                }
            }

            if (!result.isEmpty()) {
                return result;
            }
        }

        return value;
    }

    private Map<String, Type> flattenFields(List<ElasticsearchColumnHandle> columns)
    {
        Map<String, Type> result = new HashMap<>();

        for (ElasticsearchColumnHandle column : columns) {
            flattenFields(result, column.getName(), column.getType());
        }

        return result;
    }

    private void flattenFields(Map<String, Type> result, String fieldName, Type type)
    {
        if (type instanceof RowType) {
            for (RowType.Field field : ((RowType) type).getFields()) {
                flattenFields(result, appendPath(fieldName, field.getName().get()), field.getType());
            }
        }
        else {
            result.put(fieldName, type);
        }
    }

    private List<Decoder> createDecoders(ConnectorSession session, List<ElasticsearchColumnHandle> columns)
    {
        return columns.stream()
                .map(column -> {
                    if (column.getName().equals(ID.getName())) {
                        return new IdColumnDecoder();
                    }

                    if (column.getName().equals(SCORE.getName())) {
                        return new ScoreColumnDecoder();
                    }

                    if (column.getName().equals(SOURCE.getName())) {
                        return new SourceColumnDecoder();
                    }

                    return createDecoder(session, column.getName(), column.getType());
                })
                .collect(toImmutableList());
    }

    private Decoder createDecoder(ConnectorSession session, String path, Type type)
    {
        if (type.equals(VARCHAR)) {
            return new VarcharDecoder();
        }
        else if (type.equals(VARBINARY)) {
            return new VarbinaryDecoder();
        }
        else if (type.equals(TIMESTAMP)) {
            return new TimestampDecoder(session, path);
        }
        else if (type.equals(BOOLEAN)) {
            return new BooleanDecoder();
        }
        else if (type.equals(DOUBLE)) {
            return new DoubleDecoder();
        }
        else if (type.equals(REAL)) {
            return new RealDecoder();
        }
        else if (type.equals(TINYINT)) {
            return new TinyintDecoder();
        }
        else if (type.equals(SMALLINT)) {
            return new SmallintDecoder();
        }
        else if (type.equals(INTEGER)) {
            return new IntegerDecoder();
        }
        else if (type.equals(BIGINT)) {
            return new BigintDecoder();
        }
        else if (type instanceof RowType) {
            RowType rowType = (RowType) type;

            List<Decoder> decoders = rowType.getFields().stream()
                    .map(field -> createDecoder(session, appendPath(path, field.getName().get()), field.getType()))
                    .collect(toImmutableList());

            List<String> fieldNames = rowType.getFields().stream()
                    .map(RowType.Field::getName)
                    .map(Optional::get)
                    .collect(toImmutableList());

            return new RowDecoder(fieldNames, decoders);
        }

        throw new UnsupportedOperationException("Type not supported: " + type);
    }

    private static String appendPath(String base, String element)
    {
        if (base.isEmpty()) {
            return element;
        }

        return base + "." + element;
    }

    private static class SearchHitIterator
            extends AbstractIterator<SearchHit>
    {
        private final ElasticsearchClient client;
        private final Supplier<SearchResponse> first;

        private SearchHits searchHits;
        private String scrollId;
        private int currentPosition;

        private long readTimeNanos;

        public SearchHitIterator(ElasticsearchClient client, Supplier<SearchResponse> first)
        {
            this.client = client;
            this.first = first;
        }

        public long getReadTimeNanos()
        {
            return readTimeNanos;
        }

        @Override
        protected SearchHit computeNext()
        {
            if (scrollId == null) {
                long start = System.nanoTime();
                SearchResponse response = first.get();
                readTimeNanos += System.nanoTime() - start;
                reset(response);
            }
            else if (currentPosition == searchHits.getHits().length) {
                long start = System.nanoTime();
                SearchResponse response = client.nextPage(scrollId);
                readTimeNanos += System.nanoTime() - start;
                reset(response);
            }

            if (currentPosition == searchHits.getHits().length) {
                return endOfData();
            }

            SearchHit hit = searchHits.getAt(currentPosition);
            currentPosition++;

            return hit;
        }

        private void reset(SearchResponse response)
        {
            scrollId = response.getScrollId();
            searchHits = response.getHits();
            currentPosition = 0;
        }

        public void close()
        {
            if (scrollId != null) {
                try {
                    client.clearScroll(scrollId);
                }
                catch (Exception e) {
                    // ignore
                    LOG.debug("Error clearing scroll", e);
                }
            }
        }
    }
}
