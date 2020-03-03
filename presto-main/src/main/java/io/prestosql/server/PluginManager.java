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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.airlift.resolver.ArtifactResolver;
import io.airlift.resolver.DefaultArtifact;
import io.prestosql.connector.ConnectorManager;
import io.prestosql.eventlistener.EventListenerManager;
import io.prestosql.execution.resourcegroups.ResourceGroupManager;
import io.prestosql.metadata.MetadataManager;
import io.prestosql.security.AccessControlManager;
import io.prestosql.server.security.PasswordAuthenticatorManager;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.block.BlockEncoding;
import io.prestosql.spi.classloader.ThreadContextClassLoader;
import io.prestosql.spi.connector.ConnectorFactory;
import io.prestosql.spi.eventlistener.EventListenerFactory;
import io.prestosql.spi.resourcegroups.ResourceGroupConfigurationManagerFactory;
import io.prestosql.spi.security.PasswordAuthenticatorFactory;
import io.prestosql.spi.security.SystemAccessControlFactory;
import io.prestosql.spi.session.SessionPropertyConfigurationManagerFactory;
import io.prestosql.spi.type.ParametricType;
import io.prestosql.spi.type.Type;
import org.sonatype.aether.artifact.Artifact;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.metadata.FunctionExtractor.extractFunctions;
import static io.prestosql.server.PluginDiscovery.discoverPlugins;
import static io.prestosql.server.PluginDiscovery.writePluginServices;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class PluginManager
{
    private static final ImmutableList<String> SPI_PACKAGES = ImmutableList.<String>builder()
            .add("io.prestosql.spi.")
            .add("com.fasterxml.jackson.annotation.")
            .add("io.airlift.slice.")
            .add("io.airlift.units.")
            .add("org.openjdk.jol.")
            .build();

    private static final Logger log = Logger.get(PluginManager.class);

    private final ConnectorManager connectorManager;
    private final MetadataManager metadataManager;
    private final ResourceGroupManager<?> resourceGroupManager;
    private final AccessControlManager accessControlManager;
    private final PasswordAuthenticatorManager passwordAuthenticatorManager;
    private final EventListenerManager eventListenerManager;
    private final SessionPropertyDefaults sessionPropertyDefaults;
    private final ArtifactResolver resolver;
    private final File installedPluginsDir;
    private final List<String> plugins;
    private final AtomicBoolean pluginsLoading = new AtomicBoolean();
    private final AtomicBoolean pluginsLoaded = new AtomicBoolean();

    @Inject
    public PluginManager(
            NodeInfo nodeInfo,
            PluginManagerConfig config,
            ConnectorManager connectorManager,
            MetadataManager metadataManager,
            ResourceGroupManager<?> resourceGroupManager,
            AccessControlManager accessControlManager,
            PasswordAuthenticatorManager passwordAuthenticatorManager,
            EventListenerManager eventListenerManager,
            SessionPropertyDefaults sessionPropertyDefaults)
    {
        requireNonNull(nodeInfo, "nodeInfo is null");
        requireNonNull(config, "config is null");

        installedPluginsDir = config.getInstalledPluginsDir();
        if (config.getPlugins() == null) {
            this.plugins = ImmutableList.of();
        }
        else {
            this.plugins = ImmutableList.copyOf(config.getPlugins());
        }
        this.resolver = new ArtifactResolver(config.getMavenLocalRepository(), config.getMavenRemoteRepository());

        this.connectorManager = requireNonNull(connectorManager, "connectorManager is null");
        this.metadataManager = requireNonNull(metadataManager, "metadataManager is null");
        this.resourceGroupManager = requireNonNull(resourceGroupManager, "resourceGroupManager is null");
        this.accessControlManager = requireNonNull(accessControlManager, "accessControlManager is null");
        this.passwordAuthenticatorManager = requireNonNull(passwordAuthenticatorManager, "passwordAuthenticatorManager is null");
        this.eventListenerManager = requireNonNull(eventListenerManager, "eventListenerManager is null");
        this.sessionPropertyDefaults = requireNonNull(sessionPropertyDefaults, "sessionPropertyDefaults is null");
    }

    public void loadPlugins()
            throws Exception
    {
        if (!pluginsLoading.compareAndSet(false, true)) {
            return;
        }

        for (File file : listFiles(installedPluginsDir)) {
            if (file.isDirectory()) {
                loadPlugin(file.getAbsolutePath());
            }
        }

        for (String plugin : plugins) {
            loadPlugin(plugin);
        }

        metadataManager.verifyComparableOrderableContract();

        pluginsLoaded.set(true);
    }

    private void loadPlugin(String plugin)
            throws Exception
    {
        log.info("-- Loading plugin %s --", plugin);
        URLClassLoader pluginClassLoader = buildClassLoader(plugin);
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(pluginClassLoader)) {
            loadPlugin(pluginClassLoader);
        }
        log.info("-- Finished loading plugin %s --", plugin);
    }

    private void loadPlugin(URLClassLoader pluginClassLoader)
    {
        ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, pluginClassLoader);
        List<Plugin> plugins = ImmutableList.copyOf(serviceLoader);
        checkState(!plugins.isEmpty(), "No service providers of type %s", Plugin.class.getName());
        for (Plugin plugin : plugins) {
            log.info("Installing %s", plugin.getClass().getName());
            installPlugin(plugin);
        }
    }

    public void installPlugin(Plugin plugin)
    {
        for (BlockEncoding blockEncoding : plugin.getBlockEncodings()) {
            log.info("Registering block encoding %s", blockEncoding.getName());
            metadataManager.addBlockEncoding(blockEncoding);
        }

        for (Type type : plugin.getTypes()) {
            log.info("Registering type %s", type.getTypeSignature());
            metadataManager.addType(type);
        }

        for (ParametricType parametricType : plugin.getParametricTypes()) {
            log.info("Registering parametric type %s", parametricType.getName());
            metadataManager.addParametricType(parametricType);
        }

        for (ConnectorFactory connectorFactory : plugin.getConnectorFactories()) {
            log.info("Registering connector %s", connectorFactory.getName());
            connectorManager.addConnectorFactory(connectorFactory);
        }

        for (Class<?> functionClass : plugin.getFunctions()) {
            log.info("Registering functions from %s", functionClass.getName());
            metadataManager.addFunctions(extractFunctions(functionClass));
        }

        for (SessionPropertyConfigurationManagerFactory sessionConfigFactory : plugin.getSessionPropertyConfigurationManagerFactories()) {
            log.info("Registering session property configuration manager %s", sessionConfigFactory.getName());
            sessionPropertyDefaults.addConfigurationManagerFactory(sessionConfigFactory);
        }

        for (ResourceGroupConfigurationManagerFactory configurationManagerFactory : plugin.getResourceGroupConfigurationManagerFactories()) {
            log.info("Registering resource group configuration manager %s", configurationManagerFactory.getName());
            resourceGroupManager.addConfigurationManagerFactory(configurationManagerFactory);
        }

        for (SystemAccessControlFactory accessControlFactory : plugin.getSystemAccessControlFactories()) {
            log.info("Registering system access control %s", accessControlFactory.getName());
            accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        }

        for (PasswordAuthenticatorFactory authenticatorFactory : plugin.getPasswordAuthenticatorFactories()) {
            log.info("Registering password authenticator %s", authenticatorFactory.getName());
            passwordAuthenticatorManager.addPasswordAuthenticatorFactory(authenticatorFactory);
        }

        for (EventListenerFactory eventListenerFactory : plugin.getEventListenerFactories()) {
            log.info("Registering event listener %s", eventListenerFactory.getName());
            eventListenerManager.addEventListenerFactory(eventListenerFactory);
        }
    }

    private URLClassLoader buildClassLoader(String plugin)
            throws Exception
    {
        File file = new File(plugin);
        if (file.isFile() && (file.getName().equals("pom.xml") || file.getName().endsWith(".pom"))) {
            return buildClassLoaderFromPom(file);
        }
        if (file.isDirectory()) {
            return buildClassLoaderFromDirectory(file);
        }
        return buildClassLoaderFromCoordinates(plugin);
    }

    private URLClassLoader buildClassLoaderFromPom(File pomFile)
            throws Exception
    {
        List<Artifact> artifacts = resolver.resolvePom(pomFile);
        URLClassLoader classLoader = createClassLoader(artifacts, pomFile.getPath());

        Artifact artifact = artifacts.get(0);
        Set<String> plugins = discoverPlugins(artifact, classLoader);
        if (!plugins.isEmpty()) {
            writePluginServices(plugins, artifact.getFile());
        }

        return classLoader;
    }

    private URLClassLoader buildClassLoaderFromDirectory(File dir)
            throws Exception
    {
        log.info("Classpath for %s:", dir.getName());
        List<URL> urls = new ArrayList<>();
        for (File file : listFiles(dir)) {
            log.info("    %s", file);
            urls.add(file.toURI().toURL());
        }
        return createClassLoader(urls);
    }

    private URLClassLoader buildClassLoaderFromCoordinates(String coordinates)
            throws Exception
    {
        Artifact rootArtifact = new DefaultArtifact(coordinates);
        List<Artifact> artifacts = resolver.resolveArtifacts(rootArtifact);
        return createClassLoader(artifacts, rootArtifact.toString());
    }

    private URLClassLoader createClassLoader(List<Artifact> artifacts, String name)
            throws IOException
    {
        log.info("Classpath for %s:", name);
        List<URL> urls = new ArrayList<>();
        for (Artifact artifact : sortedArtifacts(artifacts)) {
            if (artifact.getFile() == null) {
                throw new RuntimeException("Could not resolve artifact: " + artifact);
            }
            File file = artifact.getFile().getCanonicalFile();
            log.info("    %s", file);
            urls.add(file.toURI().toURL());
        }
        return createClassLoader(urls);
    }

    private URLClassLoader createClassLoader(List<URL> urls)
    {
        ClassLoader parent = getClass().getClassLoader();
        return new PluginClassLoader(urls, parent, SPI_PACKAGES);
    }

    private static List<File> listFiles(File installedPluginsDir)
    {
        if (installedPluginsDir != null && installedPluginsDir.isDirectory()) {
            File[] files = installedPluginsDir.listFiles();
            if (files != null) {
                Arrays.sort(files);
                return ImmutableList.copyOf(files);
            }
        }
        return ImmutableList.of();
    }

    private static List<Artifact> sortedArtifacts(List<Artifact> artifacts)
    {
        List<Artifact> list = new ArrayList<>(artifacts);
        Collections.sort(list, Ordering.natural().nullsLast().onResultOf(Artifact::getFile));
        return list;
    }
}
