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
package io.prestosql.plugin.hive.metastore.thrift;

import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

public class ThriftMetastoreStats
{
    private final ThriftMetastoreApiStats getAllDatabases = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getDatabase = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getAllTables = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getTablesWithParameter = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getAllViews = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getTable = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getFields = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getTableColumnStatistics = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getPartitionColumnStatistics = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getPartitionNames = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getPartitionNamesPs = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getPartition = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats getPartitionsByNames = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats createDatabase = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats dropDatabase = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats alterDatabase = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats createTable = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats dropTable = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats alterTable = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats addPartitions = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats dropPartition = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats alterPartition = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats listTablePrivileges = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats grantTablePrivileges = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats revokeTablePrivileges = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats listRoles = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats grantRole = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats revokeRole = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats listRoleGrants = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats createRole = new ThriftMetastoreApiStats();
    private final ThriftMetastoreApiStats dropRole = new ThriftMetastoreApiStats();

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetAllDatabases()
    {
        return getAllDatabases;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetDatabase()
    {
        return getDatabase;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetAllTables()
    {
        return getAllTables;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetTablesWithParameter()
    {
        return getTablesWithParameter;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetAllViews()
    {
        return getAllViews;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetTable()
    {
        return getTable;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetFields()
    {
        return getFields;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetTableColumnStatistics()
    {
        return getTableColumnStatistics;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetPartitionColumnStatistics()
    {
        return getPartitionColumnStatistics;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetPartitionNames()
    {
        return getPartitionNames;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetPartitionNamesPs()
    {
        return getPartitionNamesPs;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetPartition()
    {
        return getPartition;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGetPartitionsByNames()
    {
        return getPartitionsByNames;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getCreateDatabase()
    {
        return createDatabase;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getDropDatabase()
    {
        return dropDatabase;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getAlterDatabase()
    {
        return alterDatabase;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getCreateTable()
    {
        return createTable;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getDropTable()
    {
        return dropTable;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getAlterTable()
    {
        return alterTable;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getAddPartitions()
    {
        return addPartitions;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getDropPartition()
    {
        return dropPartition;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getAlterPartition()
    {
        return alterPartition;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGrantTablePrivileges()
    {
        return grantTablePrivileges;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getRevokeTablePrivileges()
    {
        return revokeTablePrivileges;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getListTablePrivileges()
    {
        return listTablePrivileges;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getListRoles()
    {
        return listRoles;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getGrantRole()
    {
        return grantRole;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getRevokeRole()
    {
        return revokeRole;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getListRoleGrants()
    {
        return listRoleGrants;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getCreateRole()
    {
        return createRole;
    }

    @Managed
    @Nested
    public ThriftMetastoreApiStats getDropRole()
    {
        return dropRole;
    }
}
