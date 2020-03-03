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
package io.prestosql.sql.analyzer;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.FunctionKind;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.NewTableLayout;
import io.prestosql.metadata.OperatorNotFoundException;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.TableHandle;
import io.prestosql.metadata.TableMetadata;
import io.prestosql.security.AccessControl;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.security.ViewAccessControl;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.PrestoWarning;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeNotFoundException;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.parser.ParsingException;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.ExpressionInterpreter;
import io.prestosql.sql.planner.SymbolsExtractor;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.tree.AddColumn;
import io.prestosql.sql.tree.AliasedRelation;
import io.prestosql.sql.tree.AllColumns;
import io.prestosql.sql.tree.Analyze;
import io.prestosql.sql.tree.Call;
import io.prestosql.sql.tree.Comment;
import io.prestosql.sql.tree.Commit;
import io.prestosql.sql.tree.CreateSchema;
import io.prestosql.sql.tree.CreateTable;
import io.prestosql.sql.tree.CreateTableAsSelect;
import io.prestosql.sql.tree.CreateView;
import io.prestosql.sql.tree.Cube;
import io.prestosql.sql.tree.Deallocate;
import io.prestosql.sql.tree.DefaultTraversalVisitor;
import io.prestosql.sql.tree.Delete;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.DropColumn;
import io.prestosql.sql.tree.DropSchema;
import io.prestosql.sql.tree.DropTable;
import io.prestosql.sql.tree.DropView;
import io.prestosql.sql.tree.Except;
import io.prestosql.sql.tree.Execute;
import io.prestosql.sql.tree.Explain;
import io.prestosql.sql.tree.ExplainType;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.FetchFirst;
import io.prestosql.sql.tree.FieldReference;
import io.prestosql.sql.tree.FrameBound;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.Grant;
import io.prestosql.sql.tree.GroupBy;
import io.prestosql.sql.tree.GroupingElement;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.GroupingSets;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.Insert;
import io.prestosql.sql.tree.Intersect;
import io.prestosql.sql.tree.Join;
import io.prestosql.sql.tree.JoinCriteria;
import io.prestosql.sql.tree.JoinOn;
import io.prestosql.sql.tree.JoinUsing;
import io.prestosql.sql.tree.Lateral;
import io.prestosql.sql.tree.Limit;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.NaturalJoin;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Offset;
import io.prestosql.sql.tree.OrderBy;
import io.prestosql.sql.tree.Prepare;
import io.prestosql.sql.tree.Property;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.QuerySpecification;
import io.prestosql.sql.tree.Relation;
import io.prestosql.sql.tree.RenameColumn;
import io.prestosql.sql.tree.RenameSchema;
import io.prestosql.sql.tree.RenameTable;
import io.prestosql.sql.tree.ResetSession;
import io.prestosql.sql.tree.Revoke;
import io.prestosql.sql.tree.Rollback;
import io.prestosql.sql.tree.Rollup;
import io.prestosql.sql.tree.Row;
import io.prestosql.sql.tree.SampledRelation;
import io.prestosql.sql.tree.Select;
import io.prestosql.sql.tree.SelectItem;
import io.prestosql.sql.tree.SetOperation;
import io.prestosql.sql.tree.SetSession;
import io.prestosql.sql.tree.SimpleGroupBy;
import io.prestosql.sql.tree.SingleColumn;
import io.prestosql.sql.tree.SortItem;
import io.prestosql.sql.tree.StartTransaction;
import io.prestosql.sql.tree.Statement;
import io.prestosql.sql.tree.Table;
import io.prestosql.sql.tree.TableSubquery;
import io.prestosql.sql.tree.Unnest;
import io.prestosql.sql.tree.Use;
import io.prestosql.sql.tree.Values;
import io.prestosql.sql.tree.Window;
import io.prestosql.sql.tree.WindowFrame;
import io.prestosql.sql.tree.With;
import io.prestosql.sql.tree.WithQuery;
import io.prestosql.sql.util.AstUtils;
import io.prestosql.type.TypeCoercion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getLast;
import static io.prestosql.SystemSessionProperties.getMaxGroupingSets;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.FunctionKind.WINDOW;
import static io.prestosql.metadata.MetadataUtil.createQualifiedObjectName;
import static io.prestosql.spi.StandardErrorCode.AMBIGUOUS_NAME;
import static io.prestosql.spi.StandardErrorCode.CATALOG_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.COLUMN_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.COLUMN_TYPE_UNKNOWN;
import static io.prestosql.spi.StandardErrorCode.DUPLICATE_COLUMN_NAME;
import static io.prestosql.spi.StandardErrorCode.DUPLICATE_NAMED_QUERY;
import static io.prestosql.spi.StandardErrorCode.DUPLICATE_PROPERTY;
import static io.prestosql.spi.StandardErrorCode.EXPRESSION_NOT_CONSTANT;
import static io.prestosql.spi.StandardErrorCode.EXPRESSION_NOT_IN_DISTINCT;
import static io.prestosql.spi.StandardErrorCode.FUNCTION_NOT_WINDOW;
import static io.prestosql.spi.StandardErrorCode.INVALID_COLUMN_REFERENCE;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.StandardErrorCode.INVALID_VIEW;
import static io.prestosql.spi.StandardErrorCode.INVALID_WINDOW_FRAME;
import static io.prestosql.spi.StandardErrorCode.MISMATCHED_COLUMN_ALIASES;
import static io.prestosql.spi.StandardErrorCode.MISSING_COLUMN_NAME;
import static io.prestosql.spi.StandardErrorCode.MISSING_GROUP_BY;
import static io.prestosql.spi.StandardErrorCode.MISSING_ORDER_BY;
import static io.prestosql.spi.StandardErrorCode.NESTED_WINDOW;
import static io.prestosql.spi.StandardErrorCode.NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.NULL_TREATMENT_NOT_ALLOWED;
import static io.prestosql.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE;
import static io.prestosql.spi.StandardErrorCode.SCHEMA_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.TABLE_ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.TOO_MANY_GROUPING_SETS;
import static io.prestosql.spi.StandardErrorCode.TYPE_MISMATCH;
import static io.prestosql.spi.StandardErrorCode.VIEW_IS_RECURSIVE;
import static io.prestosql.spi.StandardErrorCode.VIEW_IS_STALE;
import static io.prestosql.spi.connector.StandardWarningCode.REDUNDANT_ORDER_BY;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.NodeUtils.getSortItemsFromOrderBy;
import static io.prestosql.sql.NodeUtils.mapFromProperties;
import static io.prestosql.sql.ParsingUtil.createParsingOptions;
import static io.prestosql.sql.analyzer.AggregationAnalyzer.verifyOrderByAggregations;
import static io.prestosql.sql.analyzer.AggregationAnalyzer.verifySourceAggregations;
import static io.prestosql.sql.analyzer.ExpressionAnalyzer.createConstantAnalyzer;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractAggregateFunctions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractExpressions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractWindowFunctions;
import static io.prestosql.sql.analyzer.ScopeReferenceExtractor.hasReferencesToScope;
import static io.prestosql.sql.analyzer.SemanticExceptions.semanticException;
import static io.prestosql.sql.analyzer.TypeSignatureProvider.fromTypeSignatures;
import static io.prestosql.sql.planner.DeterminismEvaluator.isDeterministic;
import static io.prestosql.sql.planner.ExpressionInterpreter.expressionOptimizer;
import static io.prestosql.sql.tree.ExplainType.Type.DISTRIBUTED;
import static io.prestosql.sql.tree.FrameBound.Type.CURRENT_ROW;
import static io.prestosql.sql.tree.FrameBound.Type.FOLLOWING;
import static io.prestosql.sql.tree.FrameBound.Type.PRECEDING;
import static io.prestosql.sql.tree.FrameBound.Type.UNBOUNDED_FOLLOWING;
import static io.prestosql.sql.tree.FrameBound.Type.UNBOUNDED_PRECEDING;
import static io.prestosql.sql.tree.WindowFrame.Type.RANGE;
import static io.prestosql.type.UnknownType.UNKNOWN;
import static io.prestosql.util.MoreLists.mappedCopy;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

class StatementAnalyzer
{
    private static final Set<String> WINDOW_VALUE_FUNCTIONS = ImmutableSet.of("lead", "lag", "first_value", "last_value", "nth_value");

    private final Analysis analysis;
    private final Metadata metadata;
    private final TypeCoercion typeCoercion;
    private final Session session;
    private final SqlParser sqlParser;
    private final AccessControl accessControl;
    private final WarningCollector warningCollector;

