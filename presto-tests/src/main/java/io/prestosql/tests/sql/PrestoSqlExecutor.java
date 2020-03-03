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
package io.prestosql.tests.sql;

import io.prestosql.Session;
import io.prestosql.testing.QueryRunner;

import static java.util.Objects.requireNonNull;

public class PrestoSqlExecutor
        implements SqlExecutor
{
    private final QueryRunner queryRunner;
    private final Session session;

    public PrestoSqlExecutor(QueryRunner queryRunner)
    {
        this(queryRunner, queryRunner.getDefaultSession());
    }

    public PrestoSqlExecutor(QueryRunner queryRunner, Session session)
    {
        this.queryRunner = requireNonNull(queryRunner, "queryRunner is null");
        this.session = requireNonNull(session, "session is null");
    }

    @Override
    public void execute(String sql)
    {
        try {
            queryRunner.execute(session, sql);
        }
        catch (Throwable e) {
            throw new RuntimeException("Error executing sql:\n" + sql, e);
        }
    }
}
