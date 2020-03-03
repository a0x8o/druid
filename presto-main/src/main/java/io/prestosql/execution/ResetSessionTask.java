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
import io.prestosql.connector.CatalogName;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AccessControl;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ResetSession;
import io.prestosql.transaction.TransactionManager;

import java.util.List;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.prestosql.spi.StandardErrorCode.CATALOG_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.INVALID_SESSION_PROPERTY;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;

public class ResetSessionTask
        implements DataDefinitionTask<ResetSession>
{
    @Override
    public String getName()
    {
        return "RESET SESSION";
    }

    @Override
    public ListenableFuture<?> execute(ResetSession statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        List<String> parts = statement.getName().getParts();
        if (parts.size() > 2) {
            throw semanticException(INVALID_SESSION_PROPERTY, statement, "Invalid session property '%s'", statement.getName());
        }

        // validate the property name
        if (parts.size() == 1) {
            metadata.getSessionPropertyManager().getSystemSessionPropertyMetadata(parts.get(0))
                    .orElseThrow(() -> semanticException(INVALID_SESSION_PROPERTY, statement, "Session property %s does not exist", statement.getName()));
        }
        else {
            CatalogName catalogName = metadata.getCatalogHandle(stateMachine.getSession(), parts.get(0))
                    .orElseThrow(() -> semanticException(CATALOG_NOT_FOUND, statement, "Catalog %s does not exist", parts.get(0)));
            metadata.getSessionPropertyManager().getConnectorSessionPropertyMetadata(catalogName, parts.get(1))
                    .orElseThrow(() -> semanticException(INVALID_SESSION_PROPERTY, statement, "Session property %s does not exist", statement.getName()));
        }

        stateMachine.addResetSessionProperties(statement.getName().toString());

        return immediateFuture(null);
    }
}
