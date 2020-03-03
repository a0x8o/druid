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
package io.prestosql.operator.scalar;

import io.airlift.json.JsonCodec;
import io.prestosql.client.FailureInfo;
import io.prestosql.spi.PrestoException;
import io.prestosql.testing.LocalQueryRunner;
import io.prestosql.util.Failures;
import org.testng.annotations.Test;

import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.type.UnknownType.UNKNOWN;

public class TestFailureFunction
        extends AbstractTestFunctions
{
    private static final String FAILURE_INFO = JsonCodec.jsonCodec(FailureInfo.class).toJson(Failures.toFailure(new RuntimeException("fail me")).toFailureInfo());

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = "fail me")
    public void testFailure()
    {
        assertFunction("fail(json_parse('" + FAILURE_INFO + "'))", UNKNOWN, null);
    }

    @Test(expectedExceptions = PrestoException.class, expectedExceptionsMessageRegExp = "Division by zero")
    public void testQuery()
    {
        // The other test does not exercise this function during execution (i.e. inside a page processor).
        // It only verifies constant folding works.
        try (LocalQueryRunner runner = new LocalQueryRunner(TEST_SESSION)) {
            runner.execute("select if(x, 78, 0/0) from (values rand() >= 0, rand() < 0) t(x)");
        }
    }
}
