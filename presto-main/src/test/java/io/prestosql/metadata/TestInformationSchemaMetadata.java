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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.client.ClientCapabilities;
import io.prestosql.connector.CatalogName;
import io.prestosql.connector.MockConnectorFactory;
import io.prestosql.connector.informationschema.InformationSchemaColumnHandle;
import io.prestosql.connector.informationschema.InformationSchemaMetadata;
import io.prestosql.connector.informationschema.InformationSchemaTableHandle;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.ConstraintApplicationResult;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.testing.TestingConnectorContext;
import io.prestosql.transaction.TransactionId;
import io.prestosql.transaction.TransactionManager;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.connector.CatalogName.createInformationSchemaCatalogName;
import static io.prestosql.connector.CatalogName.createSystemTablesCatalogName;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static io.prestosql.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static java.util.Arrays.stream;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class TestInformationSchemaMetadata
{
    private final TransactionManager transactionManager;
    private final Metadata metadata;

    public TestInformationSchemaMetadata()
    {
        MockConnectorFactory.Builder builder = MockConnectorFactory.builder();
        MockConnectorFactory mockConnectorFactory = builder.withListSchemaNames(connectorSession -> ImmutableList.of("test_schema"))
                .withListTables((connectorSession, schemaNameOrNull) ->
                        ImmutableList.of(
                                new SchemaTableName("test_schema", "test_view"),
                                new SchemaTableName("test_schema", "another_table")))
                .withGetViews((connectorSession, prefix) -> {
                    ConnectorViewDefinition definition = new ConnectorViewDefinition(
                            "select 1",
                            Optional.of("test_catalog"),
                            Optional.of("test_schema"),
                            ImmutableList.of(new ViewColumn("test", BIGINT.getTypeId())),
                            Optional.empty(),
                            true);
                    SchemaTableName viewName = new SchemaTableName("test_schema", "test_view");
                    return ImmutableMap.of(viewName, definition);
                }).build();
        Connector testConnector = mockConnectorFactory.create("test", ImmutableMap.of(), new TestingConnectorContext());
        CatalogManager catalogManager = new CatalogManager();
        String catalogName = "test_catalog";
        CatalogName catalog = new CatalogName("test_catalog");
        catalogManager.registerCatalog(new Catalog(
                catalogName,
                catalog,
                testConnector,
                createInformationSchemaCatalogName(catalog),
                testConnector,
                createSystemTablesCatalogName(catalog),
                testConnector));
        transactionManager = createTestTransactionManager(catalogManager);
        metadata = createTestMetadataManager(transactionManager, new FeaturesConfig());
    }

    /**
     * Tests information schema predicate pushdown when both schema and table name are specified.
     */
    @Test
    public void testInformationSchemaPredicatePushdown()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);

        ImmutableMap.Builder<ColumnHandle, Domain> domains = new ImmutableMap.Builder<>();
        domains.put(new InformationSchemaColumnHandle("table_schema"), Domain.singleValue(VARCHAR, Slices.utf8Slice("test_schema")));
        domains.put(new InformationSchemaColumnHandle("table_name"), Domain.singleValue(VARCHAR, Slices.utf8Slice("test_view")));
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(domains.build()));

        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "views"));
        tableHandle = metadata.applyFilter(session, tableHandle, constraint)
                .map(ConstraintApplicationResult::getHandle)
                .map(InformationSchemaTableHandle.class::cast)
                .orElseThrow(AssertionError::new);
        assertEquals(tableHandle.getPrefixes(), ImmutableSet.of(new QualifiedTablePrefix("test_catalog", "test_schema", "test_view")));
    }

    @Test
    public void testInformationSchemaPredicatePushdownWithConstraintPredicate()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);
        Constraint constraint = new Constraint(TupleDomain.all(), TestInformationSchemaMetadata::testConstraint);

        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "columns"));
        tableHandle = metadata.applyFilter(session, tableHandle, constraint)
                .map(ConstraintApplicationResult::getHandle)
                .map(InformationSchemaTableHandle.class::cast)
                .orElseThrow(AssertionError::new);

        assertEquals(tableHandle.getPrefixes(), ImmutableSet.of(new QualifiedTablePrefix("test_catalog", "test_schema", "test_view")));
    }

    @Test
    public void testInformationSchemaPredicatePushdownWithoutSchemaPredicate()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);

        // predicate without schema predicates should cause schemas to be enumerated when table predicates are present
        ImmutableMap.Builder<ColumnHandle, Domain> domains = new ImmutableMap.Builder<>();
        domains.put(new InformationSchemaColumnHandle("table_name"), Domain.singleValue(VARCHAR, Slices.utf8Slice("test_view")));
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(domains.build()));

        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "views"));
        tableHandle = metadata.applyFilter(session, tableHandle, constraint)
                .map(ConstraintApplicationResult::getHandle)
                .map(InformationSchemaTableHandle.class::cast)
                .orElseThrow(AssertionError::new);
        assertEquals(tableHandle.getPrefixes(), ImmutableSet.of(new QualifiedTablePrefix("test_catalog", "test_schema", "test_view")));
    }

    @Test
    public void testInformationSchemaPredicatePushdownWithoutTablePredicate()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);

        // predicate without table name predicates should not cause table level prefixes to be evaluated
        ImmutableMap.Builder<ColumnHandle, Domain> domains = new ImmutableMap.Builder<>();
        domains.put(new InformationSchemaColumnHandle("table_schema"), Domain.singleValue(VARCHAR, Slices.utf8Slice("test_schema")));
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(domains.build()));

        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "views"));
        tableHandle = metadata.applyFilter(session, tableHandle, constraint)
                .map(ConstraintApplicationResult::getHandle)
                .map(InformationSchemaTableHandle.class::cast)
                .orElseThrow(AssertionError::new);
        assertEquals(tableHandle.getPrefixes(), ImmutableSet.of(new QualifiedTablePrefix("test_catalog", "test_schema")));
    }

    @Test
    public void testInformationSchemaPredicatePushdownWithConstraintPredicateOnViewsTable()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);

        // predicate on non columns enumerating table should not cause tables to be enumerated
        Constraint constraint = new Constraint(TupleDomain.all(), TestInformationSchemaMetadata::testConstraint);
        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "views"));
        tableHandle = metadata.applyFilter(session, tableHandle, constraint)
                .map(ConstraintApplicationResult::getHandle)
                .map(InformationSchemaTableHandle.class::cast)
                .orElseThrow(AssertionError::new);

        assertEquals(tableHandle.getPrefixes(), ImmutableSet.of(new QualifiedTablePrefix("test_catalog", "test_schema")));
    }

    @Test
    public void testInformationSchemaPredicatePushdownOnCatalogWiseTables()
    {
        TransactionId transactionId = transactionManager.beginTransaction(false);

        // Predicate pushdown shouldn't work for catalog-wise tables because the table prefixes for them are always
        // ImmutableSet.of(new QualifiedTablePrefix(catalogName));
        Constraint constraint = new Constraint(TupleDomain.all());
        ConnectorSession session = createNewSession(transactionId);
        ConnectorMetadata metadata = new InformationSchemaMetadata("test_catalog", this.metadata);
        InformationSchemaTableHandle tableHandle = (InformationSchemaTableHandle)
                metadata.getTableHandle(session, new SchemaTableName("information_schema", "schemata"));
        Optional<ConstraintApplicationResult<ConnectorTableHandle>> result = metadata.applyFilter(session, tableHandle, constraint);
        assertFalse(result.isPresent());
    }

    private static boolean testConstraint(Map<ColumnHandle, NullableValue> bindings)
    {
        // test_schema has a table named "another_table" and we filter that out in this predicate
        NullableValue catalog = bindings.get(new InformationSchemaColumnHandle("table_catalog"));
        NullableValue schema = bindings.get(new InformationSchemaColumnHandle("table_schema"));
        NullableValue table = bindings.get(new InformationSchemaColumnHandle("table_name"));
        boolean isValid = true;
        if (catalog != null) {
            isValid = ((Slice) catalog.getValue()).toStringUtf8().equals("test_catalog");
        }
        if (schema != null) {
            isValid &= ((Slice) schema.getValue()).toStringUtf8().equals("test_schema");
        }
        if (table != null) {
            isValid &= ((Slice) table.getValue()).toStringUtf8().equals("test_view");
        }
        return isValid;
    }

    private static ConnectorSession createNewSession(TransactionId transactionId)
    {
        return testSessionBuilder()
                .setCatalog("test_catalog")
                .setSchema("information_schema")
                .setClientCapabilities(stream(ClientCapabilities.values())
                        .map(ClientCapabilities::toString)
                        .collect(toImmutableSet()))
                .setTransactionId(transactionId)
                .build()
                .toConnectorSession();
    }
}
