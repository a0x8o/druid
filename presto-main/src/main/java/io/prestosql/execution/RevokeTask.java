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
package io.prestosql.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.TableHandle;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.security.Privilege;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.Revoke;
import io.prestosql.transaction.TransactionManager;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.prestosql.metadata.MetadataUtil.createPrincipal;
import static io.prestosql.metadata.MetadataUtil.createQualifiedObjectName;
import static io.prestosql.spi.StandardErrorCode.INVALID_PRIVILEGE;
import static io.prestosql.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;

public class RevokeTask
        implements DataDefinitionTask<Revoke>
{
    @Override
    public String getName()
    {
        return "REVOKE";
    }

    @Override
    public ListenableFuture<?> execute(Revoke statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        Session session = stateMachine.getSession();
        QualifiedObjectName tableName = createQualifiedObjectName(session, statement, statement.getTableName());
        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableName);
        if (!tableHandle.isPresent()) {
            throw semanticException(TABLE_NOT_FOUND, statement, "Table '%s' does not exist", tableName);
        }

        Set<Privilege> privileges;
        if (statement.getPrivileges().isPresent()) {
            privileges = statement.getPrivileges().get().stream()
                    .map(privilege -> parsePrivilege(statement, privilege))
                    .collect(toImmutableSet());
        }
        else {
            // All privileges
            privileges = EnumSet.allOf(Privilege.class);
        }

        // verify current identity has permissions to revoke permissions
        for (Privilege privilege : privileges) {
            accessControl.checkCanRevokeTablePrivilege(session.toSecurityContext(), privilege, tableName, createPrincipal(statement.getGrantee()), statement.isGrantOptionFor());
        }

        metadata.revokeTablePrivileges(session, tableName, privileges, createPrincipal(statement.getGrantee()), statement.isGrantOptionFor());
        return immediateFuture(null);
    }

    private static Privilege parsePrivilege(Revoke statement, String privilegeString)
    {
        for (Privilege privilege : Privilege.values()) {
            if (privilege.name().equalsIgnoreCase(privilegeString)) {
                return privilege;
            }
        }

        throw semanticException(INVALID_PRIVILEGE, statement, "Unknown privilege: '%s'", privilegeString);
    }
}
