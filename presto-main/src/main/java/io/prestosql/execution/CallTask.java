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
import io.prestosql.connector.CatalogName;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.procedure.Procedure;
import io.prestosql.spi.procedure.Procedure.Argument;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.ParameterRewriter;
import io.prestosql.sql.tree.Call;
import io.prestosql.sql.tree.CallArgument;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.transaction.TransactionManager;

import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.prestosql.metadata.MetadataUtil.createQualifiedObjectName;
import static io.prestosql.spi.StandardErrorCode.CATALOG_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static io.prestosql.spi.StandardErrorCode.INVALID_PROCEDURE_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.PROCEDURE_CALL_FAILED;
import static io.prestosql.spi.type.TypeUtils.writeNativeValue;
import static io.prestosql.sql.ParameterUtils.parameterExtractor;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;
import static io.prestosql.sql.planner.ExpressionInterpreter.evaluateConstantExpression;
import static java.util.Arrays.asList;

public class CallTask
        implements DataDefinitionTask<Call>
{
    @Override
    public String getName()
    {
        return "CALL";
    }

    @Override
    public ListenableFuture<?> execute(Call call, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        if (!transactionManager.isAutoCommit(stateMachine.getSession().getRequiredTransactionId())) {
            throw new PrestoException(NOT_SUPPORTED, "Procedures cannot be called within a transaction (use autocommit mode)");
        }

        Session session = stateMachine.getSession();
        QualifiedObjectName procedureName = createQualifiedObjectName(session, call, call.getName());
        CatalogName catalogName = metadata.getCatalogHandle(stateMachine.getSession(), procedureName.getCatalogName())
                .orElseThrow(() -> semanticException(CATALOG_NOT_FOUND, call, "Catalog %s does not exist", procedureName.getCatalogName()));
        Procedure procedure = metadata.getProcedureRegistry().resolve(catalogName, procedureName.asSchemaTableName());

        // map declared argument names to positions
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < procedure.getArguments().size(); i++) {
            positions.put(procedure.getArguments().get(i).getName(), i);
        }

        // per specification, do not allow mixing argument types
        Predicate<CallArgument> hasName = argument -> argument.getName().isPresent();
        boolean anyNamed = call.getArguments().stream().anyMatch(hasName);
        boolean allNamed = call.getArguments().stream().allMatch(hasName);
        if (anyNamed && !allNamed) {
            throw semanticException(INVALID_ARGUMENTS, call, "Named and positional arguments cannot be mixed");
        }

        // get the argument names in call order
        Map<String, CallArgument> names = new LinkedHashMap<>();
        for (int i = 0; i < call.getArguments().size(); i++) {
            CallArgument argument = call.getArguments().get(i);
            if (argument.getName().isPresent()) {
                String name = argument.getName().get();
                if (names.put(name, argument) != null) {
                    throw semanticException(INVALID_ARGUMENTS, argument, "Duplicate procedure argument: %s", name);
                }
                if (!positions.containsKey(name)) {
                    throw semanticException(INVALID_ARGUMENTS, argument, "Unknown argument name: %s", name);
                }
            }
            else if (i < procedure.getArguments().size()) {
                names.put(procedure.getArguments().get(i).getName(), argument);
            }
            else {
                throw semanticException(INVALID_ARGUMENTS, call, "Too many arguments for procedure");
            }
        }

        // verify argument count
        if (names.size() < positions.size()) {
            throw semanticException(INVALID_ARGUMENTS, call, "Too few arguments for procedure");
        }

        // get argument values
        Object[] values = new Object[procedure.getArguments().size()];
        Map<NodeRef<Parameter>, Expression> parameterLookup = parameterExtractor(call, parameters);
        for (Entry<String, CallArgument> entry : names.entrySet()) {
            CallArgument callArgument = entry.getValue();
            int index = positions.get(entry.getKey());
            Argument argument = procedure.getArguments().get(index);

            Expression expression = ExpressionTreeRewriter.rewriteWith(new ParameterRewriter(parameterLookup), callArgument.getValue());

            Type type = argument.getType();
            Object value = evaluateConstantExpression(expression, type, metadata, session, parameterLookup);

            values[index] = toTypeObjectValue(session, type, value);
        }

        // validate arguments
        MethodType methodType = procedure.getMethodHandle().type();
        for (int i = 0; i < procedure.getArguments().size(); i++) {
            if ((values[i] == null) && methodType.parameterType(i).isPrimitive()) {
                String name = procedure.getArguments().get(i).getName();
                throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Procedure argument cannot be null: " + name);
            }
        }

        // insert session argument
        List<Object> arguments = new ArrayList<>();
        Iterator<Object> valuesIterator = asList(values).iterator();
        for (Class<?> type : methodType.parameterList()) {
            if (ConnectorSession.class.isAssignableFrom(type)) {
                arguments.add(session.toConnectorSession(catalogName));
            }
            else {
                arguments.add(valuesIterator.next());
            }
        }

        try {
            procedure.getMethodHandle().invokeWithArguments(arguments);
        }
        catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throwIfInstanceOf(t, PrestoException.class);
            throw new PrestoException(PROCEDURE_CALL_FAILED, t);
        }

        return immediateFuture(null);
    }

    private static Object toTypeObjectValue(Session session, Type type, Object value)
    {
        BlockBuilder blockBuilder = type.createBlockBuilder(null, 1);
        writeNativeValue(type, blockBuilder, value);
        return type.getObjectValue(session.toConnectorSession(), blockBuilder, 0);
    }
}
