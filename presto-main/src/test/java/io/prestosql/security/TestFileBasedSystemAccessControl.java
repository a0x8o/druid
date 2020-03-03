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
package io.prestosql.security;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.plugin.base.security.FileBasedSystemAccessControl;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.transaction.TransactionManager;
import org.testng.annotations.Test;

import javax.security.auth.kerberos.KerberosPrincipal;

import java.io.File;
import java.util.Optional;
import java.util.Set;

import static com.google.common.io.Files.copy;
import static io.prestosql.plugin.base.security.FileBasedAccessControlConfig.SECURITY_CONFIG_FILE;
import static io.prestosql.plugin.base.security.FileBasedAccessControlConfig.SECURITY_REFRESH_PERIOD;
import static io.prestosql.spi.security.PrincipalType.USER;
import static io.prestosql.spi.security.Privilege.SELECT;
import static io.prestosql.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static io.prestosql.transaction.TransactionBuilder.transaction;
import static java.lang.Thread.sleep;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.util.Files.newTemporaryFile;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class TestFileBasedSystemAccessControl
{
    private static final Identity alice = Identity.ofUser("alice");
    private static final Identity kerberosValidAlice = Identity.forUser("alice").withPrincipal(new KerberosPrincipal("alice/example.com@EXAMPLE.COM")).build();
    private static final Identity kerberosValidNonAsciiUser = Identity.forUser("\u0194\u0194\u0194").withPrincipal(new KerberosPrincipal("\u0194\u0194\u0194/example.com@EXAMPLE.COM")).build();
    private static final Identity kerberosInvalidAlice = Identity.forUser("alice").withPrincipal(new KerberosPrincipal("mallory/example.com@EXAMPLE.COM")).build();
    private static final Identity kerberosValidShare = Identity.forUser("alice").withPrincipal(new KerberosPrincipal("valid/example.com@EXAMPLE.COM")).build();
    private static final Identity kerberosInValidShare = Identity.forUser("alice").withPrincipal(new KerberosPrincipal("invalid/example.com@EXAMPLE.COM")).build();
    private static final Identity validSpecialRegexWildDot = Identity.forUser(".*").withPrincipal(new KerberosPrincipal("special/.*@EXAMPLE.COM")).build();
    private static final Identity validSpecialRegexEndQuote = Identity.forUser("\\E").withPrincipal(new KerberosPrincipal("special/\\E@EXAMPLE.COM")).build();
    private static final Identity invalidSpecialRegex = Identity.forUser("alice").withPrincipal(new KerberosPrincipal("special/.*@EXAMPLE.COM")).build();
    private static final Identity bob = Identity.ofUser("bob");
    private static final Identity admin = Identity.ofUser("admin");
    private static final Identity nonAsciiUser = Identity.ofUser("\u0194\u0194\u0194");
    private static final Set<String> allCatalogs = ImmutableSet.of("secret", "open-to-all", "all-allowed", "alice-catalog", "\u0200\u0200\u0200");
    private static final QualifiedObjectName aliceTable = new QualifiedObjectName("alice-catalog", "schema", "table");
    private static final QualifiedObjectName aliceView = new QualifiedObjectName("alice-catalog", "schema", "view");
    private static final CatalogSchemaName aliceSchema = new CatalogSchemaName("alice-catalog", "schema");

    @Test
    public void testCanSetUserOperations()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog_principal.json");

        try {
            accessControlManager.checkCanSetUser(Optional.empty(), alice.getUser());
            throw new AssertionError("expected AccessDeniedExeption");
        }
        catch (AccessDeniedException expected) {
        }

        accessControlManager.checkCanSetUser(kerberosValidAlice.getPrincipal(), kerberosValidAlice.getUser());
        accessControlManager.checkCanSetUser(kerberosValidNonAsciiUser.getPrincipal(), kerberosValidNonAsciiUser.getUser());
        try {
            accessControlManager.checkCanSetUser(kerberosInvalidAlice.getPrincipal(), kerberosInvalidAlice.getUser());
            throw new AssertionError("expected AccessDeniedExeption");
        }
        catch (AccessDeniedException expected) {
        }

        accessControlManager.checkCanSetUser(kerberosValidShare.getPrincipal(), kerberosValidShare.getUser());
        try {
            accessControlManager.checkCanSetUser(kerberosInValidShare.getPrincipal(), kerberosInValidShare.getUser());
            throw new AssertionError("expected AccessDeniedExeption");
        }
        catch (AccessDeniedException expected) {
        }

        accessControlManager.checkCanSetUser(validSpecialRegexWildDot.getPrincipal(), validSpecialRegexWildDot.getUser());
        accessControlManager.checkCanSetUser(validSpecialRegexEndQuote.getPrincipal(), validSpecialRegexEndQuote.getUser());
        try {
            accessControlManager.checkCanSetUser(invalidSpecialRegex.getPrincipal(), invalidSpecialRegex.getUser());
            throw new AssertionError("expected AccessDeniedExeption");
        }
        catch (AccessDeniedException expected) {
        }

        AccessControlManager accessControlManagerNoPatterns = newAccessControlManager(transactionManager, "catalog.json");
        accessControlManagerNoPatterns.checkCanSetUser(kerberosValidAlice.getPrincipal(), kerberosValidAlice.getUser());
    }

    @Test
    public void testCatalogOperations()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    assertEquals(accessControlManager.filterCatalogs(admin, allCatalogs), allCatalogs);
                    Set<String> aliceCatalogs = ImmutableSet.of("open-to-all", "alice-catalog", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(alice, allCatalogs), aliceCatalogs);
                    Set<String> bobCatalogs = ImmutableSet.of("open-to-all", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(bob, allCatalogs), bobCatalogs);
                    Set<String> nonAsciiUserCatalogs = ImmutableSet.of("open-to-all", "all-allowed", "\u0200\u0200\u0200");
                    assertEquals(accessControlManager.filterCatalogs(nonAsciiUser, allCatalogs), nonAsciiUserCatalogs);
                });
    }

    @Test
    public void testCatalogOperationsReadOnly()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog_read_only.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    assertEquals(accessControlManager.filterCatalogs(admin, allCatalogs), allCatalogs);
                    Set<String> aliceCatalogs = ImmutableSet.of("open-to-all", "alice-catalog", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(alice, allCatalogs), aliceCatalogs);
                    Set<String> bobCatalogs = ImmutableSet.of("open-to-all", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(bob, allCatalogs), bobCatalogs);
                    Set<String> nonAsciiUserCatalogs = ImmutableSet.of("open-to-all", "all-allowed", "\u0200\u0200\u0200");
                    assertEquals(accessControlManager.filterCatalogs(nonAsciiUser, allCatalogs), nonAsciiUserCatalogs);
                });
    }

    @Test
    public void testSchemaOperations()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<String> aliceSchemas = ImmutableSet.of("schema");
                    assertEquals(accessControlManager.filterSchemas(new SecurityContext(transactionId, alice), "alice-catalog", aliceSchemas), aliceSchemas);
                    assertEquals(accessControlManager.filterSchemas(new SecurityContext(transactionId, bob), "alice-catalog", aliceSchemas), ImmutableSet.of());

                    accessControlManager.checkCanCreateSchema(new SecurityContext(transactionId, alice), aliceSchema);
                    accessControlManager.checkCanDropSchema(new SecurityContext(transactionId, alice), aliceSchema);
                    accessControlManager.checkCanRenameSchema(new SecurityContext(transactionId, alice), aliceSchema, "new-schema");
                    accessControlManager.checkCanShowSchemas(new SecurityContext(transactionId, alice), "alice-catalog");
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateSchema(new SecurityContext(transactionId, bob), aliceSchema);
        }));
    }

    @Test
    public void testSchemaOperationsReadOnly()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog_read_only.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<String> aliceSchemas = ImmutableSet.of("schema");
                    assertEquals(accessControlManager.filterSchemas(new SecurityContext(transactionId, alice), "alice-catalog", aliceSchemas), aliceSchemas);
                    assertEquals(accessControlManager.filterSchemas(new SecurityContext(transactionId, bob), "alice-catalog", aliceSchemas), ImmutableSet.of());

                    accessControlManager.checkCanShowSchemas(new SecurityContext(transactionId, alice), "alice-catalog");
                });

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateSchema(new SecurityContext(transactionId, alice), aliceSchema);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanDropSchema(new SecurityContext(transactionId, alice), aliceSchema);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanRenameSchema(new SecurityContext(transactionId, alice), aliceSchema, "new-schema");
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateSchema(new SecurityContext(transactionId, bob), aliceSchema);
        }));
    }

    @Test
    public void testTableOperations()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<SchemaTableName> aliceTables = ImmutableSet.of(new SchemaTableName("schema", "table"));
                    assertEquals(accessControlManager.filterTables(new SecurityContext(transactionId, alice), "alice-catalog", aliceTables), aliceTables);
                    assertEquals(accessControlManager.filterTables(new SecurityContext(transactionId, bob), "alice-catalog", aliceTables), ImmutableSet.of());

                    accessControlManager.checkCanCreateTable(new SecurityContext(transactionId, alice), aliceTable);
                    accessControlManager.checkCanDropTable(new SecurityContext(transactionId, alice), aliceTable);
                    accessControlManager.checkCanSelectFromColumns(new SecurityContext(transactionId, alice), aliceTable, ImmutableSet.of());
                    accessControlManager.checkCanInsertIntoTable(new SecurityContext(transactionId, alice), aliceTable);
                    accessControlManager.checkCanDeleteFromTable(new SecurityContext(transactionId, alice), aliceTable);
                    accessControlManager.checkCanAddColumns(new SecurityContext(transactionId, alice), aliceTable);
                    accessControlManager.checkCanRenameColumn(new SecurityContext(transactionId, alice), aliceTable);
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateTable(new SecurityContext(transactionId, bob), aliceTable);
        }));
    }

    @Test
    public void testTableOperationsReadOnly()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog_read_only.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<SchemaTableName> aliceTables = ImmutableSet.of(new SchemaTableName("schema", "table"));
                    assertEquals(accessControlManager.filterTables(new SecurityContext(transactionId, alice), "alice-catalog", aliceTables), aliceTables);
                    assertEquals(accessControlManager.filterTables(new SecurityContext(transactionId, bob), "alice-catalog", aliceTables), ImmutableSet.of());

                    accessControlManager.checkCanSelectFromColumns(new SecurityContext(transactionId, alice), aliceTable, ImmutableSet.of());
                });

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateTable(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanDropTable(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanInsertIntoTable(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanDeleteFromTable(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanAddColumns(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanRenameColumn(new SecurityContext(transactionId, alice), aliceTable);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateTable(new SecurityContext(transactionId, bob), aliceTable);
        }));
    }

    @Test
    public void testViewOperations()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                    accessControlManager.checkCanDropView(new SecurityContext(transactionId, alice), aliceView);
                    accessControlManager.checkCanSelectFromColumns(new SecurityContext(transactionId, alice), aliceView, ImmutableSet.of());
                    accessControlManager.checkCanCreateViewWithSelectFromColumns(new SecurityContext(transactionId, alice), aliceTable, ImmutableSet.of());
                    accessControlManager.checkCanCreateViewWithSelectFromColumns(new SecurityContext(transactionId, alice), aliceView, ImmutableSet.of());
                    accessControlManager.checkCanSetCatalogSessionProperty(transactionId, alice, "alice-catalog", "property");
                    accessControlManager.checkCanGrantTablePrivilege(new SecurityContext(transactionId, alice), SELECT, aliceTable, new PrestoPrincipal(USER, "grantee"), true);
                    accessControlManager.checkCanRevokeTablePrivilege(new SecurityContext(transactionId, alice), SELECT, aliceTable, new PrestoPrincipal(USER, "revokee"), true);
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateView(new SecurityContext(transactionId, bob), aliceView);
        }));
    }

    @Test
    public void testViewOperationsReadOnly()
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = newAccessControlManager(transactionManager, "catalog_read_only.json");

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanSelectFromColumns(new SecurityContext(transactionId, alice), aliceView, ImmutableSet.of());
                    accessControlManager.checkCanSetCatalogSessionProperty(transactionId, alice, "alice-catalog", "property");
                });

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanDropView(new SecurityContext(transactionId, alice), aliceView);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateViewWithSelectFromColumns(new SecurityContext(transactionId, alice), aliceTable, ImmutableSet.of());
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateViewWithSelectFromColumns(new SecurityContext(transactionId, alice), aliceView, ImmutableSet.of());
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanGrantTablePrivilege(new SecurityContext(transactionId, alice), SELECT, aliceTable, new PrestoPrincipal(USER, "grantee"), true);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanRevokeTablePrivilege(new SecurityContext(transactionId, alice), SELECT, aliceTable, new PrestoPrincipal(USER, "revokee"), true);
        }));

        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateView(new SecurityContext(transactionId, bob), aliceView);
        }));
    }

    @Test
    public void testRefreshing()
            throws Exception
    {
        TransactionManager transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager = new AccessControlManager(transactionManager);
        File configFile = newTemporaryFile();
        configFile.deleteOnExit();
        copy(new File(getResourcePath("catalog.json")), configFile);

        accessControlManager.setSystemAccessControl(FileBasedSystemAccessControl.NAME, ImmutableMap.of(
                SECURITY_CONFIG_FILE, configFile.getAbsolutePath(),
                SECURITY_REFRESH_PERIOD, "1ms"));

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                });

        copy(new File(getResourcePath("security-config-file-with-unknown-rules.json")), configFile);
        sleep(2);

        assertThatThrownBy(() -> transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid JSON file");
        // test if file based cached control was not cached somewhere
        assertThatThrownBy(() -> transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid JSON file");

        copy(new File(getResourcePath("catalog.json")), configFile);
        sleep(2);

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(new SecurityContext(transactionId, alice), aliceView);
                });
    }

    @Test
    public void testAllowModeIsRequired()
    {
        assertThrows(IllegalArgumentException.class, () -> newAccessControlManager(createTestTransactionManager(), "catalog_allow_unset.json"));
    }

    @Test
    public void testAllowModeInvalidValue()
    {
        assertThrows(IllegalArgumentException.class, () -> newAccessControlManager(createTestTransactionManager(), "catalog_invalid_allow_value.json"));
    }

    private AccessControlManager newAccessControlManager(TransactionManager transactionManager, String resourceName)
    {
        AccessControlManager accessControlManager = new AccessControlManager(transactionManager);

        accessControlManager.setSystemAccessControl(FileBasedSystemAccessControl.NAME, ImmutableMap.of("security.config-file", getResourcePath(resourceName)));

        return accessControlManager;
    }

    private String getResourcePath(String resourceName)
    {
        return this.getClass().getClassLoader().getResource(resourceName).getPath();
    }

    @Test
    public void parseUnknownRules()
    {
        assertThatThrownBy(() -> parse("src/test/resources/security-config-file-with-unknown-rules.json"))
                .hasMessageContaining("Invalid JSON");
    }

    private void parse(String path)
    {
        new FileBasedSystemAccessControl.Factory().create(ImmutableMap.of(SECURITY_CONFIG_FILE, path));
    }
}
