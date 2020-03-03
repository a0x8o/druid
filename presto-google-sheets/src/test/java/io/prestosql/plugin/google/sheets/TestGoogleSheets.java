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
package io.prestosql.plugin.google.sheets;

import io.prestosql.Session;
import io.prestosql.testing.QueryRunner;
import io.prestosql.tests.AbstractTestQueryFramework;
import io.prestosql.tests.DistributedQueryRunner;
import org.testng.annotations.Test;

import static io.prestosql.plugin.google.sheets.TestSheetsConfig.getProperties;
import static io.prestosql.plugin.google.sheets.TestSheetsPlugin.TEST_METADATA_SHEET_ID;
import static io.prestosql.plugin.google.sheets.TestSheetsPlugin.getTestCredentialsPath;
import static io.prestosql.testing.TestingSession.testSessionBuilder;

public class TestGoogleSheets
        extends AbstractTestQueryFramework
{
    protected static final String GOOGLE_SHEETS = "gsheets";

    public TestGoogleSheets()
    {
        super(() -> createQueryRunner());
    }

    private static Session createSession()
    {
        return testSessionBuilder()
                .setCatalog(GOOGLE_SHEETS)
                .setSchema("default")
                .build();
    }

    private static QueryRunner createQueryRunner()
    {
        QueryRunner queryRunner;
        try {
            SheetsPlugin sheetsPlugin = new SheetsPlugin();
            queryRunner = DistributedQueryRunner.builder(createSession()).build();
            queryRunner.installPlugin(sheetsPlugin);
            queryRunner.createCatalog(GOOGLE_SHEETS, GOOGLE_SHEETS, getProperties(getTestCredentialsPath(), TEST_METADATA_SHEET_ID, 1000, "5m"));
        }
        catch (Exception e) {
            throw new IllegalStateException(e.getMessage());
        }
        return queryRunner;
    }

    @Test
    public void testListTable()
    {
        assertQuery("show tables", "SELECT * FROM (VALUES 'metadata_table', 'number_text', 'table_with_duplicate_and_missing_column_names')");
    }

    @Test
    public void testDescTable()
    {
        assertQuery("desc number_text", "SELECT * FROM (VALUES('number','varchar','',''), ('text','varchar','',''))");
        assertQuery("desc metadata_table", "SELECT * FROM (VALUES('table name','varchar','',''), ('sheetid_sheetname','varchar','',''), "
                + "('owner','varchar','',''), ('notes','varchar','',''))");
    }

    @Test
    public void testSelectFromTable()
    {
        assertQuery("SELECT count(*) FROM number_text", "SELECT 5");
        assertQuery("SELECT number FROM number_text", "SELECT * FROM (VALUES '1','2','3','4','5')");
        assertQuery("SELECT text FROM number_text", "SELECT * FROM (VALUES 'one','two','three','four','five')");
        assertQuery("SELECT * FROM number_text", "SELECT * FROM (VALUES ('1','one'), ('2','two'), ('3','three'), ('4','four'), ('5','five'))");
    }

    @Test
    public void testSelectFromTableIgnoreCase()
    {
        assertQuery("SELECT count(*) FROM NUMBER_TEXT", "SELECT 5");
        assertQuery("SELECT number FROM Number_Text", "SELECT * FROM (VALUES '1','2','3','4','5')");
    }

    @Test
    public void testQueryingUnknownSchemaAndTable()
    {
        assertQueryFails("select * from gsheets.foo.bar", "line 1:15: Schema foo does not exist");
        assertQueryFails("select * from gsheets.default.foo_bar_table", "Sheet expression not found for table foo_bar_table");
    }

    @Test
    public void testTableWithRepeatedAndMissingColumnNames()
    {
        assertQuery("desc table_with_duplicate_and_missing_column_names", "SELECT * FROM (VALUES('a','varchar','','')," +
                " ('column_1','varchar','',''), ('column_2','varchar','',''), ('c','varchar','',''))");
    }
}
