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
package io.prestosql.server;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.prestosql.execution.QueryManager;
import io.prestosql.execution.resourcegroups.NoOpResourceGroupManager;
import io.prestosql.execution.resourcegroups.ResourceGroupManager;
import io.prestosql.failuredetector.FailureDetector;
import io.prestosql.failuredetector.NoOpFailureDetector;
import io.prestosql.transaction.NoOpTransactionManager;
import io.prestosql.transaction.TransactionManager;

import javax.inject.Singleton;

import static com.google.common.reflect.Reflection.newProxy;

public class WorkerModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        // Install no-op session supplier on workers, since only coordinators create sessions.
        binder.bind(SessionSupplier.class).to(NoOpSessionSupplier.class).in(Scopes.SINGLETON);

        // Install no-op resource group manager on workers, since only coordinators manage resource groups.
        binder.bind(ResourceGroupManager.class).to(NoOpResourceGroupManager.class).in(Scopes.SINGLETON);

        // Install no-op transaction manager on workers, since only coordinators manage transactions.
        binder.bind(TransactionManager.class).to(NoOpTransactionManager.class).in(Scopes.SINGLETON);

        // Install no-op failure detector on workers, since only coordinators need global node selection.
        binder.bind(FailureDetector.class).to(NoOpFailureDetector.class).in(Scopes.SINGLETON);

        // HACK: this binding is needed by SystemConnectorModule, but will only be used on the coordinator
        binder.bind(QueryManager.class).toInstance(newProxy(QueryManager.class, (proxy, method, args) -> {
            throw new UnsupportedOperationException();
        }));
    }

    @Provides
    @Singleton
    public static ResourceGroupManager<?> getResourceGroupManager(@SuppressWarnings("rawtypes") ResourceGroupManager manager)
    {
        return manager;
    }
}
