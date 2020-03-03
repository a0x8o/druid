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
package com.facebook.presto.verifier.framework;

import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.metadata.CatalogManager;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.metadata.HandleJsonModule;
import com.facebook.presto.spi.block.BlockEncoding;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.parser.SqlParserOptions;
import com.facebook.presto.sql.tree.Property;
import com.facebook.presto.transaction.ForTransactionManager;
import com.facebook.presto.transaction.InMemoryTransactionManager;
import com.facebook.presto.transaction.TransactionManager;
import com.facebook.presto.transaction.TransactionManagerConfig;
import com.facebook.presto.type.TypeRegistry;
import com.facebook.presto.verifier.annotation.ForControl;
import com.facebook.presto.verifier.annotation.ForTest;
import com.facebook.presto.verifier.checksum.ChecksumValidator;
import com.facebook.presto.verifier.checksum.FloatingPointColumnValidator;
import com.facebook.presto.verifier.checksum.OrderableArrayColumnValidator;
import com.facebook.presto.verifier.checksum.SimpleColumnValidator;
import com.facebook.presto.verifier.prestoaction.SqlExceptionClassifier;
import com.facebook.presto.verifier.prestoaction.VerificationPrestoActionModule;
import com.facebook.presto.verifier.resolver.FailureResolverFactory;
import com.facebook.presto.verifier.resolver.FailureResolverModule;
import com.facebook.presto.verifier.retry.ForClusterConnection;
import com.facebook.presto.verifier.retry.ForPresto;
import com.facebook.presto.verifier.retry.RetryConfig;
import com.facebook.presto.verifier.rewrite.VerificationQueryRewriterModule;
import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import io.airlift.configuration.AbstractConfigurationAwareModule;