    public StatementAnalyzer(
            Analysis analysis,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Session session,
            WarningCollector warningCollector)
    {
        this.analysis = requireNonNull(analysis, "analysis is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeCoercion = new TypeCoercion(metadata::getType);
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.session = requireNonNull(session, "session is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    public Scope analyze(Node node, Scope outerQueryScope)
    {
        return analyze(node, Optional.of(outerQueryScope));
    }

    public Scope analyze(Node node, Optional<Scope> outerQueryScope)
    {
        return new Visitor(outerQueryScope, warningCollector).process(node, Optional.empty());
    }

    /**
     * Visitor context represents local query scope (if exists). The invariant is
     * that the local query scopes hierarchy should always have outer query scope
     * (if provided) as ancestor.
     */
    private class Visitor
            extends DefaultTraversalVisitor<Scope, Optional<Scope>>
    {
        private final Optional<Scope> outerQueryScope;
        private final WarningCollector warningCollector;

        private Visitor(Optional<Scope> outerQueryScope, WarningCollector warningCollector)
        {
            this.outerQueryScope = requireNonNull(outerQueryScope, "outerQueryScope is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        }

        @Override
        public Scope process(Node node, Optional<Scope> scope)
        {
            Scope returnScope = super.process(node, scope);
            checkState(returnScope.getOuterQueryParent().equals(outerQueryScope), "result scope should have outer query scope equal with parameter outer query scope");
            if (scope.isPresent()) {
                checkState(hasScopeAsLocalParent(returnScope, scope.get()), "return scope should have context scope as one of its ancestors");
            }
            return returnScope;
        }

        private Scope process(Node node, Scope scope)
        {
            return process(node, Optional.of(scope));
        }

        @Override
        protected Scope visitUse(Use node, Optional<Scope> scope)
        {
            throw semanticException(NOT_SUPPORTED, node, "USE statement is not supported");
        }

        @Override
        protected Scope visitInsert(Insert insert, Optional<Scope> scope)
        {
            QualifiedObjectName targetTable = createQualifiedObjectName(session, insert, insert.getTarget());
            if (metadata.getView(session, targetTable).isPresent()) {
                throw semanticException(NOT_SUPPORTED, insert, "Inserting into views is not supported");
            }

            // analyze the query that creates the data
            Scope queryScope = process(insert.getQuery(), scope);

            analysis.setUpdateType("INSERT");

            // verify the insert destination columns match the query
            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
            if (!targetTableHandle.isPresent()) {
                throw semanticException(TABLE_NOT_FOUND, insert, "Table '%s' does not exist", targetTable);
            }
            accessControl.checkCanInsertIntoTable(session.toSecurityContext(), targetTable);

            TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTableHandle.get());
            List<String> tableColumns = tableMetadata.getColumns().stream()
                    .filter(column -> !column.isHidden())
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableList());

            // analyze target table layout
            Optional<NewTableLayout> newTableLayout = metadata.getInsertLayout(session, targetTableHandle.get());
            newTableLayout.ifPresent(layout -> {
                if (!ImmutableSet.copyOf(tableColumns).containsAll(layout.getPartitionColumns())) {
                    throw new PrestoException(NOT_SUPPORTED, "INSERT must write all distribution columns: " + layout.getPartitionColumns());
                }
            });

            List<String> insertColumns;
            if (insert.getColumns().isPresent()) {
                insertColumns = insert.getColumns().get().stream()
                        .map(Identifier::getValue)
                        .map(column -> column.toLowerCase(ENGLISH))
                        .collect(toImmutableList());

                Set<String> columnNames = new HashSet<>();
                for (String insertColumn : insertColumns) {
                    if (!tableColumns.contains(insertColumn)) {
                        throw semanticException(COLUMN_NOT_FOUND, insert, "Insert column name does not exist in target table: %s", insertColumn);
                    }
                    if (!columnNames.add(insertColumn)) {
                        throw semanticException(DUPLICATE_COLUMN_NAME, insert, "Insert column name is specified more than once: %s", insertColumn);
                    }
                }
            }
            else {
                insertColumns = tableColumns;
            }

            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTableHandle.get());
            analysis.setInsert(new Analysis.Insert(
                    targetTableHandle.get(),
                    insertColumns.stream().map(columnHandles::get).collect(toImmutableList()),
                    newTableLayout));

            List<Type> tableTypes = insertColumns.stream()
                    .map(insertColumn -> tableMetadata.getColumn(insertColumn).getType())
                    .collect(toImmutableList());

            List<Type> queryTypes = queryScope.getRelationType().getVisibleFields().stream()
                    .map(Field::getType)
                    .collect(toImmutableList());

            if (!typesMatchForInsert(tableTypes, queryTypes)) {
                throw semanticException(TYPE_MISMATCH, insert, "Insert query has mismatched column types: " +
                        "Table: [" + Joiner.on(", ").join(tableTypes) + "], " +
                        "Query: [" + Joiner.on(", ").join(queryTypes) + "]");
            }

            return createAndAssignScope(insert, scope, Field.newUnqualified("rows", BIGINT));
        }

        private boolean typesMatchForInsert(List<Type> tableTypes, List<Type> queryTypes)
        {
            if (tableTypes.size() != queryTypes.size()) {
                return false;
            }

            for (int i = 0; i < tableTypes.size(); i++) {
                if (!typeCoercion.canCoerce(queryTypes.get(i), tableTypes.get(i))) {
                    return false;
                }
            }

            return true;
        }

        @Override
        protected Scope visitDelete(Delete node, Optional<Scope> scope)
        {
            Table table = node.getTable();
            QualifiedObjectName tableName = createQualifiedObjectName(session, table, table.getName());
            if (metadata.getView(session, tableName).isPresent()) {
                throw semanticException(NOT_SUPPORTED, node, "Deleting from views is not supported");
            }

            // Analyzer checks for select permissions but DELETE has a separate permission, so disable access checks
            // TODO: we shouldn't need to create a new analyzer. The access control should be carried in the context object
            StatementAnalyzer analyzer = new StatementAnalyzer(
                    analysis,
                    metadata,
                    sqlParser,
                    new AllowAllAccessControl(),
                    session,
                    warningCollector);

            Scope tableScope = analyzer.analyze(table, scope);
            node.getWhere().ifPresent(where -> analyzeWhere(node, tableScope, where));

            analysis.setUpdateType("DELETE");

            accessControl.checkCanDeleteFromTable(session.toSecurityContext(), tableName);

            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitAnalyze(Analyze node, Optional<Scope> scope)
        {
            analysis.setUpdateType("ANALYZE");
            QualifiedObjectName tableName = createQualifiedObjectName(session, node, node.getTableName());

            // verify the target table exists and it's not a view
            if (metadata.getView(session, tableName).isPresent()) {
                throw semanticException(NOT_SUPPORTED, node, "Analyzing views is not supported");
            }

            validateProperties(node.getProperties(), scope);
            CatalogName catalogName = metadata.getCatalogHandle(session, tableName.getCatalogName())
                    .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog not found: " + tableName.getCatalogName()));

            Map<String, Object> analyzeProperties = metadata.getAnalyzePropertyManager().getProperties(
                    catalogName,
                    catalogName.getCatalogName(),
                    mapFromProperties(node.getProperties()),
                    session,
                    metadata,
                    analysis.getParameters());
            TableHandle tableHandle = metadata.getTableHandleForStatisticsCollection(session, tableName, analyzeProperties)
                    .orElseThrow(() -> semanticException(TABLE_NOT_FOUND, node, "Table '%s' does not exist", tableName));

            // user must have read and insert permission in order to analyze stats of a table
            analysis.addTableColumnReferences(
                    accessControl,
                    session.getIdentity(),
                    ImmutableMultimap.<QualifiedObjectName, String>builder()
                            .putAll(tableName, metadata.getColumnHandles(session, tableHandle).keySet())
                            .build());
            try {
                accessControl.checkCanInsertIntoTable(session.toSecurityContext(), tableName);
            }
            catch (AccessDeniedException exception) {
                throw new AccessDeniedException(format("Cannot ANALYZE (missing insert privilege) table %s", tableName));
            }

            analysis.setAnalyzeTarget(tableHandle);
            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitCreateTableAsSelect(CreateTableAsSelect node, Optional<Scope> scope)
        {
            analysis.setUpdateType("CREATE TABLE");

            // turn this into a query that has a new table writer node on top.
            QualifiedObjectName targetTable = createQualifiedObjectName(session, node, node.getName());

            Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
            if (targetTableHandle.isPresent()) {
                if (node.isNotExists()) {
                    analysis.setCreate(new Analysis.Create(
                            Optional.of(targetTable),
                            Optional.empty(),
                            Optional.empty(),
                            node.isWithData(),
                            true));
                    return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
                }
                throw semanticException(TABLE_ALREADY_EXISTS, node, "Destination table '%s' already exists", targetTable);
            }

            validateProperties(node.getProperties(), scope);

            accessControl.checkCanCreateTable(session.toSecurityContext(), targetTable);

            // analyze the query that creates the table
            Scope queryScope = process(node.getQuery(), scope);

            ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();

            // analyze target table columns and column aliases
            if (node.getColumnAliases().isPresent()) {
                validateColumnAliases(node.getColumnAliases().get(), queryScope.getRelationType().getVisibleFieldCount());

                int aliasPosition = 0;
                for (Field field : queryScope.getRelationType().getVisibleFields()) {
                    if (field.getType().equals(UNKNOWN)) {
                        throw semanticException(COLUMN_TYPE_UNKNOWN, node, "Column type is unknown at position %s", queryScope.getRelationType().indexOf(field) + 1);
                    }
                    columns.add(new ColumnMetadata(node.getColumnAliases().get().get(aliasPosition).getValue(), field.getType()));
                    aliasPosition++;
                }
            }
            else {
                validateColumns(node, queryScope.getRelationType());
                columns.addAll(queryScope.getRelationType().getVisibleFields().stream()
                        .map(field -> new ColumnMetadata(field.getName().get(), field.getType()))
                        .collect(toImmutableList()));
            }

            // create target table metadata
            CatalogName catalogName = metadata.getCatalogHandle(session, targetTable.getCatalogName())
                    .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog does not exist: " + targetTable.getCatalogName()));

            Map<String, Object> properties = metadata.getTablePropertyManager().getProperties(
                    catalogName,
                    targetTable.getCatalogName(),
                    mapFromProperties(node.getProperties()),
                    session,
                    metadata,
                    analysis.getParameters());

            ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(targetTable.asSchemaTableName(), columns.build(), properties, node.getComment());

            // analyze target table layout
            Optional<NewTableLayout> newTableLayout = metadata.getNewTableLayout(session, targetTable.getCatalogName(), tableMetadata);

            Set<String> columnNames = columns.build().stream()
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableSet());

            newTableLayout.ifPresent(layout -> {
                if (!columnNames.containsAll(layout.getPartitionColumns())) {
                    throw new PrestoException(NOT_SUPPORTED, "INSERT must write all distribution columns: " + layout.getPartitionColumns());
                }
            });

            analysis.setCreate(new Analysis.Create(
                    Optional.of(targetTable),
                    Optional.of(tableMetadata),
                    newTableLayout,
                    node.isWithData(),
                    false));

            return createAndAssignScope(node, scope, Field.newUnqualified("rows", BIGINT));
        }

        @Override
        protected Scope visitCreateView(CreateView node, Optional<Scope> scope)
        {
            analysis.setUpdateType("CREATE VIEW");

            QualifiedObjectName viewName = createQualifiedObjectName(session, node, node.getName());

            // analyze the query that creates the view
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);

            Scope queryScope = analyzer.analyze(node.getQuery(), scope);

            accessControl.checkCanCreateView(session.toSecurityContext(), viewName);

            validateColumns(node, queryScope.getRelationType());

            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitSetSession(SetSession node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitResetSession(ResetSession node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitAddColumn(AddColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCreateSchema(CreateSchema node, Optional<Scope> scope)
        {
            validateProperties(node.getProperties(), scope);
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropSchema(DropSchema node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameSchema(RenameSchema node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCreateTable(CreateTable node, Optional<Scope> scope)
        {
            validateProperties(node.getProperties(), scope);
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitProperty(Property node, Optional<Scope> scope)
        {
            // Property value expressions must be constant
            createConstantAnalyzer(metadata, session, analysis.getParameters(), WarningCollector.NOOP, analysis.isDescribe())
                    .analyze(node.getValue(), createScope(scope));
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropTable(DropTable node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameTable(RenameTable node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitComment(Comment node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRenameColumn(RenameColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropColumn(DropColumn node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDropView(DropView node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitStartTransaction(StartTransaction node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCommit(Commit node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRollback(Rollback node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitPrepare(Prepare node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitDeallocate(Deallocate node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitExecute(Execute node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitGrant(Grant node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitRevoke(Revoke node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        @Override
        protected Scope visitCall(Call node, Optional<Scope> scope)
        {
            return createAndAssignScope(node, scope);
        }

        private void validateProperties(List<Property> properties, Optional<Scope> scope)
        {
            Set<String> propertyNames = new HashSet<>();
            for (Property property : properties) {
                if (!propertyNames.add(property.getName().getValue())) {
                    throw semanticException(DUPLICATE_PROPERTY, property, "Duplicate property: %s", property.getName().getValue());
                }
            }
            for (Property property : properties) {
                process(property, scope);
            }
        }

        private void validateColumns(Statement node, RelationType descriptor)
        {
            // verify that all column names are specified and unique
            // TODO: collect errors and return them all at once
            Set<String> names = new HashSet<>();
            for (Field field : descriptor.getVisibleFields()) {
                Optional<String> fieldName = field.getName();
                if (!fieldName.isPresent()) {
                    throw semanticException(MISSING_COLUMN_NAME, node, "Column name not specified at position %s", descriptor.indexOf(field) + 1);
                }
                if (!names.add(fieldName.get())) {
                    throw semanticException(DUPLICATE_COLUMN_NAME, node, "Column name '%s' specified more than once", fieldName.get());
                }
                if (field.getType().equals(UNKNOWN)) {
                    throw semanticException(COLUMN_TYPE_UNKNOWN, node, "Column type is unknown: %s", fieldName.get());
                }
            }
        }

        private void validateColumnAliases(List<Identifier> columnAliases, int sourceColumnSize)
        {
            if (columnAliases.size() != sourceColumnSize) {
                throw semanticException(
                        MISMATCHED_COLUMN_ALIASES,
                        columnAliases.get(0),
                        "Column alias list has %s entries but subquery has %s columns",
                        columnAliases.size(),
                        sourceColumnSize);
            }
            Set<String> names = new HashSet<>();
            for (Identifier identifier : columnAliases) {
                if (names.contains(identifier.getValue().toLowerCase(ENGLISH))) {
                    throw semanticException(DUPLICATE_COLUMN_NAME, identifier, "Column name '%s' specified more than once", identifier.getValue());
                }
                names.add(identifier.getValue().toLowerCase(ENGLISH));
            }
        }

        @Override
        protected Scope visitExplain(Explain node, Optional<Scope> scope)
        {
            checkState(node.isAnalyze(), "Non analyze explain should be rewritten to Query");
            if (node.getOptions().stream().anyMatch(option -> !option.equals(new ExplainType(DISTRIBUTED)))) {
                throw semanticException(NOT_SUPPORTED, node, "EXPLAIN ANALYZE only supports TYPE DISTRIBUTED option");
            }
            process(node.getStatement(), scope);
            analysis.setUpdateType(null);
            return createAndAssignScope(node, scope, Field.newUnqualified("Query Plan", VARCHAR));
        }

        @Override
        protected Scope visitQuery(Query node, Optional<Scope> scope)
        {
            Scope withScope = analyzeWith(node, scope);
            Scope queryBodyScope = process(node.getQueryBody(), withScope);

            List<Expression> orderByExpressions = emptyList();
            if (node.getOrderBy().isPresent()) {
                orderByExpressions = analyzeOrderBy(node, getSortItemsFromOrderBy(node.getOrderBy()), queryBodyScope);

                if (queryBodyScope.getOuterQueryParent().isPresent() && !node.getLimit().isPresent() && !node.getOffset().isPresent()) {
                    // not the root scope and ORDER BY is ineffective
                    analysis.markRedundantOrderBy(node.getOrderBy().get());
                    warningCollector.add(new PrestoWarning(REDUNDANT_ORDER_BY, "ORDER BY in subquery may have no effect"));
                }
            }
            analysis.setOrderByExpressions(node, orderByExpressions);

            if (node.getOffset().isPresent()) {
                analyzeOffset(node.getOffset().get());
            }

            if (node.getLimit().isPresent()) {
                boolean requiresOrderBy = analyzeLimit(node.getLimit().get());
                if (requiresOrderBy && !node.getOrderBy().isPresent()) {
                    throw semanticException(MISSING_ORDER_BY, node.getLimit().get(), "FETCH FIRST WITH TIES clause requires ORDER BY");
                }
            }

            // Input fields == Output fields
            analysis.setOutputExpressions(node, descriptorToFields(queryBodyScope));

            Scope queryScope = Scope.builder()
                    .withParent(withScope)
                    .withRelationType(RelationId.of(node), queryBodyScope.getRelationType())
                    .build();

            analysis.setScope(node, queryScope);
            return queryScope;
        }

        @Override
        protected Scope visitUnnest(Unnest node, Optional<Scope> scope)
        {
            ImmutableList.Builder<Field> outputFields = ImmutableList.builder();
            for (Expression expression : node.getExpressions()) {
                ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, createScope(scope));
                Type expressionType = expressionAnalysis.getType(expression);
                if (expressionType instanceof ArrayType) {
                    Type elementType = ((ArrayType) expressionType).getElementType();
                    if (elementType instanceof RowType) {
                        ((RowType) elementType).getFields().stream()
                                .map(field -> Field.newUnqualified(field.getName(), field.getType()))
                                .forEach(outputFields::add);
                    }
                    else {
                        outputFields.add(Field.newUnqualified(Optional.empty(), elementType));
                    }
                }
                else if (expressionType instanceof MapType) {
                    outputFields.add(Field.newUnqualified(Optional.empty(), ((MapType) expressionType).getKeyType()));
                    outputFields.add(Field.newUnqualified(Optional.empty(), ((MapType) expressionType).getValueType()));
                }
                else {
                    throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Cannot unnest type: " + expressionType);
                }
            }
            if (node.isWithOrdinality()) {
                outputFields.add(Field.newUnqualified(Optional.empty(), BIGINT));
            }
            return createAndAssignScope(node, scope, outputFields.build());
        }

        @Override
        protected Scope visitLateral(Lateral node, Optional<Scope> scope)
        {
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);
            Scope queryScope = analyzer.analyze(node.getQuery(), scope);
            return createAndAssignScope(node, scope, queryScope.getRelationType());
        }

        @Override
        protected Scope visitTable(Table table, Optional<Scope> scope)
        {
            if (!table.getName().getPrefix().isPresent()) {
                // is this a reference to a WITH query?
                Optional<WithQuery> withQuery = createScope(scope).getNamedQuery(table.getName().getSuffix());
                if (withQuery.isPresent()) {
                    return createScopeForCommonTableExpression(table, scope, withQuery.get());
                }
            }

            QualifiedObjectName name = createQualifiedObjectName(session, table, table.getName());
            analysis.addEmptyColumnReferencesForTable(accessControl, session.getIdentity(), name);

            // is this a reference to a view?
            Optional<ConnectorViewDefinition> optionalView = metadata.getView(session, name);
            if (optionalView.isPresent()) {
                return createScopeForView(table, name, scope, optionalView.get());
            }

            Optional<TableHandle> tableHandle = metadata.getTableHandle(session, name);
            if (!tableHandle.isPresent()) {
                if (!metadata.getCatalogHandle(session, name.getCatalogName()).isPresent()) {
                    throw semanticException(CATALOG_NOT_FOUND, table, "Catalog %s does not exist", name.getCatalogName());
                }
                if (!metadata.schemaExists(session, new CatalogSchemaName(name.getCatalogName(), name.getSchemaName()))) {
                    throw semanticException(SCHEMA_NOT_FOUND, table, "Schema %s does not exist", name.getSchemaName());
                }
                throw semanticException(TABLE_NOT_FOUND, table, "Table %s does not exist", name);
            }
            TableMetadata tableMetadata = metadata.getTableMetadata(session, tableHandle.get());
            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle.get());

            // TODO: discover columns lazily based on where they are needed (to support connectors that can't enumerate all tables)
            ImmutableList.Builder<Field> fields = ImmutableList.builder();
            for (ColumnMetadata column : tableMetadata.getColumns()) {
                Field field = Field.newQualified(
                        table.getName(),
                        Optional.of(column.getName()),
                        column.getType(),
                        column.isHidden(),
                        Optional.of(name),
                        Optional.of(column.getName()),
                        false);
                fields.add(field);
                ColumnHandle columnHandle = columnHandles.get(column.getName());
                checkArgument(columnHandle != null, "Unknown field %s", field);
                analysis.setColumn(field, columnHandle);
            }

            analysis.registerTable(table, tableHandle.get());

            return createAndAssignScope(table, scope, fields.build());
        }

        private Scope createScopeForCommonTableExpression(Table table, Optional<Scope> scope, WithQuery withQuery)
        {
            Query query = withQuery.getQuery();
            analysis.registerNamedQuery(table, query);

            // re-alias the fields with the name assigned to the query in the WITH declaration
            RelationType queryDescriptor = analysis.getOutputDescriptor(query);

            List<Field> fields;
            Optional<List<Identifier>> columnNames = withQuery.getColumnNames();
            if (columnNames.isPresent()) {
                // if columns are explicitly aliased -> WITH cte(alias1, alias2 ...)
                ImmutableList.Builder<Field> fieldBuilder = ImmutableList.builder();

                int field = 0;
                for (Identifier columnName : columnNames.get()) {
                    Field inputField = queryDescriptor.getFieldByIndex(field);
                    fieldBuilder.add(Field.newQualified(
                            QualifiedName.of(table.getName().getSuffix()),
                            Optional.of(columnName.getValue()),
                            inputField.getType(),
                            false,
                            inputField.getOriginTable(),
                            inputField.getOriginColumnName(),
                            inputField.isAliased()));

                    field++;
                }

                fields = fieldBuilder.build();
            }
            else {
                fields = queryDescriptor.getAllFields().stream()
                        .map(field -> Field.newQualified(
                                QualifiedName.of(table.getName().getSuffix()),
                                field.getName(),
                                field.getType(),
                                field.isHidden(),
                                field.getOriginTable(),
                                field.getOriginColumnName(),
                                field.isAliased()))
                        .collect(toImmutableList());
            }

            return createAndAssignScope(table, scope, fields);
        }

        private Scope createScopeForView(Table table, QualifiedObjectName name, Optional<Scope> scope, ConnectorViewDefinition view)
        {
            Statement statement = analysis.getStatement();
            if (statement instanceof CreateView) {
                CreateView viewStatement = (CreateView) statement;
                QualifiedObjectName viewNameFromStatement = createQualifiedObjectName(session, viewStatement, viewStatement.getName());
                if (viewStatement.isReplace() && viewNameFromStatement.equals(name)) {
                    throw semanticException(VIEW_IS_RECURSIVE, table, "Statement would create a recursive view");
                }
            }
            if (analysis.hasTableInView(table)) {
                throw semanticException(VIEW_IS_RECURSIVE, table, "View is recursive");
            }

            Query query = parseView(view.getOriginalSql(), name, table);
            analysis.registerNamedQuery(table, query);
            analysis.registerTableForView(table);
            RelationType descriptor = analyzeView(query, name, view.getCatalog(), view.getSchema(), view.getOwner(), table);
            analysis.unregisterTableForView();

            if (isViewStale(view.getColumns(), descriptor.getVisibleFields(), name, table)) {
                throw semanticException(VIEW_IS_STALE, table, "View '%s' is stale; it must be re-created", name);
            }

            // Derive the type of the view from the stored definition, not from the analysis of the underlying query.
            // This is needed in case the underlying table(s) changed and the query in the view now produces types that
            // are implicitly coercible to the declared view types.
            List<Field> outputFields = view.getColumns().stream()
                    .map(column -> Field.newQualified(
                            table.getName(),
                            Optional.of(column.getName()),
                            getViewColumnType(column, name, table),
                            false,
                            Optional.of(name),
                            Optional.of(column.getName()),
                            false))
                    .collect(toImmutableList());

            analysis.addRelationCoercion(table, outputFields.stream().map(Field::getType).toArray(Type[]::new));

            return createAndAssignScope(table, scope, outputFields);
        }

        @Override
        protected Scope visitAliasedRelation(AliasedRelation relation, Optional<Scope> scope)
        {
            Scope relationScope = process(relation.getRelation(), scope);

            // todo this check should be inside of TupleDescriptor.withAlias, but the exception needs the node object
            RelationType relationType = relationScope.getRelationType();
            if (relation.getColumnNames() != null) {
                int totalColumns = relationType.getVisibleFieldCount();
                if (totalColumns != relation.getColumnNames().size()) {
                    throw semanticException(MISMATCHED_COLUMN_ALIASES, relation, "Column alias list has %s entries but '%s' has %s columns available", relation.getColumnNames().size(), relation.getAlias(), totalColumns);
                }
            }

            List<String> aliases = null;
            if (relation.getColumnNames() != null) {
                aliases = relation.getColumnNames().stream()
                        .map(Identifier::getValue)
                        .collect(Collectors.toList());
            }

            RelationType descriptor = relationType.withAlias(relation.getAlias().getValue(), aliases);

            return createAndAssignScope(relation, scope, descriptor);
        }

        @Override
        protected Scope visitSampledRelation(SampledRelation relation, Optional<Scope> scope)
        {
            if (!SymbolsExtractor.extractNames(relation.getSamplePercentage(), analysis.getColumnReferences()).isEmpty()) {
                throw semanticException(EXPRESSION_NOT_CONSTANT, relation.getSamplePercentage(), "Sample percentage cannot contain column references");
            }

            Map<NodeRef<Expression>, Type> expressionTypes = ExpressionAnalyzer.analyzeExpressions(
                    session,
                    metadata,
                    sqlParser,
                    TypeProvider.empty(),
                    ImmutableList.of(relation.getSamplePercentage()),
                    analysis.getParameters(),
                    WarningCollector.NOOP,
                    analysis.isDescribe())
                    .getExpressionTypes();

            ExpressionInterpreter samplePercentageEval = expressionOptimizer(relation.getSamplePercentage(), metadata, session, expressionTypes);

            Object samplePercentageObject = samplePercentageEval.optimize(symbol -> {
                throw semanticException(EXPRESSION_NOT_CONSTANT, relation.getSamplePercentage(), "Sample percentage cannot contain column references");
            });

            if (!(samplePercentageObject instanceof Number)) {
                throw semanticException(TYPE_MISMATCH, relation.getSamplePercentage(), "Sample percentage should evaluate to a numeric expression");
            }

            double samplePercentageValue = ((Number) samplePercentageObject).doubleValue();

            if (samplePercentageValue < 0.0) {
                throw semanticException(NUMERIC_VALUE_OUT_OF_RANGE, relation.getSamplePercentage(), "Sample percentage must be greater than or equal to 0");
            }
            if ((samplePercentageValue > 100.0)) {
                throw semanticException(NUMERIC_VALUE_OUT_OF_RANGE, relation.getSamplePercentage(), "Sample percentage must be less than or equal to 100");
            }

            analysis.setSampleRatio(relation, samplePercentageValue / 100);
            Scope relationScope = process(relation.getRelation(), scope);
            return createAndAssignScope(relation, scope, relationScope.getRelationType());
        }

        @Override
        protected Scope visitTableSubquery(TableSubquery node, Optional<Scope> scope)
        {
            StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector);
            Scope queryScope = analyzer.analyze(node.getQuery(), scope);
            return createAndAssignScope(node, scope, queryScope.getRelationType());
        }

        @Override
        protected Scope visitQuerySpecification(QuerySpecification node, Optional<Scope> scope)
        {
            // TODO: extract candidate names from SELECT, WHERE, HAVING, GROUP BY and ORDER BY expressions
            // to pass down to analyzeFrom

            Scope sourceScope = analyzeFrom(node, scope);

            node.getWhere().ifPresent(where -> analyzeWhere(node, sourceScope, where));

            List<Expression> outputExpressions = analyzeSelect(node, sourceScope);
            List<Expression> groupByExpressions = analyzeGroupBy(node, sourceScope, outputExpressions);
            analyzeHaving(node, sourceScope);

            Scope outputScope = computeAndAssignOutputScope(node, scope, sourceScope);

            List<Expression> orderByExpressions = emptyList();
            Optional<Scope> orderByScope = Optional.empty();
            if (node.getOrderBy().isPresent()) {
                if (node.getSelect().isDistinct()) {
                    verifySelectDistinct(node, outputExpressions);
                }

                OrderBy orderBy = node.getOrderBy().get();
                orderByScope = Optional.of(computeAndAssignOrderByScope(orderBy, sourceScope, outputScope));

                orderByExpressions = analyzeOrderBy(node, orderBy.getSortItems(), orderByScope.get());

                if (sourceScope.getOuterQueryParent().isPresent() && !node.getLimit().isPresent() && !node.getOffset().isPresent()) {
                    // not the root scope and ORDER BY is ineffective
                    analysis.markRedundantOrderBy(orderBy);
                    warningCollector.add(new PrestoWarning(REDUNDANT_ORDER_BY, "ORDER BY in subquery may have no effect"));
                }
            }
            analysis.setOrderByExpressions(node, orderByExpressions);

            if (node.getOffset().isPresent()) {
                analyzeOffset(node.getOffset().get());
            }

            if (node.getLimit().isPresent()) {
                boolean requiresOrderBy = analyzeLimit(node.getLimit().get());
                if (requiresOrderBy && !node.getOrderBy().isPresent()) {
                    throw semanticException(MISSING_ORDER_BY, node.getLimit().get(), "FETCH FIRST WITH TIES clause requires ORDER BY");
                }
            }

            List<Expression> sourceExpressions = new ArrayList<>(outputExpressions);
            node.getHaving().ifPresent(sourceExpressions::add);

            analyzeGroupingOperations(node, sourceExpressions, orderByExpressions);
            analyzeAggregations(node, sourceScope, orderByScope, groupByExpressions, sourceExpressions, orderByExpressions);
            analyzeWindowFunctions(node, outputExpressions, orderByExpressions);

            if (analysis.isAggregation(node) && node.getOrderBy().isPresent()) {
                // Create a different scope for ORDER BY expressions when aggregation is present.
                // This is because planner requires scope in order to resolve names against fields.
                // Original ORDER BY scope "sees" FROM query fields. However, during planning
                // and when aggregation is present, ORDER BY expressions should only be resolvable against
                // output scope, group by expressions and aggregation expressions.
                List<GroupingOperation> orderByGroupingOperations = extractExpressions(orderByExpressions, GroupingOperation.class);
                List<FunctionCall> orderByAggregations = extractAggregateFunctions(orderByExpressions, metadata);
                computeAndAssignOrderByScopeWithAggregation(node.getOrderBy().get(), sourceScope, outputScope, orderByAggregations, groupByExpressions, orderByGroupingOperations);
            }

            return outputScope;
        }

        @Override
        protected Scope visitSetOperation(SetOperation node, Optional<Scope> scope)
        {
            checkState(node.getRelations().size() >= 2);

            List<Scope> relationScopes = node.getRelations().stream()
                    .map(relation -> {
                        Scope relationScope = process(relation, scope);
                        return createAndAssignScope(relation, scope, relationScope.getRelationType().withOnlyVisibleFields());
                    })
                    .collect(toImmutableList());

            Type[] outputFieldTypes = relationScopes.get(0).getRelationType().getVisibleFields().stream()
                    .map(Field::getType)
                    .toArray(Type[]::new);
            for (Scope relationScope : relationScopes) {
                int outputFieldSize = outputFieldTypes.length;
                RelationType relationType = relationScope.getRelationType();
                int descFieldSize = relationType.getVisibleFields().size();
                String setOperationName = node.getClass().getSimpleName().toUpperCase(ENGLISH);
                if (outputFieldSize != descFieldSize) {
                    throw semanticException(
                            TYPE_MISMATCH,
                            node,
                            "%s query has different number of fields: %d, %d",
                            setOperationName,
                            outputFieldSize,
                            descFieldSize);
                }
                for (int i = 0; i < descFieldSize; i++) {
                    Type descFieldType = relationType.getFieldByIndex(i).getType();
                    Optional<Type> commonSuperType = typeCoercion.getCommonSuperType(outputFieldTypes[i], descFieldType);
                    if (!commonSuperType.isPresent()) {
                        throw semanticException(
                                TYPE_MISMATCH,
                                node,
                                "column %d in %s query has incompatible types: %s, %s",
                                i + 1,
                                setOperationName,
                                outputFieldTypes[i].getDisplayName(),
                                descFieldType.getDisplayName());
                    }
                    outputFieldTypes[i] = commonSuperType.get();
                }
            }

            Field[] outputDescriptorFields = new Field[outputFieldTypes.length];
            RelationType firstDescriptor = relationScopes.get(0).getRelationType().withOnlyVisibleFields();
            for (int i = 0; i < outputFieldTypes.length; i++) {
                Field oldField = firstDescriptor.getFieldByIndex(i);
                outputDescriptorFields[i] = new Field(
                        oldField.getRelationAlias(),
                        oldField.getName(),
                        outputFieldTypes[i],
                        oldField.isHidden(),
                        oldField.getOriginTable(),
                        oldField.getOriginColumnName(),
                        oldField.isAliased());
            }

            for (int i = 0; i < node.getRelations().size(); i++) {
                Relation relation = node.getRelations().get(i);
                Scope relationScope = relationScopes.get(i);
                RelationType relationType = relationScope.getRelationType();
                for (int j = 0; j < relationType.getVisibleFields().size(); j++) {
                    Type outputFieldType = outputFieldTypes[j];
                    Type descFieldType = relationType.getFieldByIndex(j).getType();
                    if (!outputFieldType.equals(descFieldType)) {
                        analysis.addRelationCoercion(relation, outputFieldTypes);
                        break;
                    }
                }
            }
            return createAndAssignScope(node, scope, outputDescriptorFields);
        }

        @Override
        protected Scope visitIntersect(Intersect node, Optional<Scope> scope)
        {
            if (!node.isDistinct()) {
                throw semanticException(NOT_SUPPORTED, node, "INTERSECT ALL not yet implemented");
            }

            return visitSetOperation(node, scope);
        }

        @Override
        protected Scope visitExcept(Except node, Optional<Scope> scope)
        {
            if (!node.isDistinct()) {
                throw semanticException(NOT_SUPPORTED, node, "EXCEPT ALL not yet implemented");
            }

            return visitSetOperation(node, scope);
        }

        @Override
        protected Scope visitJoin(Join node, Optional<Scope> scope)
        {
            JoinCriteria criteria = node.getCriteria().orElse(null);
            if (criteria instanceof NaturalJoin) {
                throw semanticException(NOT_SUPPORTED, node, "Natural join not supported");
            }

            Scope left = process(node.getLeft(), scope);
            Scope right = process(node.getRight(), isLateralRelation(node.getRight()) ? Optional.of(left) : scope);

            if (criteria instanceof JoinUsing) {
                return analyzeJoinUsing(node, ((JoinUsing) criteria).getColumns(), scope, left, right);
            }

            Scope output = createAndAssignScope(node, scope, left.getRelationType().joinWith(right.getRelationType()));

            if (node.getType() == Join.Type.CROSS || node.getType() == Join.Type.IMPLICIT) {
                return output;
            }
            if (criteria instanceof JoinOn) {
                Expression expression = ((JoinOn) criteria).getExpression();

                // need to register coercions in case when join criteria requires coercion (e.g. join on char(1) = char(2))
                ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, output);
                Type clauseType = expressionAnalysis.getType(expression);
                if (!clauseType.equals(BOOLEAN)) {
                    if (!clauseType.equals(UNKNOWN)) {
                        throw semanticException(TYPE_MISMATCH, expression, "JOIN ON clause must evaluate to a boolean: actual type %s", clauseType);
                    }
                    // coerce null to boolean
                    analysis.addCoercion(expression, BOOLEAN, false);
                }

                Analyzer.verifyNoAggregateWindowOrGroupingFunctions(metadata, expression, "JOIN clause");

                analysis.recordSubqueries(node, expressionAnalysis);
                analysis.setJoinCriteria(node, expression);
            }
            else {
                throw new UnsupportedOperationException("Unsupported join criteria: " + criteria.getClass().getName());
            }

            return output;
        }

        private Scope analyzeJoinUsing(Join node, List<Identifier> columns, Optional<Scope> scope, Scope left, Scope right)
        {
            List<Field> joinFields = new ArrayList<>();

            List<Integer> leftJoinFields = new ArrayList<>();
            List<Integer> rightJoinFields = new ArrayList<>();

            Set<Identifier> seen = new HashSet<>();
            for (Identifier column : columns) {
                if (!seen.add(column)) {
                    throw semanticException(DUPLICATE_COLUMN_NAME, column, "Column '%s' appears multiple times in USING clause", column.getValue());
                }

                Optional<ResolvedField> leftField = left.tryResolveField(column);
                Optional<ResolvedField> rightField = right.tryResolveField(column);

                if (!leftField.isPresent()) {
                    throw semanticException(COLUMN_NOT_FOUND, column, "Column '%s' is missing from left side of join", column.getValue());
                }
                if (!rightField.isPresent()) {
                    throw semanticException(COLUMN_NOT_FOUND, column, "Column '%s' is missing from right side of join", column.getValue());
                }

                // ensure a comparison operator exists for the given types (applying coercions if necessary)
                try {
                    metadata.resolveOperator(OperatorType.EQUAL, ImmutableList.of(
                            leftField.get().getType(), rightField.get().getType()));
                }
                catch (OperatorNotFoundException e) {
                    throw semanticException(TYPE_MISMATCH, column, "%s", e.getMessage());
                }

                Optional<Type> type = typeCoercion.getCommonSuperType(leftField.get().getType(), rightField.get().getType());
                analysis.addTypes(ImmutableMap.of(NodeRef.of(column), type.get()));

                joinFields.add(Field.newUnqualified(column.getValue(), type.get()));

                leftJoinFields.add(leftField.get().getRelationFieldIndex());
                rightJoinFields.add(rightField.get().getRelationFieldIndex());
            }

            ImmutableList.Builder<Field> outputs = ImmutableList.builder();
            outputs.addAll(joinFields);

            ImmutableList.Builder<Integer> leftFields = ImmutableList.builder();
            for (int i = 0; i < left.getRelationType().getAllFieldCount(); i++) {
                if (!leftJoinFields.contains(i)) {
                    outputs.add(left.getRelationType().getFieldByIndex(i));
                    leftFields.add(i);
                }
            }

            ImmutableList.Builder<Integer> rightFields = ImmutableList.builder();
            for (int i = 0; i < right.getRelationType().getAllFieldCount(); i++) {
                if (!rightJoinFields.contains(i)) {
                    outputs.add(right.getRelationType().getFieldByIndex(i));
                    rightFields.add(i);
                }
            }

            analysis.setJoinUsing(node, new Analysis.JoinUsingAnalysis(leftJoinFields, rightJoinFields, leftFields.build(), rightFields.build()));

            return createAndAssignScope(node, scope, new RelationType(outputs.build()));
        }

        private boolean isLateralRelation(Relation node)
        {
            if (node instanceof AliasedRelation) {
                return isLateralRelation(((AliasedRelation) node).getRelation());
            }
            return node instanceof Unnest || node instanceof Lateral;
        }

        @Override
        protected Scope visitValues(Values node, Optional<Scope> scope)
        {
            checkState(node.getRows().size() >= 1);

            List<List<Type>> rowTypes = node.getRows().stream()
                    .map(row -> analyzeExpression(row, createScope(scope)).getType(row))
                    .map(type -> {
                        if (type instanceof RowType) {
                            return type.getTypeParameters();
                        }
                        return ImmutableList.of(type);
                    })
                    .collect(toImmutableList());

            // determine common super type of the rows
            List<Type> fieldTypes = new ArrayList<>(rowTypes.iterator().next());
            for (List<Type> rowType : rowTypes) {
                // check field count consistency for rows
                if (rowType.size() != fieldTypes.size()) {
                    throw semanticException(TYPE_MISMATCH,
                            node,
                            "Values rows have mismatched types: %s vs %s",
                            rowTypes.get(0),
                            rowType);
                }

                for (int i = 0; i < rowType.size(); i++) {
                    Type fieldType = rowType.get(i);
                    Type superType = fieldTypes.get(i);

                    Optional<Type> commonSuperType = typeCoercion.getCommonSuperType(fieldType, superType);
                    if (!commonSuperType.isPresent()) {
                        throw semanticException(TYPE_MISMATCH,
                                node,
                                "Values rows have mismatched types: %s vs %s",
                                rowTypes.get(0),
                                rowType);
                    }
                    fieldTypes.set(i, commonSuperType.get());
                }
            }

            // add coercions for the rows
            for (Expression row : node.getRows()) {
                if (row instanceof Row) {
                    List<Expression> items = ((Row) row).getItems();
                    for (int i = 0; i < items.size(); i++) {
                        Type expectedType = fieldTypes.get(i);
                        Expression item = items.get(i);
                        Type actualType = analysis.getType(item);
                        if (!actualType.equals(expectedType)) {
                            analysis.addCoercion(item, expectedType, typeCoercion.isTypeOnlyCoercion(actualType, expectedType));
                        }
                    }
                }
                else {
                    Type actualType = analysis.getType(row);
                    Type expectedType = fieldTypes.get(0);
                    if (!actualType.equals(expectedType)) {
                        analysis.addCoercion(row, expectedType, typeCoercion.isTypeOnlyCoercion(actualType, expectedType));
                    }
                }
            }

            List<Field> fields = fieldTypes.stream()
                    .map(valueType -> Field.newUnqualified(Optional.empty(), valueType))
                    .collect(toImmutableList());

            return createAndAssignScope(node, scope, fields);
        }

        private void analyzeWindowFunctions(QuerySpecification node, List<Expression> outputExpressions, List<Expression> orderByExpressions)
        {
            analysis.setWindowFunctions(node, analyzeWindowFunctions(node, outputExpressions));
            if (node.getOrderBy().isPresent()) {
                analysis.setOrderByWindowFunctions(node.getOrderBy().get(), analyzeWindowFunctions(node, orderByExpressions));
            }
        }

        private List<FunctionCall> analyzeWindowFunctions(QuerySpecification node, List<Expression> expressions)
        {
            for (Expression expression : expressions) {
                new WindowFunctionValidator().process(expression, analysis);
            }

            List<FunctionCall> windowFunctions = extractWindowFunctions(expressions);

            for (FunctionCall windowFunction : windowFunctions) {
                // filter with window function is not supported yet
                if (windowFunction.getFilter().isPresent()) {
                    throw semanticException(NOT_SUPPORTED, node, "FILTER is not yet supported for window functions");
                }

                if (windowFunction.getOrderBy().isPresent()) {
                    throw semanticException(NOT_SUPPORTED, windowFunction, "Window function with ORDER BY is not supported");
                }

                Window window = windowFunction.getWindow().get();

                ImmutableList.Builder<Node> toExtract = ImmutableList.builder();
                toExtract.addAll(windowFunction.getArguments());
                toExtract.addAll(window.getPartitionBy());
                window.getOrderBy().ifPresent(orderBy -> toExtract.addAll(orderBy.getSortItems()));
                window.getFrame().ifPresent(toExtract::add);

                List<FunctionCall> nestedWindowFunctions = extractWindowFunctions(toExtract.build());

                if (!nestedWindowFunctions.isEmpty()) {
                    throw semanticException(NESTED_WINDOW, node, "Cannot nest window functions inside window function '%s': %s",
                            windowFunction,
                            windowFunctions);
                }

                if (windowFunction.isDistinct()) {
                    throw semanticException(NOT_SUPPORTED, node, "DISTINCT in window function parameters not yet supported: %s", windowFunction);
                }

                // TODO get function requirements from window function metadata when we have it
                String name = windowFunction.getName().toString().toLowerCase(ENGLISH);
                if (name.equals("lag") || name.equals("lead")) {
                    if (!window.getOrderBy().isPresent()) {
                        throw semanticException(MISSING_ORDER_BY, window, "%s function requires an ORDER BY window clause", windowFunction.getName());
                    }
                    if (window.getFrame().isPresent()) {
                        throw semanticException(INVALID_WINDOW_FRAME, window.getFrame().get(), "Cannot specify window frame for %s function", windowFunction.getName());
                    }
                }

                if (!WINDOW_VALUE_FUNCTIONS.contains(name) && windowFunction.getNullTreatment().isPresent()) {
                    throw semanticException(NULL_TREATMENT_NOT_ALLOWED, windowFunction, "Cannot specify null treatment clause for %s function", windowFunction.getName());
                }

                if (window.getFrame().isPresent()) {
                    analyzeWindowFrame(window.getFrame().get());
                }

                List<TypeSignature> argumentTypes = mappedCopy(windowFunction.getArguments(), expression -> analysis.getType(expression).getTypeSignature());

                FunctionKind kind = metadata.resolveFunction(windowFunction.getName(), fromTypeSignatures(argumentTypes)).getKind();
                if (kind != AGGREGATE && kind != WINDOW) {
                    throw semanticException(FUNCTION_NOT_WINDOW, node, "Not a window function: %s", windowFunction.getName());
                }
            }

            return windowFunctions;
        }

        private void analyzeWindowFrame(WindowFrame frame)
        {
            FrameBound.Type startType = frame.getStart().getType();
            FrameBound.Type endType = frame.getEnd().orElse(new FrameBound(CURRENT_ROW)).getType();

            if (startType == UNBOUNDED_FOLLOWING) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame start cannot be UNBOUNDED FOLLOWING");
            }
            if (endType == UNBOUNDED_PRECEDING) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame end cannot be UNBOUNDED PRECEDING");
            }
            if ((startType == CURRENT_ROW) && (endType == PRECEDING)) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from CURRENT ROW cannot end with PRECEDING");
            }
            if ((startType == FOLLOWING) && (endType == PRECEDING)) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from FOLLOWING cannot end with PRECEDING");
            }
            if ((startType == FOLLOWING) && (endType == CURRENT_ROW)) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame starting from FOLLOWING cannot end with CURRENT ROW");
            }
            if ((frame.getType() == RANGE) && ((startType == PRECEDING) || (endType == PRECEDING))) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame RANGE PRECEDING is only supported with UNBOUNDED");
            }
            if ((frame.getType() == RANGE) && ((startType == FOLLOWING) || (endType == FOLLOWING))) {
                throw semanticException(INVALID_WINDOW_FRAME, frame, "Window frame RANGE FOLLOWING is only supported with UNBOUNDED");
            }
        }

        private void analyzeHaving(QuerySpecification node, Scope scope)
        {
            if (node.getHaving().isPresent()) {
                Expression predicate = node.getHaving().get();

                ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);

                expressionAnalysis.getWindowFunctions().stream()
                        .findFirst()
                        .ifPresent(function -> {
                            throw semanticException(NESTED_WINDOW, function.getNode(), "HAVING clause cannot contain window functions");
                        });

                analysis.recordSubqueries(node, expressionAnalysis);

                Type predicateType = expressionAnalysis.getType(predicate);
                if (!predicateType.equals(BOOLEAN) && !predicateType.equals(UNKNOWN)) {
                    throw semanticException(TYPE_MISMATCH, predicate, "HAVING clause must evaluate to a boolean: actual type %s", predicateType);
                }

                analysis.setHaving(node, predicate);
            }
        }

        private Multimap<QualifiedName, Expression> extractNamedOutputExpressions(Select node)
        {
            // Compute aliased output terms so we can resolve order by expressions against them first
            ImmutableMultimap.Builder<QualifiedName, Expression> assignments = ImmutableMultimap.builder();
            for (SelectItem item : node.getSelectItems()) {
                if (item instanceof SingleColumn) {
                    SingleColumn column = (SingleColumn) item;
                    Optional<Identifier> alias = column.getAlias();
                    if (alias.isPresent()) {
                        assignments.put(QualifiedName.of(alias.get().getValue()), column.getExpression()); // TODO: need to know if alias was quoted
                    }
                    else if (column.getExpression() instanceof Identifier) {
                        assignments.put(QualifiedName.of(((Identifier) column.getExpression()).getValue()), column.getExpression());
                    }
                }
            }

            return assignments.build();
        }

        private class OrderByExpressionRewriter
                extends ExpressionRewriter<Void>
        {
            private final Multimap<QualifiedName, Expression> assignments;

            public OrderByExpressionRewriter(Multimap<QualifiedName, Expression> assignments)
            {
                this.assignments = assignments;
            }

            @Override
            public Expression rewriteIdentifier(Identifier reference, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                // if this is a simple name reference, try to resolve against output columns
                QualifiedName name = QualifiedName.of(reference.getValue());
                Set<Expression> expressions = ImmutableSet.copyOf(assignments.get(name));

                if (expressions.size() > 1) {
                    throw semanticException(AMBIGUOUS_NAME, reference, "'%s' in ORDER BY is ambiguous", name);
                }

                if (expressions.size() == 1) {
                    return Iterables.getOnlyElement(expressions);
                }

                // otherwise, couldn't resolve name against output aliases, so fall through...
                return reference;
            }
        }

        private void checkGroupingSetsCount(GroupBy node)
        {
            // If groupBy is distinct then crossProduct will be overestimated if there are duplicate grouping sets.
            int crossProduct = 1;
            for (GroupingElement element : node.getGroupingElements()) {
                try {
                    int product;
                    if (element instanceof SimpleGroupBy) {
                        product = 1;
                    }
                    else if (element instanceof Cube) {
                        int exponent = element.getExpressions().size();
                        if (exponent > 30) {
                            throw new ArithmeticException();
                        }
                        product = 1 << exponent;
                    }
                    else if (element instanceof Rollup) {
                        product = element.getExpressions().size() + 1;
                    }
                    else if (element instanceof GroupingSets) {
                        product = ((GroupingSets) element).getSets().size();
                    }
                    else {
                        throw new UnsupportedOperationException("Unsupported grouping element type: " + element.getClass().getName());
                    }
                    crossProduct = Math.multiplyExact(crossProduct, product);
                }
                catch (ArithmeticException e) {
                    throw semanticException(TOO_MANY_GROUPING_SETS, node,
                            "GROUP BY has more than %s grouping sets but can contain at most %s", Integer.MAX_VALUE, getMaxGroupingSets(session));
                }
                if (crossProduct > getMaxGroupingSets(session)) {
                    throw semanticException(TOO_MANY_GROUPING_SETS, node,
                            "GROUP BY has %s grouping sets but can contain at most %s", crossProduct, getMaxGroupingSets(session));
                }
            }
        }

        private List<Expression> analyzeGroupBy(QuerySpecification node, Scope scope, List<Expression> outputExpressions)
        {
            if (node.getGroupBy().isPresent()) {
                ImmutableList.Builder<Set<FieldId>> cubes = ImmutableList.builder();
                ImmutableList.Builder<List<FieldId>> rollups = ImmutableList.builder();
                ImmutableList.Builder<List<Set<FieldId>>> sets = ImmutableList.builder();
                ImmutableList.Builder<Expression> complexExpressions = ImmutableList.builder();
                ImmutableList.Builder<Expression> groupingExpressions = ImmutableList.builder();

                checkGroupingSetsCount(node.getGroupBy().get());
                for (GroupingElement groupingElement : node.getGroupBy().get().getGroupingElements()) {
                    if (groupingElement instanceof SimpleGroupBy) {
                        for (Expression column : groupingElement.getExpressions()) {
                            // simple GROUP BY expressions allow ordinals or arbitrary expressions
                            if (column instanceof LongLiteral) {
                                long ordinal = ((LongLiteral) column).getValue();
                                if (ordinal < 1 || ordinal > outputExpressions.size()) {
                                    throw semanticException(INVALID_COLUMN_REFERENCE, column, "GROUP BY position %s is not in select list", ordinal);
                                }

                                column = outputExpressions.get(toIntExact(ordinal - 1));
                            }
                            else {
                                analyzeExpression(column, scope);
                            }

                            FieldId field = analysis.getColumnReferenceFields().get(NodeRef.of(column));
                            if (field != null) {
                                sets.add(ImmutableList.of(ImmutableSet.of(field)));
                            }
                            else {
                                Analyzer.verifyNoAggregateWindowOrGroupingFunctions(metadata, column, "GROUP BY clause");
                                analysis.recordSubqueries(node, analyzeExpression(column, scope));
                                complexExpressions.add(column);
                            }

                            groupingExpressions.add(column);
                        }
                    }
                    else {
                        for (Expression column : groupingElement.getExpressions()) {
                            analyzeExpression(column, scope);
                            if (!analysis.getColumnReferences().contains(NodeRef.of(column))) {
                                throw semanticException(INVALID_COLUMN_REFERENCE, column, "GROUP BY expression must be a column reference: %s", column);
                            }

                            groupingExpressions.add(column);
                        }

                        if (groupingElement instanceof Cube) {
                            Set<FieldId> cube = groupingElement.getExpressions().stream()
                                    .map(NodeRef::of)
                                    .map(analysis.getColumnReferenceFields()::get)
                                    .collect(toImmutableSet());

                            cubes.add(cube);
                        }
                        else if (groupingElement instanceof Rollup) {
                            List<FieldId> rollup = groupingElement.getExpressions().stream()
                                    .map(NodeRef::of)
                                    .map(analysis.getColumnReferenceFields()::get)
                                    .collect(toImmutableList());

                            rollups.add(rollup);
                        }
                        else if (groupingElement instanceof GroupingSets) {
                            List<Set<FieldId>> groupingSets = ((GroupingSets) groupingElement).getSets().stream()
                                    .map(set -> set.stream()
                                            .map(NodeRef::of)
                                            .map(analysis.getColumnReferenceFields()::get)
                                            .collect(toImmutableSet()))
                                    .collect(toImmutableList());

                            sets.add(groupingSets);
                        }
                    }
                }

                List<Expression> expressions = groupingExpressions.build();
                for (Expression expression : expressions) {
                    Type type = analysis.getType(expression);
                    if (!type.isComparable()) {
                        throw semanticException(TYPE_MISMATCH, node, "%s is not comparable, and therefore cannot be used in GROUP BY", type);
                    }
                }

                analysis.setGroupByExpressions(node, expressions);
                analysis.setGroupingSets(node, new Analysis.GroupingSetAnalysis(cubes.build(), rollups.build(), sets.build(), complexExpressions.build()));

                return expressions;
            }

            if (hasAggregates(node) || node.getHaving().isPresent()) {
                analysis.setGroupByExpressions(node, ImmutableList.of());
            }

            return ImmutableList.of();
        }

        private boolean hasAggregates(QuerySpecification node)
        {
            List<Node> toExtract = ImmutableList.<Node>builder()
                    .addAll(node.getSelect().getSelectItems())
                    .addAll(getSortItemsFromOrderBy(node.getOrderBy()))
                    .build();

            List<FunctionCall> aggregates = extractAggregateFunctions(toExtract, metadata);

            return !aggregates.isEmpty();
        }

        private Scope computeAndAssignOutputScope(QuerySpecification node, Optional<Scope> scope, Scope sourceScope)
        {
            ImmutableList.Builder<Field> outputFields = ImmutableList.builder();

            for (SelectItem item : node.getSelect().getSelectItems()) {
                if (item instanceof AllColumns) {
                    // expand * and T.*
                    Optional<QualifiedName> starPrefix = ((AllColumns) item).getTarget()
                            .map(target -> {
                                if (target instanceof DereferenceExpression) {
                                    return DereferenceExpression.getQualifiedName((DereferenceExpression) target);
                                }
                                if (target instanceof Identifier) {
                                    return QualifiedName.of(ImmutableList.of((Identifier) target));
                                }
                                throw semanticException(NOT_SUPPORTED, item, "Only identifier chains supported with .*");
                            });

                    for (Field field : sourceScope.getRelationType().resolveFieldsWithPrefix(starPrefix)) {
                        outputFields.add(Field.newUnqualified(field.getName(), field.getType(), field.getOriginTable(), field.getOriginColumnName(), false));
                    }
                }
                else if (item instanceof SingleColumn) {
                    SingleColumn column = (SingleColumn) item;

                    Expression expression = column.getExpression();
                    Optional<Identifier> field = column.getAlias();

                    Optional<QualifiedObjectName> originTable = Optional.empty();
                    Optional<String> originColumn = Optional.empty();
                    QualifiedName name = null;

                    if (expression instanceof Identifier) {
                        name = QualifiedName.of(((Identifier) expression).getValue());
                    }
                    else if (expression instanceof DereferenceExpression) {
                        name = DereferenceExpression.getQualifiedName((DereferenceExpression) expression);
                    }

                    if (name != null) {
                        List<Field> matchingFields = sourceScope.getRelationType().resolveFields(name);
                        if (!matchingFields.isEmpty()) {
                            originTable = matchingFields.get(0).getOriginTable();
                            originColumn = matchingFields.get(0).getOriginColumnName();
                        }
                    }

                    if (!field.isPresent()) {
                        if (name != null) {
                            field = Optional.of(getLast(name.getOriginalParts()));
                        }
                    }

                    outputFields.add(Field.newUnqualified(field.map(Identifier::getValue), analysis.getType(expression), originTable, originColumn, column.getAlias().isPresent())); // TODO don't use analysis as a side-channel. Use outputExpressions to look up the type
                }
                else {
                    throw new IllegalArgumentException("Unsupported SelectItem type: " + item.getClass().getName());
                }
            }

            return createAndAssignScope(node, scope, outputFields.build());
        }

        private Scope computeAndAssignOrderByScope(OrderBy node, Scope sourceScope, Scope outputScope)
        {
            // ORDER BY should "see" both output and FROM fields during initial analysis and non-aggregation query planning
            Scope orderByScope = Scope.builder()
                    .withParent(sourceScope)
                    .withRelationType(outputScope.getRelationId(), outputScope.getRelationType())
                    .build();
            analysis.setScope(node, orderByScope);
            return orderByScope;
        }

        private void computeAndAssignOrderByScopeWithAggregation(OrderBy node, Scope sourceScope, Scope outputScope, List<FunctionCall> aggregations, List<Expression> groupByExpressions, List<GroupingOperation> groupingOperations)
        {
            // This scope is only used for planning. When aggregation is present then
            // only output fields, groups and aggregation expressions should be visible from ORDER BY expression
            ImmutableList.Builder<Expression> orderByAggregationExpressionsBuilder = ImmutableList.<Expression>builder()
                    .addAll(groupByExpressions)
                    .addAll(aggregations)
                    .addAll(groupingOperations);

            // Don't add aggregate complex expressions that contains references to output column because the names would clash in TranslationMap during planning.
            List<Expression> orderByExpressionsReferencingOutputScope = AstUtils.preOrder(node)
                    .filter(Expression.class::isInstance)
                    .map(Expression.class::cast)
                    .filter(expression -> hasReferencesToScope(expression, analysis, outputScope))
                    .collect(toImmutableList());
            List<Expression> orderByAggregationExpressions = orderByAggregationExpressionsBuilder.build().stream()
                    .filter(expression -> !orderByExpressionsReferencingOutputScope.contains(expression) || analysis.isColumnReference(expression))
                    .collect(toImmutableList());

            // generate placeholder fields
            Set<Field> seen = new HashSet<>();
            List<Field> orderByAggregationSourceFields = orderByAggregationExpressions.stream()
                    .map(expression -> {
                        // generate qualified placeholder field for GROUP BY expressions that are column references
                        Optional<Field> sourceField = sourceScope.tryResolveField(expression)
                                .filter(resolvedField -> seen.add(resolvedField.getField()))
                                .map(ResolvedField::getField);
                        return sourceField
                                .orElse(Field.newUnqualified(Optional.empty(), analysis.getType(expression)));
                    })
                    .collect(toImmutableList());

            Scope orderByAggregationScope = Scope.builder()
                    .withRelationType(RelationId.anonymous(), new RelationType(orderByAggregationSourceFields))
                    .build();

            Scope orderByScope = Scope.builder()
                    .withParent(orderByAggregationScope)
                    .withRelationType(outputScope.getRelationId(), outputScope.getRelationType())
                    .build();
            analysis.setScope(node, orderByScope);
            analysis.setOrderByAggregates(node, orderByAggregationExpressions);
        }

        private List<Expression> analyzeSelect(QuerySpecification node, Scope scope)
        {
            ImmutableList.Builder<Expression> outputExpressionBuilder = ImmutableList.builder();

            for (SelectItem item : node.getSelect().getSelectItems()) {
                if (item instanceof AllColumns) {
                    analyzeSelectAllColumns((AllColumns) item, node, scope, outputExpressionBuilder);
                }
                else if (item instanceof SingleColumn) {
                    analyzeSelectSingleColumn((SingleColumn) item, node, scope, outputExpressionBuilder);
                }
                else {
                    throw new IllegalArgumentException("Unsupported SelectItem type: " + item.getClass().getName());
                }
            }

            List<Expression> result = outputExpressionBuilder.build();
            analysis.setOutputExpressions(node, result);

            return result;
        }

        private void analyzeSelectAllColumns(
                AllColumns allColumns,
                QuerySpecification node,
                Scope scope,
                ImmutableList.Builder<Expression> outputExpressionBuilder)
        {
            // expand * and T.*
            Optional<QualifiedName> starPrefix = allColumns.getTarget()
                    .map(target -> {
                        if (target instanceof DereferenceExpression) {
                            return DereferenceExpression.getQualifiedName((DereferenceExpression) target);
                        }
                        if (target instanceof Identifier) {
                            return QualifiedName.of(ImmutableList.of((Identifier) target));
                        }
                        throw semanticException(NOT_SUPPORTED, allColumns, "Only identifier chains supported with .*");
                    });

            if (!allColumns.getAliases().isEmpty()) {
                throw semanticException(NOT_SUPPORTED, allColumns, "Column aliases not supported");
            }

            RelationType relationType = scope.getRelationType();
            List<Field> fields = relationType.resolveFieldsWithPrefix(starPrefix);
            if (fields.isEmpty()) {
                if (starPrefix.isPresent()) {
                    throw semanticException(TABLE_NOT_FOUND, allColumns, "Table '%s' not found", starPrefix.get());
                }
                if (!node.getFrom().isPresent()) {
                    throw semanticException(COLUMN_NOT_FOUND, allColumns, "SELECT * not allowed in queries without FROM clause");
                }
                throw semanticException(COLUMN_NOT_FOUND, allColumns, "SELECT * not allowed from relation that has no columns");
            }

            for (Field field : fields) {
                int fieldIndex = relationType.indexOf(field);
                FieldReference expression = new FieldReference(fieldIndex);
                outputExpressionBuilder.add(expression);
                ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, scope);

                Type type = expressionAnalysis.getType(expression);
                if (node.getSelect().isDistinct() && !type.isComparable()) {
                    throw semanticException(TYPE_MISMATCH, node.getSelect(), "DISTINCT can only be applied to comparable types (actual: %s)", type);
                }
            }
        }

        private void analyzeSelectSingleColumn(
                SingleColumn singleColumn,
                QuerySpecification node,
                Scope scope,
                ImmutableList.Builder<Expression> outputExpressionBuilder)
        {
            ExpressionAnalysis expressionAnalysis = analyzeExpression(singleColumn.getExpression(), scope);
            analysis.recordSubqueries(node, expressionAnalysis);
            outputExpressionBuilder.add(singleColumn.getExpression());

            Type type = expressionAnalysis.getType(singleColumn.getExpression());
            if (node.getSelect().isDistinct() && !type.isComparable()) {
                throw semanticException(
                        TYPE_MISMATCH, node.getSelect(),
                        "DISTINCT can only be applied to comparable types (actual: %s): %s",
                        type,
                        singleColumn.getExpression());
            }
        }

        public void analyzeWhere(Node node, Scope scope, Expression predicate)
        {
            Analyzer.verifyNoAggregateWindowOrGroupingFunctions(metadata, predicate, "WHERE clause");

            ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);
            analysis.recordSubqueries(node, expressionAnalysis);

            Type predicateType = expressionAnalysis.getType(predicate);
            if (!predicateType.equals(BOOLEAN)) {
                if (!predicateType.equals(UNKNOWN)) {
                    throw semanticException(TYPE_MISMATCH, predicate, "WHERE clause must evaluate to a boolean: actual type %s", predicateType);
                }
                // coerce null to boolean
                analysis.addCoercion(predicate, BOOLEAN, false);
            }

            analysis.setWhere(node, predicate);
        }

        private Scope analyzeFrom(QuerySpecification node, Optional<Scope> scope)
        {
            if (node.getFrom().isPresent()) {
                return process(node.getFrom().get(), scope);
            }

            return createScope(scope);
        }

        private void analyzeGroupingOperations(QuerySpecification node, List<Expression> outputExpressions, List<Expression> orderByExpressions)
        {
            List<GroupingOperation> groupingOperations = extractExpressions(Iterables.concat(outputExpressions, orderByExpressions), GroupingOperation.class);
            boolean isGroupingOperationPresent = !groupingOperations.isEmpty();

            if (isGroupingOperationPresent && !node.getGroupBy().isPresent()) {
                throw semanticException(
                        MISSING_GROUP_BY,
                        node,
                        "A GROUPING() operation can only be used with a corresponding GROUPING SET/CUBE/ROLLUP/GROUP BY clause");
            }

            analysis.setGroupingOperations(node, groupingOperations);
        }

        private void analyzeAggregations(
                QuerySpecification node,
                Scope sourceScope,
                Optional<Scope> orderByScope,
                List<Expression> groupByExpressions,
                List<Expression> outputExpressions,
                List<Expression> orderByExpressions)
        {
            checkState(orderByExpressions.isEmpty() || orderByScope.isPresent(), "non-empty orderByExpressions list without orderByScope provided");

            List<FunctionCall> aggregates = extractAggregateFunctions(Iterables.concat(outputExpressions, orderByExpressions), metadata);
            analysis.setAggregates(node, aggregates);

            if (analysis.isAggregation(node)) {
                // ensure SELECT, ORDER BY and HAVING are constant with respect to group
                // e.g, these are all valid expressions:
                //     SELECT f(a) GROUP BY a
                //     SELECT f(a + 1) GROUP BY a + 1
                //     SELECT a + sum(b) GROUP BY a
                List<Expression> distinctGroupingColumns = ImmutableSet.copyOf(groupByExpressions).asList();

                for (Expression expression : outputExpressions) {
                    verifySourceAggregations(distinctGroupingColumns, sourceScope, expression, metadata, analysis);
                }

                for (Expression expression : orderByExpressions) {
                    verifyOrderByAggregations(distinctGroupingColumns, sourceScope, orderByScope.get(), expression, metadata, analysis);
                }
            }
        }

        private RelationType analyzeView(Query query, QualifiedObjectName name, Optional<String> catalog, Optional<String> schema, Optional<String> owner, Table node)
        {
            try {
                // run view as view owner if set; otherwise, run as session user
                Identity identity;
                AccessControl viewAccessControl;
                if (owner.isPresent() && !owner.get().equals(session.getIdentity().getUser())) {
                    identity = Identity.ofUser(owner.get());
                    viewAccessControl = new ViewAccessControl(accessControl);
                }
                else {
                    identity = session.getIdentity();
                    viewAccessControl = accessControl;
                }

                // TODO: record path in view definition (?) (check spec) and feed it into the session object we use to evaluate the query defined by the view
                Session viewSession = Session.builder(metadata.getSessionPropertyManager())
                        .setQueryId(session.getQueryId())
                        .setTransactionId(session.getTransactionId().orElse(null))
                        .setIdentity(identity)
                        .setSource(session.getSource().orElse(null))
                        .setCatalog(catalog.orElse(null))
                        .setSchema(schema.orElse(null))
                        .setPath(session.getPath())
                        .setTimeZoneKey(session.getTimeZoneKey())
                        .setLocale(session.getLocale())
                        .setRemoteUserAddress(session.getRemoteUserAddress().orElse(null))
                        .setUserAgent(session.getUserAgent().orElse(null))
                        .setClientInfo(session.getClientInfo().orElse(null))
                        .setStartTime(session.getStartTime())
                        .build();

                StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, viewAccessControl, viewSession, warningCollector);
                Scope queryScope = analyzer.analyze(query, Scope.create());
                return queryScope.getRelationType().withAlias(name.getObjectName(), null);
            }
            catch (RuntimeException e) {
                throw semanticException(INVALID_VIEW, node, e, "Failed analyzing stored view '%s': %s", name, e.getMessage());
            }
        }

        private Query parseView(String view, QualifiedObjectName name, Node node)
        {
            try {
                return (Query) sqlParser.createStatement(view, createParsingOptions(session));
            }
            catch (ParsingException e) {
                throw semanticException(INVALID_VIEW, node, e, "Failed parsing stored view '%s': %s", name, e.getMessage());
            }
        }

        private boolean isViewStale(List<ViewColumn> columns, Collection<Field> fields, QualifiedObjectName name, Node node)
        {
            if (columns.size() != fields.size()) {
                return true;
            }

            List<Field> fieldList = ImmutableList.copyOf(fields);
            for (int i = 0; i < columns.size(); i++) {
                ViewColumn column = columns.get(i);
                Type type = getViewColumnType(column, name, node);
                Field field = fieldList.get(i);
                if (!column.getName().equalsIgnoreCase(field.getName().orElse(null)) ||
                        !typeCoercion.canCoerce(field.getType(), type)) {
                    return true;
                }
            }

            return false;
        }

        private Type getViewColumnType(ViewColumn column, QualifiedObjectName name, Node node)
        {
            try {
                return metadata.getType(column.getType());
            }
            catch (TypeNotFoundException e) {
                throw semanticException(INVALID_VIEW, node, e, "Unknown type '%s' for column '%s' in view: %s", column.getType(), column.getName(), name);
            }
        }

        private ExpressionAnalysis analyzeExpression(Expression expression, Scope scope)
        {
            return ExpressionAnalyzer.analyzeExpression(
                    session,
                    metadata,
                    accessControl,
                    sqlParser,
                    scope,
                    analysis,
                    expression,
                    WarningCollector.NOOP);
        }

        private List<Expression> descriptorToFields(Scope scope)
        {
            ImmutableList.Builder<Expression> builder = ImmutableList.builder();
            for (int fieldIndex = 0; fieldIndex < scope.getRelationType().getAllFieldCount(); fieldIndex++) {
                FieldReference expression = new FieldReference(fieldIndex);
                builder.add(expression);
                analyzeExpression(expression, scope);
            }
            return builder.build();
        }

        private Scope analyzeWith(Query node, Optional<Scope> scope)
        {
            // analyze WITH clause
            if (!node.getWith().isPresent()) {
                return createScope(scope);
            }
            With with = node.getWith().get();
            if (with.isRecursive()) {
                throw semanticException(NOT_SUPPORTED, with, "Recursive WITH queries are not supported");
            }

            Scope.Builder withScopeBuilder = scopeBuilder(scope);
            for (WithQuery withQuery : with.getQueries()) {
                Query query = withQuery.getQuery();
                process(query, withScopeBuilder.build());

                String name = withQuery.getName().getValue().toLowerCase(ENGLISH);
                if (withScopeBuilder.containsNamedQuery(name)) {
                    throw semanticException(DUPLICATE_NAMED_QUERY, withQuery, "WITH query name '%s' specified more than once", name);
                }

                // check if all or none of the columns are explicitly alias
                if (withQuery.getColumnNames().isPresent()) {
                    List<Identifier> columnNames = withQuery.getColumnNames().get();
                    RelationType queryDescriptor = analysis.getOutputDescriptor(query);
                    if (columnNames.size() != queryDescriptor.getVisibleFieldCount()) {
                        throw semanticException(MISMATCHED_COLUMN_ALIASES, withQuery, "WITH column alias list has %s entries but WITH query(%s) has %s columns", columnNames.size(), name, queryDescriptor.getVisibleFieldCount());
                    }
                }

                withScopeBuilder.withNamedQuery(name, withQuery);
            }

            Scope withScope = withScopeBuilder.build();
            analysis.setScope(with, withScope);
            return withScope;
        }

        private void verifySelectDistinct(QuerySpecification node, List<Expression> outputExpressions)
        {
            for (SortItem item : node.getOrderBy().get().getSortItems()) {
                Expression expression = item.getSortKey();

                if (expression instanceof LongLiteral) {
                    continue;
                }

                Expression rewrittenOrderByExpression = ExpressionTreeRewriter.rewriteWith(new OrderByExpressionRewriter(extractNamedOutputExpressions(node.getSelect())), expression);
                int index = outputExpressions.indexOf(rewrittenOrderByExpression);
                if (index == -1) {
                    throw semanticException(EXPRESSION_NOT_IN_DISTINCT, node.getSelect(), "For SELECT DISTINCT, ORDER BY expressions must appear in select list");
                }

                if (!isDeterministic(expression)) {
                    throw semanticException(EXPRESSION_NOT_IN_DISTINCT, expression, "Non deterministic ORDER BY expression is not supported with SELECT DISTINCT");
                }
            }
        }

        private List<Expression> analyzeOrderBy(Node node, List<SortItem> sortItems, Scope orderByScope)
        {
            ImmutableList.Builder<Expression> orderByFieldsBuilder = ImmutableList.builder();

            for (SortItem item : sortItems) {
                Expression expression = item.getSortKey();

                if (expression instanceof LongLiteral) {
                    // this is an ordinal in the output tuple

                    long ordinal = ((LongLiteral) expression).getValue();
                    if (ordinal < 1 || ordinal > orderByScope.getRelationType().getVisibleFieldCount()) {
                        throw semanticException(INVALID_COLUMN_REFERENCE, expression, "ORDER BY position %s is not in select list", ordinal);
                    }

                    expression = new FieldReference(toIntExact(ordinal - 1));
                }

                ExpressionAnalysis expressionAnalysis = ExpressionAnalyzer.analyzeExpression(session,
                        metadata,
                        accessControl,
                        sqlParser,
                        orderByScope,
                        analysis,
                        expression,
                        WarningCollector.NOOP);
                analysis.recordSubqueries(node, expressionAnalysis);

                Type type = analysis.getType(expression);
                if (!type.isOrderable()) {
                    throw semanticException(TYPE_MISMATCH, node, "Type %s is not orderable, and therefore cannot be used in ORDER BY: %s", type, expression);
                }

                orderByFieldsBuilder.add(expression);
            }

            return orderByFieldsBuilder.build();
        }

        private void analyzeOffset(Offset node)
        {
            long rowCount;
            try {
                rowCount = Long.parseLong(node.getRowCount());
            }
            catch (NumberFormatException e) {
                throw semanticException(TYPE_MISMATCH, node, "Invalid OFFSET row count: %s", node.getRowCount());
            }
            if (rowCount < 0) {
                throw semanticException(NUMERIC_VALUE_OUT_OF_RANGE, node, "OFFSET row count must be greater or equal to 0 (actual value: %s)", rowCount);
            }
            analysis.setOffset(node, rowCount);
        }

        /**
         * @return true if the Query / QuerySpecification containing the analyzed
         * Limit or FetchFirst, must contain orderBy (i.e., for FetchFirst with ties).
         */
        private boolean analyzeLimit(Node node)
        {
            checkState(
                    node instanceof FetchFirst || node instanceof Limit,
                    "Invalid limit node type. Expected: FetchFirst or Limit. Actual: %s", node.getClass().getName());
            if (node instanceof FetchFirst) {
                return analyzeLimit((FetchFirst) node);
            }
            else {
                return analyzeLimit((Limit) node);
            }
        }

        private boolean analyzeLimit(FetchFirst node)
        {
            if (!node.getRowCount().isPresent()) {
                analysis.setLimit(node, 1);
            }
            else {
                long rowCount;
                try {
                    rowCount = Long.parseLong(node.getRowCount().get());
                }
                catch (NumberFormatException e) {
                    throw semanticException(TYPE_MISMATCH, node, "Invalid FETCH FIRST row count: %s", node.getRowCount().get());
                }
                if (rowCount <= 0) {
                    throw semanticException(NUMERIC_VALUE_OUT_OF_RANGE, node, "FETCH FIRST row count must be positive (actual value: %s)", rowCount);
                }
                analysis.setLimit(node, rowCount);
            }

            return node.isWithTies();
        }

        private boolean analyzeLimit(Limit node)
        {
            if (node.getLimit().equalsIgnoreCase("all")) {
                analysis.setLimit(node, OptionalLong.empty());
            }
            else {
                long rowCount;
                try {
                    rowCount = Long.parseLong(node.getLimit());
                }
                catch (NumberFormatException e) {
                    throw semanticException(TYPE_MISMATCH, node, "Invalid LIMIT row count: %s", node.getLimit());
                }
                if (rowCount < 0) {
                    throw semanticException(NUMERIC_VALUE_OUT_OF_RANGE, node, "LIMIT row count must be greater or equal to 0 (actual value: %s)", rowCount);
                }
                analysis.setLimit(node, rowCount);
            }

            return false;
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope)
        {
            return createAndAssignScope(node, parentScope, emptyList());
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, Field... fields)
        {
            return createAndAssignScope(node, parentScope, new RelationType(fields));
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, List<Field> fields)
        {
            return createAndAssignScope(node, parentScope, new RelationType(fields));
        }

        private Scope createAndAssignScope(Node node, Optional<Scope> parentScope, RelationType relationType)
        {
            Scope scope = scopeBuilder(parentScope)
                    .withRelationType(RelationId.of(node), relationType)
                    .build();

            analysis.setScope(node, scope);
            return scope;
        }

        private Scope createScope(Optional<Scope> parentScope)
        {
            return scopeBuilder(parentScope).build();
        }

        private Scope.Builder scopeBuilder(Optional<Scope> parentScope)
        {
            Scope.Builder scopeBuilder = Scope.builder();

            if (parentScope.isPresent()) {
                // parent scope represents local query scope hierarchy. Local query scope
                // hierarchy should have outer query scope as ancestor already.
                scopeBuilder.withParent(parentScope.get());
            }
            else {
                outerQueryScope.ifPresent(scopeBuilder::withOuterQueryParent);
            }

            return scopeBuilder;
        }
    }

    private static boolean hasScopeAsLocalParent(Scope root, Scope parent)
    {
        Scope scope = root;
        while (scope.getLocalParent().isPresent()) {
            scope = scope.getLocalParent().get();
            if (scope.equals(parent)) {
                return true;
            }
        }

        return false;
    }
}
