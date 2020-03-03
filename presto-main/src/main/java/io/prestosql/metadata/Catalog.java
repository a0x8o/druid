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
package io.prestosql.metadata;

import io.prestosql.connector.CatalogName;
import io.prestosql.spi.connector.Connector;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.prestosql.metadata.MetadataUtil.checkCatalogName;
import static java.util.Objects.requireNonNull;

public class Catalog
{
    private final String catalogName;
    private final CatalogName connectorCatalogName;
    private final Connector connector;

    private final CatalogName informationSchemaId;
    private final Connector informationSchema;

    private final CatalogName systemTablesId;
    private final Connector systemTables;

    public Catalog(
            String catalogName,
            CatalogName connectorCatalogName,
            Connector connector,
            CatalogName informationSchemaId,
            Connector informationSchema,
            CatalogName systemTablesId,
            Connector systemTables)
    {
        this.catalogName = checkCatalogName(catalogName);
        this.connectorCatalogName = requireNonNull(connectorCatalogName, "connectorConnectorId is null");
        this.connector = requireNonNull(connector, "connector is null");
        this.informationSchemaId = requireNonNull(informationSchemaId, "informationSchemaId is null");
        this.informationSchema = requireNonNull(informationSchema, "informationSchema is null");
        this.systemTablesId = requireNonNull(systemTablesId, "systemTablesId is null");
        this.systemTables = requireNonNull(systemTables, "systemTables is null");
    }

    public String getCatalogName()
    {
        return catalogName;
    }

    public CatalogName getConnectorCatalogName()
    {
        return connectorCatalogName;
    }

    public CatalogName getInformationSchemaId()
    {
        return informationSchemaId;
    }

    public CatalogName getSystemTablesId()
    {
        return systemTablesId;
    }

    public Connector getConnector(CatalogName catalogName)
    {
        if (this.connectorCatalogName.equals(catalogName)) {
            return connector;
        }
        if (informationSchemaId.equals(catalogName)) {
            return informationSchema;
        }
        if (systemTablesId.equals(catalogName)) {
            return systemTables;
        }
        throw new IllegalArgumentException("Unknown connector id: " + catalogName);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("catalogName", catalogName)
                .add("connectorConnectorId", connectorCatalogName)
                .toString();
    }
}
