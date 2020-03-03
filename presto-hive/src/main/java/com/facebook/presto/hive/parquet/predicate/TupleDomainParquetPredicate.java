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
package com.facebook.presto.hive.parquet.predicate;

import com.facebook.presto.hive.parquet.ParquetDictionaryPage;
import com.facebook.presto.hive.parquet.RichColumnDescriptor;
import com.facebook.presto.hive.parquet.dictionary.ParquetDictionary;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.Range;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.predicate.ValueSet;
import com.facebook.presto.spi.type.Type;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import parquet.column.ColumnDescriptor;
import parquet.column.statistics.BinaryStatistics;
import parquet.column.statistics.BooleanStatistics;
import parquet.column.statistics.DoubleStatistics;
import parquet.column.statistics.FloatStatistics;
import parquet.column.statistics.IntStatistics;
import parquet.column.statistics.LongStatistics;
import parquet.column.statistics.Statistics;
import parquet.schema.PrimitiveType.PrimitiveTypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.hive.parquet.ParquetTypeUtils.getPrestoType;
import static com.facebook.presto.hive.parquet.predicate.ParquetPredicateUtils.isStatisticsOverflow;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.facebook.presto.spi.type.Varchars.isVarcharType;
import static java.lang.Float.floatToRawIntBits;
import static java.util.Objects.requireNonNull;