import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class VerifierModule
        extends AbstractConfigurationAwareModule
{
    private final SqlParserOptions sqlParserOptions;
    private final List<Class<? extends Predicate<SourceQuery>>> customQueryFilterClasses;
    private final SqlExceptionClassifier exceptionClassifier;
    private final List<FailureResolverFactory> failureResolverFactories;
    private final List<Property> tablePropertyOverrides;

    public VerifierModule(
            SqlParserOptions sqlParserOptions,
            List<Class<? extends Predicate<SourceQuery>>> customQueryFilterClasses,
            SqlExceptionClassifier exceptionClassifier,
            List<FailureResolverFactory> failureResolverFactories,
            List<Property> tablePropertyOverrides)
    {
        this.sqlParserOptions = requireNonNull(sqlParserOptions, "sqlParserOptions is null");
        this.customQueryFilterClasses = ImmutableList.copyOf(customQueryFilterClasses);
        this.exceptionClassifier = requireNonNull(exceptionClassifier, "exceptionClassifier is null");
        this.failureResolverFactories = requireNonNull(failureResolverFactories, "failureResolverFactories is null");
        this.tablePropertyOverrides = requireNonNull(tablePropertyOverrides, "tablePropertyOverrides is null");
    }

    protected final void setup(Binder binder)
    {
        configBinder(binder).bindConfig(VerifierConfig.class);
        configBinder(binder).bindConfig(QueryConfigurationOverridesConfig.class, ForControl.class, "control");
        configBinder(binder).bindConfig(QueryConfigurationOverridesConfig.class, ForTest.class, "test");
        binder.bind(QueryConfigurationOverrides.class).annotatedWith(ForControl.class).to(Key.get(QueryConfigurationOverridesConfig.class, ForControl.class)).in(SINGLETON);
        binder.bind(QueryConfigurationOverrides.class).annotatedWith(ForTest.class).to(Key.get(QueryConfigurationOverridesConfig.class, ForTest.class)).in(SINGLETON);

        configBinder(binder).bindConfig(RetryConfig.class, ForClusterConnection.class, "cluster-connection");
        configBinder(binder).bindConfig(RetryConfig.class, ForPresto.class, "presto");

        for (Class<? extends Predicate<SourceQuery>> customQueryFilterClass : customQueryFilterClasses) {
            binder.bind(customQueryFilterClass).in(SINGLETON);
        }

        // block encoding
        binder.bind(BlockEncodingSerde.class).to(BlockEncodingManager.class).in(Scopes.SINGLETON);
        newSetBinder(binder, BlockEncoding.class);

        // catalog
        binder.bind(CatalogManager.class).in(Scopes.SINGLETON);

        // function
        binder.bind(FunctionManager.class).in(SINGLETON);

        // handle resolver
        binder.install(new HandleJsonModule());

        // parser
        binder.bind(SqlParserOptions.class).toInstance(sqlParserOptions);
        binder.bind(SqlParser.class).in(SINGLETON);

        // transaction
        configBinder(binder).bindConfig(TransactionManagerConfig.class);

        // type
        configBinder(binder).bindConfig(FeaturesConfig.class);
        binder.bind(TypeManager.class).to(TypeRegistry.class).in(SINGLETON);
        newSetBinder(binder, Type.class);

        // verifier
        install(new VerificationPrestoActionModule(exceptionClassifier));
        install(new VerificationQueryRewriterModule());
        install(new FailureResolverModule(failureResolverFactories));
        binder.bind(VerificationManager.class).in(SINGLETON);
        binder.bind(VerificationFactory.class).in(SINGLETON);
        binder.bind(ChecksumValidator.class).in(SINGLETON);
        binder.bind(SimpleColumnValidator.class).in(SINGLETON);
        binder.bind(FloatingPointColumnValidator.class).in(SINGLETON);
        binder.bind(OrderableArrayColumnValidator.class).in(SINGLETON);
        binder.bind(new TypeLiteral<List<Predicate<SourceQuery>>>() {}).toProvider(new CustomQueryFilterProvider(customQueryFilterClasses));
        binder.bind(new TypeLiteral<List<Property>>() {}).toInstance(tablePropertyOverrides);
    }

    private static class CustomQueryFilterProvider
            implements Provider<List<Predicate<SourceQuery>>>
    {
        private final List<Class<? extends Predicate<SourceQuery>>> customQueryFilterClasses;
        private Injector injector;

        public CustomQueryFilterProvider(List<Class<? extends Predicate<SourceQuery>>> customQueryFilterClasses)
        {
            this.customQueryFilterClasses = ImmutableList.copyOf(customQueryFilterClasses);
        }

        @Inject
        public void setInjector(Injector injector)
        {
            this.injector = injector;
        }

        @Override
        public List<Predicate<SourceQuery>> get()
        {
            ImmutableList.Builder<Predicate<SourceQuery>> customVerificationFilters = ImmutableList.builder();
            for (Class<? extends Predicate<SourceQuery>> filterClass : customQueryFilterClasses) {
                customVerificationFilters.add(injector.getInstance(filterClass));
            }
            return customVerificationFilters.build();
        }
    }

    @Provides
    @Singleton
    @ForTransactionManager
    public static ScheduledExecutorService createTransactionIdleCheckExecutor()
    {
        return newSingleThreadScheduledExecutor(daemonThreadsNamed("transaction-idle-check"));
    }

    @Provides
    @Singleton
    @ForTransactionManager
    public static ExecutorService createTransactionFinishingExecutor()
    {
        return newCachedThreadPool(daemonThreadsNamed("transaction-finishing-%s"));
    }

    @Provides
    @Singleton
    public static TransactionManager createTransactionManager(
            TransactionManagerConfig config,
            CatalogManager catalogManager,
            @ForTransactionManager ScheduledExecutorService idleCheckExecutor,
            @ForTransactionManager ExecutorService finishingExecutor)
    {
        return InMemoryTransactionManager.create(config, idleCheckExecutor, catalogManager, finishingExecutor);
    }
}
