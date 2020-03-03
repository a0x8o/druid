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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.connector.CatalogName;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.DoubleType;
import io.prestosql.spi.type.IntegerType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.planner.ParameterRewriter;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Parameter;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.StandardErrorCode.INVALID_SESSION_PROPERTY;
import static io.prestosql.spi.type.TypeUtils.writeNativeValue;
import static io.prestosql.sql.planner.ExpressionInterpreter.evaluateConstantExpression;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public final class SessionPropertyManager
{
    private static final JsonCodecFactory JSON_CODEC_FACTORY = new JsonCodecFactory();
    private final ConcurrentMap<String, PropertyMetadata<?>> systemSessionProperties = new ConcurrentHashMap<>();
    private final ConcurrentMap<CatalogName, Map<String, PropertyMetadata<?>>> connectorSessionProperties = new ConcurrentHashMap<>();

    public SessionPropertyManager()
    {
        this(new SystemSessionProperties());
    }

    @Inject
    public SessionPropertyManager(SystemSessionProperties systemSessionProperties)
    {
        this(systemSessionProperties.getSessionProperties());
    }

    public SessionPropertyManager(List<PropertyMetadata<?>> systemSessionProperties)
    {
        addSystemSessionProperties(systemSessionProperties);
    }

    public void addSystemSessionProperties(List<PropertyMetadata<?>> systemSessionProperties)
    {
        systemSessionProperties
                .forEach(this::addSystemSessionProperty);
    }

    public void addSystemSessionProperty(PropertyMetadata<?> sessionProperty)
    {
        requireNonNull(sessionProperty, "sessionProperty is null");
        checkState(systemSessionProperties.put(sessionProperty.getName(), sessionProperty) == null,
                "System session property '%s' are already registered", sessionProperty.getName());
    }

    public void addConnectorSessionProperties(CatalogName catalogName, List<PropertyMetadata<?>> properties)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(properties, "properties is null");

        Map<String, PropertyMetadata<?>> propertiesByName = Maps.uniqueIndex(properties, PropertyMetadata::getName);
        checkState(connectorSessionProperties.putIfAbsent(catalogName, propertiesByName) == null, "Session properties for catalog '%s' are already registered", catalogName);
    }

    public void removeConnectorSessionProperties(CatalogName catalogName)
    {
        connectorSessionProperties.remove(catalogName);
    }

    public Optional<PropertyMetadata<?>> getSystemSessionPropertyMetadata(String name)
    {
        requireNonNull(name, "name is null");

        return Optional.ofNullable(systemSessionProperties.get(name));
    }

    public Optional<PropertyMetadata<?>> getConnectorSessionPropertyMetadata(CatalogName catalogName, String propertyName)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(propertyName, "propertyName is null");
        Map<String, PropertyMetadata<?>> properties = connectorSessionProperties.get(catalogName);
        if (properties == null || properties.isEmpty()) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, "Unknown connector " + catalogName);
        }

        return Optional.ofNullable(properties.get(propertyName));
    }

    public List<SessionPropertyValue> getAllSessionProperties(Session session, Map<String, CatalogName> catalogs)
    {
        requireNonNull(session, "session is null");

        ImmutableList.Builder<SessionPropertyValue> sessionPropertyValues = ImmutableList.builder();
        Map<String, String> systemProperties = session.getSystemProperties();
        for (PropertyMetadata<?> property : new TreeMap<>(systemSessionProperties).values()) {
            String defaultValue = firstNonNull(property.getDefaultValue(), "").toString();
            String value = systemProperties.getOrDefault(property.getName(), defaultValue);
            sessionPropertyValues.add(new SessionPropertyValue(
                    value,
                    defaultValue,
                    property.getName(),
                    Optional.empty(),
                    property.getName(),
                    property.getDescription(),
                    property.getSqlType().getDisplayName(),
                    property.isHidden()));
        }

        for (Entry<String, CatalogName> entry : new TreeMap<>(catalogs).entrySet()) {
            String catalog = entry.getKey();
            CatalogName catalogName = entry.getValue();
            Map<String, String> connectorProperties = session.getConnectorProperties(catalogName);

            for (PropertyMetadata<?> property : new TreeMap<>(connectorSessionProperties.get(catalogName)).values()) {
                String defaultValue = firstNonNull(property.getDefaultValue(), "").toString();
                String value = connectorProperties.getOrDefault(property.getName(), defaultValue);

                sessionPropertyValues.add(new SessionPropertyValue(
                        value,
                        defaultValue,
                        catalog + "." + property.getName(),
                        Optional.of(catalog),
                        property.getName(),
                        property.getDescription(),
                        property.getSqlType().getDisplayName(),
                        property.isHidden()));
            }
        }

        return sessionPropertyValues.build();
    }

    public <T> T decodeSystemPropertyValue(String name, @Nullable String value, Class<T> type)
    {
        PropertyMetadata<?> property = getSystemSessionPropertyMetadata(name)
                .orElseThrow(() -> new PrestoException(INVALID_SESSION_PROPERTY, "Unknown session property " + name));

        return decodePropertyValue(name, value, type, property);
    }

    public <T> T decodeCatalogPropertyValue(CatalogName catalog, String catalogName, String propertyName, @Nullable String propertyValue, Class<T> type)
    {
        String fullPropertyName = catalogName + "." + propertyName;
        PropertyMetadata<?> property = getConnectorSessionPropertyMetadata(catalog, propertyName)
                .orElseThrow(() -> new PrestoException(INVALID_SESSION_PROPERTY, "Unknown session property " + fullPropertyName));

        return decodePropertyValue(fullPropertyName, propertyValue, type, property);
    }

    public void validateSystemSessionProperty(String propertyName, String propertyValue)
    {
        PropertyMetadata<?> propertyMetadata = getSystemSessionPropertyMetadata(propertyName)
                .orElseThrow(() -> new PrestoException(INVALID_SESSION_PROPERTY, "Unknown session property " + propertyName));

        decodePropertyValue(propertyName, propertyValue, propertyMetadata.getJavaType(), propertyMetadata);
    }

    public void validateCatalogSessionProperty(CatalogName catalog, String catalogName, String propertyName, String propertyValue)
    {
        String fullPropertyName = catalogName + "." + propertyName;
        PropertyMetadata<?> propertyMetadata = getConnectorSessionPropertyMetadata(catalog, propertyName)
                .orElseThrow(() -> new PrestoException(INVALID_SESSION_PROPERTY, "Unknown session property " + fullPropertyName));

        decodePropertyValue(fullPropertyName, propertyValue, propertyMetadata.getJavaType(), propertyMetadata);
    }

    private static <T> T decodePropertyValue(String fullPropertyName, @Nullable String propertyValue, Class<T> type, PropertyMetadata<?> metadata)
    {
        if (metadata.getJavaType() != type) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, format("Property %s is type %s, but requested type was %s", fullPropertyName,
                    metadata.getJavaType().getName(),
                    type.getName()));
        }
        if (propertyValue == null) {
            return type.cast(metadata.getDefaultValue());
        }
        Object objectValue = deserializeSessionProperty(metadata.getSqlType(), propertyValue);
        try {
            return type.cast(metadata.decode(objectValue));
        }
        catch (PrestoException e) {
            throw e;
        }
        catch (Exception e) {
            // the system property decoder can throw any exception
            throw new PrestoException(INVALID_SESSION_PROPERTY, format("%s is invalid: %s", fullPropertyName, propertyValue), e);
        }
    }

    public static Object evaluatePropertyValue(Expression expression, Type expectedType, Session session, Metadata metadata, Map<NodeRef<Parameter>, Expression> parameters)
    {
        Expression rewritten = ExpressionTreeRewriter.rewriteWith(new ParameterRewriter(parameters), expression);
        Object value = evaluateConstantExpression(rewritten, expectedType, metadata, session, parameters);

        // convert to object value type of SQL type
        BlockBuilder blockBuilder = expectedType.createBlockBuilder(null, 1);
        writeNativeValue(expectedType, blockBuilder, value);
        Object objectValue = expectedType.getObjectValue(session.toConnectorSession(), blockBuilder, 0);

        if (objectValue == null) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, "Session property value must not be null");
        }
        return objectValue;
    }

    public static String serializeSessionProperty(Type type, Object value)
    {
        if (value == null) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, "Session property can not be null");
        }
        if (BooleanType.BOOLEAN.equals(type)) {
            return value.toString();
        }
        if (BigintType.BIGINT.equals(type)) {
            return value.toString();
        }
        if (IntegerType.INTEGER.equals(type)) {
            return value.toString();
        }
        if (DoubleType.DOUBLE.equals(type)) {
            return value.toString();
        }
        if (VarcharType.VARCHAR.equals(type)) {
            return value.toString();
        }
        if (type instanceof ArrayType || type instanceof MapType) {
            return getJsonCodecForType(type).toJson(value);
        }
        throw new PrestoException(INVALID_SESSION_PROPERTY, format("Session property type %s is not supported", type));
    }

    private static Object deserializeSessionProperty(Type type, String value)
    {
        if (value == null) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, "Session property can not be null");
        }
        if (VarcharType.VARCHAR.equals(type)) {
            return value;
        }
        if (BooleanType.BOOLEAN.equals(type)) {
            return Boolean.valueOf(value);
        }
        if (BigintType.BIGINT.equals(type)) {
            return Long.valueOf(value);
        }
        if (IntegerType.INTEGER.equals(type)) {
            return Integer.valueOf(value);
        }
        if (DoubleType.DOUBLE.equals(type)) {
            return Double.valueOf(value);
        }
        if (type instanceof ArrayType || type instanceof MapType) {
            return getJsonCodecForType(type).fromJson(value);
        }
        throw new PrestoException(INVALID_SESSION_PROPERTY, format("Session property type %s is not supported", type));
    }

    private static <T> JsonCodec<T> getJsonCodecForType(Type type)
    {
        if (VarcharType.VARCHAR.equals(type)) {
            return (JsonCodec<T>) JSON_CODEC_FACTORY.jsonCodec(String.class);
        }
        if (BooleanType.BOOLEAN.equals(type)) {
            return (JsonCodec<T>) JSON_CODEC_FACTORY.jsonCodec(Boolean.class);
        }
        if (BigintType.BIGINT.equals(type)) {
            return (JsonCodec<T>) JSON_CODEC_FACTORY.jsonCodec(Long.class);
        }
        if (IntegerType.INTEGER.equals(type)) {
            return (JsonCodec<T>) JSON_CODEC_FACTORY.jsonCodec(Integer.class);
        }
        if (DoubleType.DOUBLE.equals(type)) {
            return (JsonCodec<T>) JSON_CODEC_FACTORY.jsonCodec(Double.class);
        }
        if (type instanceof ArrayType) {
            Type elementType = ((ArrayType) type).getElementType();
            return (JsonCodec<T>) JSON_CODEC_FACTORY.listJsonCodec(getJsonCodecForType(elementType));
        }
        if (type instanceof MapType) {
            Type keyType = ((MapType) type).getKeyType();
            Type valueType = ((MapType) type).getValueType();
            return (JsonCodec<T>) JSON_CODEC_FACTORY.mapJsonCodec(getMapKeyType(keyType), getJsonCodecForType(valueType));
        }
        throw new PrestoException(INVALID_SESSION_PROPERTY, format("Session property type %s is not supported", type));
    }

    private static Class<?> getMapKeyType(Type type)
    {
        if (VarcharType.VARCHAR.equals(type)) {
            return String.class;
        }
        if (BooleanType.BOOLEAN.equals(type)) {
            return Boolean.class;
        }
        if (BigintType.BIGINT.equals(type)) {
            return Long.class;
        }
        if (IntegerType.INTEGER.equals(type)) {
            return Integer.class;
        }
        if (DoubleType.DOUBLE.equals(type)) {
            return Double.class;
        }
        throw new PrestoException(INVALID_SESSION_PROPERTY, format("Session property map key type %s is not supported", type));
    }

    public static class SessionPropertyValue
    {
        private final String fullyQualifiedName;
        private final Optional<String> catalogName;
        private final String propertyName;
        private final String description;
        private final String type;
        private final String value;
        private final String defaultValue;
        private final boolean hidden;

        private SessionPropertyValue(String value,
                String defaultValue,
                String fullyQualifiedName,
                Optional<String> catalogName,
                String propertyName,
                String description,
                String type,
                boolean hidden)
        {
            this.fullyQualifiedName = fullyQualifiedName;
            this.catalogName = catalogName;
            this.propertyName = propertyName;
            this.description = description;
            this.type = type;
            this.value = value;
            this.defaultValue = defaultValue;
            this.hidden = hidden;
        }

        public String getFullyQualifiedName()
        {
            return fullyQualifiedName;
        }

        public Optional<String> getCatalogName()
        {
            return catalogName;
        }

        public String getPropertyName()
        {
            return propertyName;
        }

        public String getDescription()
        {
            return description;
        }

        public String getType()
        {
            return type;
        }

        public String getValue()
        {
            return value;
        }

        public String getDefaultValue()
        {
            return defaultValue;
        }

        public boolean isHidden()
        {
            return hidden;
        }
    }
}
