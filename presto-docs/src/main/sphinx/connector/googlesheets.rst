=======================
Google Sheets Connector
=======================

The Google Sheets connector allows reading spreadsheets as tables in Presto.

Configuration
-------------

Create ``etc/catalog/sheets.properties``
to mount the Google Sheets connector as the ``sheets`` catalog,
replacing the properties as appropriate:

.. code-block:: none

    connector.name=gsheets
    credentials-path=/path/to/google-sheets-credentials.json
    metadata-sheet-id=exampleId

Configuration Properties
------------------------

The following configuration properties are available:

=================================== =====================================================================
Property Name                       Description
=================================== =====================================================================
``credentials-path``                Path to the Google API JSON key file
``metadata-sheet-id``               Sheet ID of the spreadsheet that contains the table mapping
``sheets-data-max-cache-size``      Maximum number of spreadsheets to cache (defaults to ``1000``)
``sheets-data-expire-after-write``  How long to cache spreadsheet data or metadata (defaults to ``5m``)
=================================== =====================================================================

Credentials
-----------

The connector requires credentials in order to access the Google Sheets API.

1. Open the `Google Sheets API <https://console.developers.google.com/apis/library/sheets.googleapis.com>`_
   page and click the *Enable* button. This will take you to the API manager page.

2. Select a project using the drop down menu at the top of the page.
   Create a new project if you do not already have one.

3. Choose *Credentials* in the left panel.

4. Click *Manage service accounts*, then create a service account for the connector.
   On the *Create key* step, create and download a key in JSON format.

The key file will need to be available on the Presto coordinator and workers.
Set the ``credentials-path`` configuration property to point to this file
(the exact name of the file does not matter -- it can be named anything).

Metadata Sheet
--------------

The metadata sheet is used to map table names to sheet IDs.
Create a new metadata sheet. The first row must be a header row
containing the following columns in this order:

* Table Name
* Sheet ID
* Owner
* Notes

See this `example sheet <https://docs.google.com/spreadsheets/d/1Es4HhWALUQjoa-bQh4a8B5HROz7dpGMfq_HbfoaW5LM>`_
as a reference.

The metadata sheet must be shared with the service account user
(the one for which the key credentials file was created). Click the *Share*
button to share the sheet with the email address of the service account.

Set the ``metadata-sheet-id`` configuration property to the ID of this sheet.

Querying Sheets
---------------

The service account user must have access to the sheet in order for Presto
to query it. Click the *Share* button to share the sheet with the email
address of the service account.

The sheet needs to be mapped to a Presto table name. Specify a table name
(column A) and the sheet ID (column B) in the metadata sheet. To refer
to a specific tab in the sheet, add the tab name after the sheet ID, separated
with ``#``.

API Usage Limits
----------------

The Google Sheets API has `usage limits <https://developers.google.com/sheets/api/limits>`_
that may impact the usage of this connector. Increasing the cache duration and/or size
may prevent the limit from being reached. Running queries on the ``information_schema.columns``
table without a schema and table name filter may lead to hitting the limit, as this requires
fetching the sheet data for every table (unless it is already cached).
