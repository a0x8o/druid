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
package io.prestosql.sql.planner;

import io.prestosql.connector.CatalogName;
import io.prestosql.execution.Output;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.PlanVisitor;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.TableWriterNode.CreateTarget;
import io.prestosql.sql.planner.plan.TableWriterNode.DeleteTarget;
import io.prestosql.sql.planner.plan.TableWriterNode.InsertTarget;
import io.prestosql.sql.planner.plan.TableWriterNode.WriterTarget;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

public class OutputExtractor
{
    public Optional<Output> extractOutput(PlanNode root)
    {
        Visitor visitor = new Visitor();
        root.accept(visitor, null);

        if (visitor.getCatalogName() == null) {
            return Optional.empty();
        }

        return Optional.of(new Output(
                visitor.getCatalogName(),
                visitor.getSchemaTableName().getSchemaName(),
                visitor.getSchemaTableName().getTableName()));
    }

    private static class Visitor
            extends PlanVisitor<Void, Void>
    {
        private CatalogName catalogName;
        private SchemaTableName schemaTableName;

        @Override
        public Void visitTableWriter(TableWriterNode node, Void context)
        {
            WriterTarget writerTarget = node.getTarget();

            if (writerTarget instanceof CreateTarget) {
                CreateTarget target = (CreateTarget) writerTarget;
                catalogName = target.getHandle().getCatalogName();
                checkState(schemaTableName == null || schemaTableName.equals(target.getSchemaTableName()),
                        "cannot have more than a single create, insert or delete in a query");
                schemaTableName = target.getSchemaTableName();
            }
            else if (writerTarget instanceof InsertTarget) {
                InsertTarget target = (InsertTarget) writerTarget;
                catalogName = target.getHandle().getCatalogName();
                checkState(schemaTableName == null || schemaTableName.equals(target.getSchemaTableName()),
                        "cannot have more than a single create, insert or delete in a query");
                schemaTableName = target.getSchemaTableName();
            }
            else if (writerTarget instanceof DeleteTarget) {
                DeleteTarget target = (DeleteTarget) writerTarget;
                catalogName = target.getHandle().getCatalogName();
                checkState(schemaTableName == null || schemaTableName.equals(target.getSchemaTableName()),
                        "cannot have more than a single create, insert or delete in a query");
                schemaTableName = target.getSchemaTableName();
            }

            return null;
        }

        @Override
        protected Void visitPlan(PlanNode node, Void context)
        {
            for (PlanNode child : node.getSources()) {
                child.accept(this, context);
            }
            return null;
        }

        public CatalogName getCatalogName()
        {
            return catalogName;
        }

        public SchemaTableName getSchemaTableName()
        {
            return schemaTableName;
        }
    }
}