public class TupleDomainParquetPredicate
        implements ParquetPredicate
{
    private final TupleDomain<ColumnDescriptor> effectivePredicate;
    private final List<RichColumnDescriptor> columns;

    public TupleDomainParquetPredicate(TupleDomain<ColumnDescriptor> effectivePredicate, List<RichColumnDescriptor> columns)
    {
        this.effectivePredicate = requireNonNull(effectivePredicate, "effectivePredicate is null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));
    }

    @Override
    public boolean matches(long numberOfRows, Map<ColumnDescriptor, Statistics<?>> statistics)
    {
        if (numberOfRows == 0) {
            return false;
        }
        ImmutableMap.Builder<ColumnDescriptor, Domain> domains = ImmutableMap.builder();

        for (RichColumnDescriptor column : columns) {
            Statistics<?> columnStatistics = statistics.get(column);

            Domain domain;
            Type type = getPrestoType(column);
            if (columnStatistics == null || columnStatistics.isEmpty()) {
                // no stats for column
                domain = Domain.all(type);
            }
            else {
                domain = getDomain(type, numberOfRows, columnStatistics);
            }
            domains.put(column, domain);
        }
        TupleDomain<ColumnDescriptor> stripeDomain = TupleDomain.withColumnDomains(domains.build());

        return effectivePredicate.overlaps(stripeDomain);
    }

    @Override
    public boolean matches(Map<ColumnDescriptor, ParquetDictionaryDescriptor> dictionaries)
    {
        ImmutableMap.Builder<ColumnDescriptor, Domain> domains = ImmutableMap.builder();

        for (RichColumnDescriptor column : columns) {
            ParquetDictionaryDescriptor dictionaryDescriptor = dictionaries.get(column);
            Domain domain = getDomain(getPrestoType(column), dictionaryDescriptor);
            if (domain != null) {
                domains.put(column, domain);
            }
        }
        TupleDomain<ColumnDescriptor> stripeDomain = TupleDomain.withColumnDomains(domains.build());

        return effectivePredicate.overlaps(stripeDomain);
    }

    @VisibleForTesting
    public static Domain getDomain(Type type, long rowCount, Statistics<?> statistics)
    {
        if (statistics == null || statistics.isEmpty()) {
            return Domain.all(type);
        }

        if (statistics.getNumNulls() == rowCount) {
            return Domain.onlyNull(type);
        }

        boolean hasNullValue = statistics.getNumNulls() != 0L;

        // ignore corrupted statistics
        if (statistics.genericGetMin() == null || statistics.genericGetMax() == null) {
            return Domain.create(ValueSet.all(type), hasNullValue);
        }

        if (type.equals(BOOLEAN) && statistics instanceof BooleanStatistics) {
            BooleanStatistics booleanStatistics = (BooleanStatistics) statistics;

            boolean hasTrueValues = !(booleanStatistics.getMax() == false && booleanStatistics.getMin() == false);
            boolean hasFalseValues = !(booleanStatistics.getMax() == true && booleanStatistics.getMin() == true);
            if (hasTrueValues && hasFalseValues) {
                return Domain.all(type);
            }
            if (hasTrueValues) {
                return Domain.create(ValueSet.of(type, true), hasNullValue);
            }
            if (hasFalseValues) {
                return Domain.create(ValueSet.of(type, false), hasNullValue);
            }
        }
        else if ((type.equals(BIGINT) || type.equals(TINYINT) || type.equals(SMALLINT) || type.equals(INTEGER)) && (statistics instanceof LongStatistics || statistics instanceof IntStatistics)) {
            ParquetIntegerStatistics parquetIntegerStatistics;
            if (statistics instanceof LongStatistics) {
                LongStatistics longStatistics = (LongStatistics) statistics;
                // ignore corrupted statistics
                if (longStatistics.genericGetMin() > longStatistics.genericGetMax()) {
                    return Domain.create(ValueSet.all(type), hasNullValue);
                }
                parquetIntegerStatistics = new ParquetIntegerStatistics(longStatistics.genericGetMin(), longStatistics.genericGetMax());
            }
            else {
                IntStatistics intStatistics = (IntStatistics) statistics;
                // ignore corrupted statistics
                if (intStatistics.genericGetMin() > intStatistics.genericGetMax()) {
                    return Domain.create(ValueSet.all(type), hasNullValue);
                }
                parquetIntegerStatistics = new ParquetIntegerStatistics((long) intStatistics.getMin(), (long) intStatistics.getMax());
            }
            if (isStatisticsOverflow(type, parquetIntegerStatistics)) {
                return Domain.create(ValueSet.all(type), hasNullValue);
            }
            return createDomain(type, hasNullValue, parquetIntegerStatistics);
        }
        else if (type.equals(REAL) && statistics instanceof FloatStatistics) {
            FloatStatistics floatStatistics = (FloatStatistics) statistics;
            // ignore corrupted statistics
            if (floatStatistics.genericGetMin() > floatStatistics.genericGetMax()) {
                return Domain.create(ValueSet.all(type), hasNullValue);
            }

            ParquetIntegerStatistics parquetStatistics = new ParquetIntegerStatistics(
                    (long) floatToRawIntBits(floatStatistics.getMin()),
                    (long) floatToRawIntBits(floatStatistics.getMax()));

            return createDomain(type, hasNullValue, parquetStatistics);
        }
        else if (type.equals(DOUBLE) && statistics instanceof DoubleStatistics) {
            DoubleStatistics doubleStatistics = (DoubleStatistics) statistics;
            // ignore corrupted statistics
            if (doubleStatistics.genericGetMin() > doubleStatistics.genericGetMax()) {
                return Domain.create(ValueSet.all(type), hasNullValue);
            }
            ParquetDoubleStatistics parquetDoubleStatistics = new ParquetDoubleStatistics(doubleStatistics.genericGetMin(), doubleStatistics.genericGetMax());
            return createDomain(type, hasNullValue, parquetDoubleStatistics);
        }
        else if (isVarcharType(type) && statistics instanceof BinaryStatistics) {
            BinaryStatistics binaryStatistics = (BinaryStatistics) statistics;
            Slice minSlice = Slices.wrappedBuffer(binaryStatistics.getMin().getBytes());
            Slice maxSlice = Slices.wrappedBuffer(binaryStatistics.getMax().getBytes());
            // ignore corrupted statistics
            if (minSlice.compareTo(maxSlice) > 0) {
                return Domain.create(ValueSet.all(type), hasNullValue);
            }
            ParquetStringStatistics parquetStringStatistics = new ParquetStringStatistics(minSlice, maxSlice);
            return createDomain(type, hasNullValue, parquetStringStatistics);
        }
        return Domain.create(ValueSet.all(type), hasNullValue);
    }

    @VisibleForTesting
    public static Domain getDomain(Type type, ParquetDictionaryDescriptor dictionaryDescriptor)
    {
        if (dictionaryDescriptor == null) {
            return null;
        }

        ColumnDescriptor columnDescriptor = dictionaryDescriptor.getColumnDescriptor();
        Optional<ParquetDictionaryPage> dictionaryPage = dictionaryDescriptor.getDictionaryPage();
        if (!dictionaryPage.isPresent()) {
            return null;
        }

        ParquetDictionary dictionary;
        try {
            dictionary = dictionaryPage.get().getEncoding().initDictionary(columnDescriptor, dictionaryPage.get());
        }
        catch (Exception e) {
            // In case of exception, just continue reading the data, not using dictionary page at all
            // OK to ignore exception when reading dictionaries
            return null;
        }

        int dictionarySize = dictionaryPage.get().getDictionarySize();
        if (type.equals(BIGINT) && columnDescriptor.getType() == PrimitiveTypeName.INT64) {
            List<Domain> domains = new ArrayList<>();
            for (int i = 0; i < dictionarySize; i++) {
                domains.add(Domain.singleValue(type, dictionary.decodeToLong(i)));
            }
            domains.add(Domain.onlyNull(type));
            return Domain.union(domains);
        }
        else if (type.equals(BIGINT) && columnDescriptor.getType() == PrimitiveTypeName.INT32) {
            List<Domain> domains = new ArrayList<>();
            for (int i = 0; i < dictionarySize; i++) {
                domains.add(Domain.singleValue(type, (long) dictionary.decodeToInt(i)));
            }
            domains.add(Domain.onlyNull(type));
            return Domain.union(domains);
        }
        else if (type.equals(DOUBLE) && columnDescriptor.getType() == PrimitiveTypeName.DOUBLE) {
            List<Domain> domains = new ArrayList<>();
            for (int i = 0; i < dictionarySize; i++) {
                domains.add(Domain.singleValue(type, dictionary.decodeToDouble(i)));
            }
            domains.add(Domain.onlyNull(type));
            return Domain.union(domains);
        }
        else if (type.equals(DOUBLE) && columnDescriptor.getType() == PrimitiveTypeName.FLOAT) {
            List<Domain> domains = new ArrayList<>();
            for (int i = 0; i < dictionarySize; i++) {
                domains.add(Domain.singleValue(type, (double) dictionary.decodeToFloat(i)));
            }
            domains.add(Domain.onlyNull(type));
            return Domain.union(domains);
        }
        else if (isVarcharType(type) && columnDescriptor.getType() == PrimitiveTypeName.BINARY) {
            List<Domain> domains = new ArrayList<>();
            for (int i = 0; i < dictionarySize; i++) {
                domains.add(Domain.singleValue(type, Slices.wrappedBuffer(dictionary.decodeToBinary(i).getBytes())));
            }
            domains.add(Domain.onlyNull(type));
            return Domain.union(domains);
        }
        return null;
    }

    private static <T extends Comparable<T>> Domain createDomain(Type type, boolean hasNullValue, ParquetRangeStatistics<T> rangeStatistics)
    {
        return createDomain(type, hasNullValue, rangeStatistics, value -> value);
    }

    private static <F, T extends Comparable<T>> Domain createDomain(Type type,
            boolean hasNullValue,
            ParquetRangeStatistics<F> rangeStatistics,
            Function<F, T> function)
    {
        F min = rangeStatistics.getMin();
        F max = rangeStatistics.getMax();

        if (min != null && max != null) {
            return Domain.create(ValueSet.ofRanges(Range.range(type, function.apply(min), true, function.apply(max), true)), hasNullValue);
        }
        if (max != null) {
            return Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(type, function.apply(max))), hasNullValue);
        }
        if (min != null) {
            return Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(type, function.apply(min))), hasNullValue);
        }
        return Domain.create(ValueSet.all(type), hasNullValue);
    }
}
