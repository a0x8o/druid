=======
EXPLAIN
=======

Synopsis
--------

.. code-block:: none

    EXPLAIN [ ( option [, ...] ) ] statement

    where option can be one of:

        FORMAT { TEXT | GRAPHVIZ | JSON }
        TYPE { LOGICAL | DISTRIBUTED | VALIDATE | IO }

Description
-----------

Show the logical or distributed execution plan of a statement, or validate the statement.
Use ``TYPE DISTRIBUTED`` option to display fragmented plan. Each plan fragment is executed by
a single or multiple Presto nodes. Fragments separation represent the data exchange between Presto nodes.
Fragment type specifies how the fragment is executed by Presto nodes and how the data is
distributed between fragments:

``SINGLE``
    Fragment is executed on a single node.

``HASH``
    Fragment is executed on a fixed number of nodes with the input data
    distributed using a hash function.

``ROUND_ROBIN``
    Fragment is executed on a fixed number of nodes with the input data
    distributed in a round-robin fashion.

``BROADCAST``
    Fragment is executed on a fixed number of nodes with the input data
    broadcasted to all nodes.

``SOURCE``
    Fragment is executed on nodes where input splits are accessed.

Examples
--------

Logical plan:

.. code-block:: none

    presto:tiny> EXPLAIN SELECT regionkey, count(*) FROM nation GROUP BY 1;
                                                       Query Plan
    -----------------------------------------------------------------------------------------------------------------
     Output[regionkey, _col1]
     │   Layout: [regionkey:bigint, count:bigint]
     │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
     │   _col1 := count
     └─ RemoteExchange[GATHER]
        │   Layout: [regionkey:bigint, count:bigint]
        │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
        └─ Aggregate(FINAL)[regionkey]
           │   Layout: [regionkey:bigint, count:bigint]
           │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
           │   count := count("count_8")
           └─ LocalExchange[HASH][$hashvalue] ("regionkey")
              │   Layout: [regionkey:bigint, count_8:bigint, $hashvalue:bigint]
              │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
              └─ RemoteExchange[REPARTITION][$hashvalue_9]
                 │   Layout: [regionkey:bigint, count_8:bigint, $hashvalue_9:bigint]
                 │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
                 └─ Project[]
                    │   Layout: [regionkey:bigint, count_8:bigint, $hashvalue_10:bigint]
                    │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
                    │   $hashvalue_10 := "combine_hash"(bigint '0', COALESCE("$operator$hash_code"("regionkey"), 0))
                    └─ Aggregate(PARTIAL)[regionkey]
                       │   Layout: [regionkey:bigint, count_8:bigint]
                       │   count_8 := count(*)
                       └─ TableScan[tpch:nation:sf0.01]
                              Layout: [regionkey:bigint]
                              Estimates: {rows: 25 (225B), cpu: 225, memory: 0B, network: 0B}
                              regionkey := tpch:regionkey

Distributed plan:

.. code-block:: none

    presto:tiny> EXPLAIN (TYPE DISTRIBUTED) SELECT regionkey, count(*) FROM nation GROUP BY 1;
                                                  Query Plan
    ------------------------------------------------------------------------------------------------------
     Fragment 0 [SINGLE]
         Output layout: [regionkey, count]
         Output partitioning: SINGLE []
         Stage Execution Strategy: UNGROUPED_EXECUTION
         Output[regionkey, _col1]
         │   Layout: [regionkey:bigint, count:bigint]
         │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
         │   _col1 := count
         └─ RemoteSource[1]
                Layout: [regionkey:bigint, count:bigint]

     Fragment 1 [HASH]
         Output layout: [regionkey, count]
         Output partitioning: SINGLE []
         Stage Execution Strategy: UNGROUPED_EXECUTION
         Aggregate(FINAL)[regionkey]
         │   Layout: [regionkey:bigint, count:bigint]
         │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
         │   count := count("count_8")
         └─ LocalExchange[HASH][$hashvalue] ("regionkey")
            │   Layout: [regionkey:bigint, count_8:bigint, $hashvalue:bigint]
            │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
            └─ RemoteSource[2]
                   Layout: [regionkey:bigint, count_8:bigint, $hashvalue_9:bigint]

     Fragment 2 [SOURCE]
         Output layout: [regionkey, count_8, $hashvalue_10]
         Output partitioning: HASH [regionkey][$hashvalue_10]
         Stage Execution Strategy: UNGROUPED_EXECUTION
         Project[]
         │   Layout: [regionkey:bigint, count_8:bigint, $hashvalue_10:bigint]
         │   Estimates: {rows: ? (?), cpu: ?, memory: ?, network: ?}
         │   $hashvalue_10 := "combine_hash"(bigint '0', COALESCE("$operator$hash_code"("regionkey"), 0))
         └─ Aggregate(PARTIAL)[regionkey]
            │   Layout: [regionkey:bigint, count_8:bigint]
            │   count_8 := count(*)
            └─ TableScan[tpch:nation:sf0.01, grouped = false]
                   Layout: [regionkey:bigint]
                   Estimates: {rows: 25 (225B), cpu: 225, memory: 0B, network: 0B}
                   regionkey := tpch:regionkey

Validate:

.. code-block:: none

    presto:tiny> EXPLAIN (TYPE VALIDATE) SELECT regionkey, count(*) FROM nation GROUP BY 1;
     Valid
    -------
     true

IO:

.. code-block:: none


    presto:hive> EXPLAIN (TYPE IO, FORMAT JSON) INSERT INTO test_nation SELECT * FROM nation WHERE regionkey = 2;
                Query Plan
    -----------------------------------
     {
       "inputTableColumnInfos" : [ {
         "table" : {
           "catalog" : "hive",
           "schemaTable" : {
             "schema" : "tpch",
             "table" : "nation"
           }
         },
         "columns" : [ {
           "columnName" : "regionkey",
           "type" : "bigint",
           "domain" : {
             "nullsAllowed" : false,
             "ranges" : [ {
               "low" : {
                 "value" : "2",
                 "bound" : "EXACTLY"
               },
               "high" : {
                 "value" : "2",
                 "bound" : "EXACTLY"
               }
             } ]
           }
         } ],
         "estimate" : {
           "outputRowCount" : 15000.0,
           "outputSizeInBytes" : 1597294.0,
           "cpuCost" : 1597294.0,
           "maxMemory" : 0.0,
           "networkCost" : 0.0
         },
       } ],
       "outputTable" : {
         "catalog" : "hive",
         "schemaTable" : {
           "schema" : "tpch",
           "table" : "test_nation"
         }
       },
       "estimate" : {
         "outputRowCount" : 15000.0,
         "outputSizeInBytes" : 1597294.0,
         "cpuCost" : 1597294.0,
         "maxMemory" : 0.0,
         "networkCost" : 1597294.0
       }
     }


See Also
--------

:doc:`explain-analyze`
