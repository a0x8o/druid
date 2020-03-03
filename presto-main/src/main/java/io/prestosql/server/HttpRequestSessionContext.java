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
package io.prestosql.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.Session.ResourceEstimateBuilder;
import io.prestosql.dispatcher.DispatcherConfig.HeaderSupport;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.SelectedRole;
import io.prestosql.spi.session.ResourceEstimates;
import io.prestosql.sql.parser.ParsingException;
import io.prestosql.sql.parser.ParsingOptions;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.transaction.TransactionId;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static com.google.common.net.HttpHeaders.X_FORWARDED_FOR;
import static io.prestosql.client.PrestoHeaders.PRESTO_CATALOG;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLIENT_CAPABILITIES;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLIENT_INFO;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLIENT_TAGS;
import static io.prestosql.client.PrestoHeaders.PRESTO_EXTRA_CREDENTIAL;
import static io.prestosql.client.PrestoHeaders.PRESTO_LANGUAGE;
import static io.prestosql.client.PrestoHeaders.PRESTO_PATH;
import static io.prestosql.client.PrestoHeaders.PRESTO_PREPARED_STATEMENT;
import static io.prestosql.client.PrestoHeaders.PRESTO_RESOURCE_ESTIMATE;
import static io.prestosql.client.PrestoHeaders.PRESTO_ROLE;
import static io.prestosql.client.PrestoHeaders.PRESTO_SCHEMA;
import static io.prestosql.client.PrestoHeaders.PRESTO_SESSION;
import static io.prestosql.client.PrestoHeaders.PRESTO_SOURCE;
import static io.prestosql.client.PrestoHeaders.PRESTO_TIME_ZONE;
import static io.prestosql.client.PrestoHeaders.PRESTO_TRACE_TOKEN;
import static io.prestosql.client.PrestoHeaders.PRESTO_TRANSACTION_ID;
import static io.prestosql.client.PrestoHeaders.PRESTO_USER;
import static io.prestosql.dispatcher.DispatcherConfig.HeaderSupport.ACCEPT;
import static io.prestosql.dispatcher.DispatcherConfig.HeaderSupport.IGNORE;
import static io.prestosql.sql.parser.ParsingOptions.DecimalLiteralTreatment.AS_DOUBLE;
import static java.lang.String.format;

