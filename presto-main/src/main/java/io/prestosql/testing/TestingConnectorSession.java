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
package io.prestosql.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.prestosql.execution.QueryIdGenerator;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.security.ConnectorIdentity;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.TimeZoneKey;
import io.prestosql.sql.analyzer.FeaturesConfig;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.prestosql.spi.StandardErrorCode.INVALID_SESSION_PROPERTY;
import static io.prestosql.spi.type.TimeZoneKey.UTC_KEY;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class TestingConnectorSession
        implements ConnectorSession
{
    private static final QueryIdGenerator queryIdGenerator = new QueryIdGenerator();
    public static final ConnectorSession SESSION = new TestingConnectorSession(ImmutableList.of());

    private final String queryId;
    private final ConnectorIdentity identity;
    private final Optional<String> source;
    private final TimeZoneKey timeZoneKey;
    private final Locale locale;
    private final Optional<String> traceToken;
    private final long startTime;
    private final Map<String, PropertyMetadata<?>> properties;
    private final Map<String, Object> propertyValues;
    private final boolean isLegacyTimestamp;

    public TestingConnectorSession(List<PropertyMetadata<?>> properties)
    {
        this(properties, ImmutableMap.of());
    }

    public TestingConnectorSession(List<PropertyMetadata<?>> properties, Map<String, Object> propertyValues)
    {
        this("user", Optional.of("test"), Optional.empty(), UTC_KEY, ENGLISH, System.currentTimeMillis(), properties, propertyValues, new FeaturesConfig().isLegacyTimestamp());
    }

    public TestingConnectorSession(
            String user,
            Optional<String> source,
            Optional<String> traceToken,
            TimeZoneKey timeZoneKey,
            Locale locale,
            long startTime,
            List<PropertyMetadata<?>> propertyMetadatas,
            Map<String, Object> propertyValues,
            boolean isLegacyTimestamp)
    {
        this.queryId = queryIdGenerator.createNextQueryId().toString();
        this.identity = new ConnectorIdentity(requireNonNull(user, "user is null"), Optional.empty(), Optional.empty());
        this.source = requireNonNull(source, "source is null");
        this.traceToken = requireNonNull(traceToken, "traceToken is null");
        this.timeZoneKey = requireNonNull(timeZoneKey, "timeZoneKey is null");
        this.locale = requireNonNull(locale, "locale is null");
        this.startTime = startTime;
        this.properties = Maps.uniqueIndex(propertyMetadatas, PropertyMetadata::getName);
        this.propertyValues = ImmutableMap.copyOf(propertyValues);
        this.isLegacyTimestamp = isLegacyTimestamp;
    }

    @Override
    public String getQueryId()
    {
        return queryId;
    }

    @Override
    public Optional<String> getSource()
    {
        return source;
    }

    @Override
    public ConnectorIdentity getIdentity()
    {
        return identity;
    }

    @Override
    public TimeZoneKey getTimeZoneKey()
    {
        return timeZoneKey;
    }

    @Override
    public Locale getLocale()
    {
        return locale;
    }

    @Override
    public long getStartTime()
    {
        return startTime;
    }

    @Override
    public Optional<String> getTraceToken()
    {
        return traceToken;
    }

    @Override
    public boolean isLegacyTimestamp()
    {
        return isLegacyTimestamp;
    }

    @Override
    public <T> T getProperty(String name, Class<T> type)
    {
        PropertyMetadata<?> metadata = properties.get(name);
        if (metadata == null) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, "Unknown session property " + name);
        }
        Object value = propertyValues.get(name);
        if (value == null) {
            return type.cast(metadata.getDefaultValue());
        }
        return type.cast(metadata.decode(value));
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("user", getUser())
                .add("source", source.orElse(null))
                .add("traceToken", traceToken.orElse(null))
                .add("timeZoneKey", timeZoneKey)
                .add("locale", locale)
                .add("startTime", startTime)
                .add("properties", propertyValues)
                .omitNullValues()
                .toString();
    }
}