public final class HttpRequestSessionContext
        implements SessionContext
{
    private static final Logger log = Logger.get(HttpRequestSessionContext.class);

    private static final Splitter DOT_SPLITTER = Splitter.on('.');

    private final String catalog;
    private final String schema;
    private final String path;

    private final Identity identity;

    private final String source;
    private final Optional<String> traceToken;
    private final String userAgent;
    private final String remoteUserAddress;
    private final String timeZoneId;
    private final String language;
    private final Set<String> clientTags;
    private final Set<String> clientCapabilities;
    private final ResourceEstimates resourceEstimates;

    private final Map<String, String> systemProperties;
    private final Map<String, Map<String, String>> catalogSessionProperties;

    private final Map<String, String> preparedStatements;

    private final Optional<TransactionId> transactionId;
    private final boolean clientTransactionSupport;
    private final String clientInfo;

    public HttpRequestSessionContext(HeaderSupport forwardedHeaderSupport, HttpServletRequest servletRequest)
            throws WebApplicationException
    {
        catalog = trimEmptyToNull(servletRequest.getHeader(PRESTO_CATALOG));
        schema = trimEmptyToNull(servletRequest.getHeader(PRESTO_SCHEMA));
        path = trimEmptyToNull(servletRequest.getHeader(PRESTO_PATH));
        assertRequest((catalog != null) || (schema == null), "Schema is set but catalog is not");

        String user = trimEmptyToNull(servletRequest.getHeader(PRESTO_USER));
        assertRequest(user != null, "User must be set");
        identity = Identity.forUser(user)
                .withPrincipal(Optional.ofNullable(servletRequest.getUserPrincipal()))
                .withRoles(parseRoleHeaders(servletRequest))
                .withExtraCredentials(parseExtraCredentials(servletRequest))
                .build();

        source = servletRequest.getHeader(PRESTO_SOURCE);
        traceToken = Optional.ofNullable(trimEmptyToNull(servletRequest.getHeader(PRESTO_TRACE_TOKEN)));
        userAgent = servletRequest.getHeader(USER_AGENT);
        remoteUserAddress = getRemoteUserAddress(forwardedHeaderSupport, servletRequest.getHeader(X_FORWARDED_FOR), servletRequest.getRemoteAddr());
        timeZoneId = servletRequest.getHeader(PRESTO_TIME_ZONE);
        language = servletRequest.getHeader(PRESTO_LANGUAGE);
        clientInfo = servletRequest.getHeader(PRESTO_CLIENT_INFO);
        clientTags = parseClientTags(servletRequest);
        clientCapabilities = parseClientCapabilities(servletRequest);
        resourceEstimates = parseResourceEstimate(servletRequest);

        // parse session properties
        ImmutableMap.Builder<String, String> systemProperties = ImmutableMap.builder();
        Map<String, Map<String, String>> catalogSessionProperties = new HashMap<>();
        for (Entry<String, String> entry : parseSessionHeaders(servletRequest).entrySet()) {
            String fullPropertyName = entry.getKey();
            String propertyValue = entry.getValue();
            List<String> nameParts = DOT_SPLITTER.splitToList(fullPropertyName);
            if (nameParts.size() == 1) {
                String propertyName = nameParts.get(0);

                assertRequest(!propertyName.isEmpty(), "Invalid %s header", PRESTO_SESSION);

                // catalog session properties can not be validated until the transaction has stated, so we delay system property validation also
                systemProperties.put(propertyName, propertyValue);
            }
            else if (nameParts.size() == 2) {
                String catalogName = nameParts.get(0);
                String propertyName = nameParts.get(1);

                assertRequest(!catalogName.isEmpty(), "Invalid %s header", PRESTO_SESSION);
                assertRequest(!propertyName.isEmpty(), "Invalid %s header", PRESTO_SESSION);

                // catalog session properties can not be validated until the transaction has stated
                catalogSessionProperties.computeIfAbsent(catalogName, id -> new HashMap<>()).put(propertyName, propertyValue);
            }
            else {
                throw badRequest(format("Invalid %s header", PRESTO_SESSION));
            }
        }
        this.systemProperties = systemProperties.build();
        this.catalogSessionProperties = catalogSessionProperties.entrySet().stream()
                .collect(toImmutableMap(Entry::getKey, entry -> ImmutableMap.copyOf(entry.getValue())));

        preparedStatements = parsePreparedStatementsHeaders(servletRequest);

        String transactionIdHeader = servletRequest.getHeader(PRESTO_TRANSACTION_ID);
        clientTransactionSupport = transactionIdHeader != null;
        transactionId = parseTransactionId(transactionIdHeader);
    }

    private static String getRemoteUserAddress(HeaderSupport forwardedHeaderSupport, String xForwarderForHeader, String remoteAddess)
    {
        // TODO support 'Forwarder' header (here & where other X-Forwarder-* are supported)

        switch (forwardedHeaderSupport) {
            case WARN:
                if (xForwarderForHeader != null) {
                    log.warn("Unsupported HTTP header '%s'. Presto needs to be explicitly configured to %s or %s this header", X_FORWARDED_FOR, IGNORE, ACCEPT);
                }
                return remoteAddess;

            case IGNORE:
                return remoteAddess;

            case ACCEPT:
                if (xForwarderForHeader != null) {
                    List<String> addresses = Splitter.on(",").trimResults().omitEmptyStrings().splitToList(xForwarderForHeader);
                    if (!addresses.isEmpty()) {
                        return addresses.get(0);
                    }
                }
                return remoteAddess;

            default:
                throw new UnsupportedOperationException("Unexpected forwardedHeaderSupport: " + forwardedHeaderSupport);
        }
    }

    @Override
    public Identity getIdentity()
    {
        return identity;
    }

    @Override
    public String getCatalog()
    {
        return catalog;
    }

    @Override
    public String getSchema()
    {
        return schema;
    }

    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public String getSource()
    {
        return source;
    }

    @Override
    public String getRemoteUserAddress()
    {
        return remoteUserAddress;
    }

    @Override
    public String getUserAgent()
    {
        return userAgent;
    }

    @Override
    public String getClientInfo()
    {
        return clientInfo;
    }

    @Override
    public Set<String> getClientTags()
    {
        return clientTags;
    }

    @Override
    public Set<String> getClientCapabilities()
    {
        return clientCapabilities;
    }

    @Override
    public ResourceEstimates getResourceEstimates()
    {
        return resourceEstimates;
    }

    @Override
    public String getTimeZoneId()
    {
        return timeZoneId;
    }

    @Override
    public String getLanguage()
    {
        return language;
    }

    @Override
    public Map<String, String> getSystemProperties()
    {
        return systemProperties;
    }

    @Override
    public Map<String, Map<String, String>> getCatalogSessionProperties()
    {
        return catalogSessionProperties;
    }

    @Override
    public Map<String, String> getPreparedStatements()
    {
        return preparedStatements;
    }

    @Override
    public Optional<TransactionId> getTransactionId()
    {
        return transactionId;
    }

    @Override
    public boolean supportClientTransaction()
    {
        return clientTransactionSupport;
    }

    @Override
    public Optional<String> getTraceToken()
    {
        return traceToken;
    }

    private static List<String> splitSessionHeader(Enumeration<String> headers)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return Collections.list(headers).stream()
                .map(splitter::splitToList)
                .flatMap(Collection::stream)
                .collect(toImmutableList());
    }

    private static Map<String, String> parseSessionHeaders(HttpServletRequest servletRequest)
    {
        return parseProperty(servletRequest, PRESTO_SESSION);
    }

    private static Map<String, SelectedRole> parseRoleHeaders(HttpServletRequest servletRequest)
    {
        ImmutableMap.Builder<String, SelectedRole> roles = ImmutableMap.builder();
        parseProperty(servletRequest, PRESTO_ROLE).forEach((key, value) -> {
            SelectedRole role;
            try {
                role = SelectedRole.valueOf(value);
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header", PRESTO_ROLE));
            }
            roles.put(key, role);
        });
        return roles.build();
    }

    private static Map<String, String> parseExtraCredentials(HttpServletRequest servletRequest)
    {
        return parseProperty(servletRequest, PRESTO_EXTRA_CREDENTIAL);
    }

    private static Map<String, String> parseProperty(HttpServletRequest servletRequest, String headerName)
    {
        Map<String, String> properties = new HashMap<>();
        for (String header : splitSessionHeader(servletRequest.getHeaders(headerName))) {
            List<String> nameValue = Splitter.on('=').trimResults().splitToList(header);
            assertRequest(nameValue.size() == 2, "Invalid %s header", headerName);
            try {
                properties.put(nameValue.get(0), urlDecode(nameValue.get(1)));
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header: %s", headerName, e));
            }
        }
        return properties;
    }

    private Set<String> parseClientTags(HttpServletRequest servletRequest)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return ImmutableSet.copyOf(splitter.split(nullToEmpty(servletRequest.getHeader(PRESTO_CLIENT_TAGS))));
    }

    private Set<String> parseClientCapabilities(HttpServletRequest servletRequest)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return ImmutableSet.copyOf(splitter.split(nullToEmpty(servletRequest.getHeader(PRESTO_CLIENT_CAPABILITIES))));
    }

    private ResourceEstimates parseResourceEstimate(HttpServletRequest servletRequest)
    {
        ResourceEstimateBuilder builder = new ResourceEstimateBuilder();
        parseProperty(servletRequest, PRESTO_RESOURCE_ESTIMATE).forEach((name, value) -> {
            try {
                switch (name.toUpperCase()) {
                    case ResourceEstimates.EXECUTION_TIME:
                        builder.setExecutionTime(Duration.valueOf(value));
                        break;
                    case ResourceEstimates.CPU_TIME:
                        builder.setCpuTime(Duration.valueOf(value));
                        break;
                    case ResourceEstimates.PEAK_MEMORY:
                        builder.setPeakMemory(DataSize.valueOf(value));
                        break;
                    default:
                        throw badRequest(format("Unsupported resource name %s", name));
                }
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Unsupported format for resource estimate '%s': %s", value, e));
            }
        });

        return builder.build();
    }

    private static void assertRequest(boolean expression, String format, Object... args)
    {
        if (!expression) {
            throw badRequest(format(format, args));
        }
    }

    private static Map<String, String> parsePreparedStatementsHeaders(HttpServletRequest servletRequest)
    {
        ImmutableMap.Builder<String, String> preparedStatements = ImmutableMap.builder();
        parseProperty(servletRequest, PRESTO_PREPARED_STATEMENT).forEach((key, sqlString) -> {
            String statementName;
            try {
                statementName = urlDecode(key);
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header: %s", PRESTO_PREPARED_STATEMENT, e.getMessage()));
            }

            // Validate statement
            SqlParser sqlParser = new SqlParser();
            try {
                sqlParser.createStatement(sqlString, new ParsingOptions(AS_DOUBLE /* anything */));
            }
            catch (ParsingException e) {
                throw badRequest(format("Invalid %s header: %s", PRESTO_PREPARED_STATEMENT, e.getMessage()));
            }

            preparedStatements.put(statementName, sqlString);
        });

        return preparedStatements.build();
    }

    private static Optional<TransactionId> parseTransactionId(String transactionId)
    {
        transactionId = trimEmptyToNull(transactionId);
        if (transactionId == null || transactionId.equalsIgnoreCase("none")) {
            return Optional.empty();
        }
        try {
            return Optional.of(TransactionId.valueOf(transactionId));
        }
        catch (Exception e) {
            throw badRequest(e.getMessage());
        }
    }

    private static WebApplicationException badRequest(String message)
    {
        throw new WebApplicationException(message, Response
                .status(Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build());
    }

    private static String trimEmptyToNull(String value)
    {
        return emptyToNull(nullToEmpty(value).trim());
    }

    private static String urlDecode(String value)
    {
        try {
            return URLDecoder.decode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
