/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaHook;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.partition.spec.PartitionSpecProxy;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.metastore.DefaultHiveMetaHook;
import org.apache.hadoop.hive.ql.exec.tez.TezTask;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.JobContextImpl;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.OutputCommitter;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hive.HiveSchemaUtil;
import org.apache.iceberg.hive.HiveTableOperations;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.mr.Catalogs;
import org.apache.iceberg.mr.InputFormatConfig;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiveIcebergMetaHook extends DefaultHiveMetaHook {
  private static final Logger LOG = LoggerFactory.getLogger(HiveIcebergMetaHook.class);
  private static final Set<String> PARAMETERS_TO_REMOVE = ImmutableSet
      .of(InputFormatConfig.TABLE_SCHEMA, Catalogs.LOCATION, Catalogs.NAME);
  private static final Set<String> PROPERTIES_TO_REMOVE = ImmutableSet
      // We don't want to push down the metadata location props to Iceberg from HMS,
      // since the snapshot pointer in HMS would always be one step ahead
      .of(BaseMetastoreTableOperations.METADATA_LOCATION_PROP,
      BaseMetastoreTableOperations.PREVIOUS_METADATA_LOCATION_PROP,
      // Initially we'd like to cache the partition spec in HMS, but not push it down later to Iceberg during alter
      // table commands since by then the HMS info can be stale + Iceberg does not store its partition spec in the props
      InputFormatConfig.PARTITION_SPEC);

  private final Configuration conf;
  private Table icebergTable = null;
  private Properties catalogProperties;
  private boolean deleteIcebergTable;
  private FileIO deleteIo;
  private TableMetadata deleteMetadata;
  private boolean canMigrateHiveTable;
  private PreAlterTableProperties preAlterTableProperties;

  public HiveIcebergMetaHook(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public void preCreateTable(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    this.catalogProperties = getCatalogProperties(hmsTable);

    // Set the table type even for non HiveCatalog based tables
    hmsTable.getParameters().put(BaseMetastoreTableOperations.TABLE_TYPE_PROP,
        BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE.toUpperCase());

    if (!Catalogs.hiveCatalog(conf, catalogProperties)) {
      // For non-HiveCatalog tables too, we should set the input and output format
      // so that the table can be read by other engines like Impala
      hmsTable.getSd().setInputFormat(HiveIcebergInputFormat.class.getCanonicalName());
      hmsTable.getSd().setOutputFormat(HiveIcebergOutputFormat.class.getCanonicalName());

      // If not using HiveCatalog check for existing table
      try {
        this.icebergTable = Catalogs.loadTable(conf, catalogProperties);

        Preconditions.checkArgument(catalogProperties.getProperty(InputFormatConfig.TABLE_SCHEMA) == null,
            "Iceberg table already created - can not use provided schema");
        Preconditions.checkArgument(catalogProperties.getProperty(InputFormatConfig.PARTITION_SPEC) == null,
            "Iceberg table already created - can not use provided partition specification");

        LOG.info("Iceberg table already exists {}", icebergTable);

        return;
      } catch (NoSuchTableException nte) {
        // If the table does not exist we will create it below
      }
    }

    // If the table does not exist collect data for table creation
    // - InputFormatConfig.TABLE_SCHEMA, InputFormatConfig.PARTITION_SPEC takes precedence so the user can override the
    // Iceberg schema and specification generated by the code

    Schema schema = schema(catalogProperties, hmsTable);
    PartitionSpec spec = spec(schema, catalogProperties, hmsTable);

    // If there are partition keys specified remove them from the HMS table and add them to the column list
    if (hmsTable.isSetPartitionKeys()) {
      hmsTable.getSd().getCols().addAll(hmsTable.getPartitionKeys());
      hmsTable.setPartitionKeysIsSet(false);
    }

    catalogProperties.put(InputFormatConfig.TABLE_SCHEMA, SchemaParser.toJson(schema));
    catalogProperties.put(InputFormatConfig.PARTITION_SPEC, PartitionSpecParser.toJson(spec));
    updateHmsTableProperties(hmsTable);
  }

  @Override
  public void rollbackCreateTable(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    // do nothing
  }

  @Override
  public void commitCreateTable(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    if (icebergTable == null) {
      if (Catalogs.hiveCatalog(conf, catalogProperties)) {
        catalogProperties.put(TableProperties.ENGINE_HIVE_ENABLED, true);
      }

      Catalogs.createTable(conf, catalogProperties);
    }
  }

  @Override
  public void preDropTable(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    this.catalogProperties = getCatalogProperties(hmsTable);
    this.deleteIcebergTable = hmsTable.getParameters() != null &&
        "TRUE".equalsIgnoreCase(hmsTable.getParameters().get(InputFormatConfig.EXTERNAL_TABLE_PURGE));

    if (deleteIcebergTable && Catalogs.hiveCatalog(conf, catalogProperties)) {
      // Store the metadata and the id for deleting the actual table data
      String metadataLocation = hmsTable.getParameters().get(BaseMetastoreTableOperations.METADATA_LOCATION_PROP);
      this.deleteIo = Catalogs.loadTable(conf, catalogProperties).io();
      this.deleteMetadata = TableMetadataParser.read(deleteIo, metadataLocation);
    }
  }

  @Override
  public void rollbackDropTable(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    // do nothing
  }

  @Override
  public void commitDropTable(org.apache.hadoop.hive.metastore.api.Table hmsTable, boolean deleteData) {
    if (deleteData && deleteIcebergTable) {
      try {
        if (!Catalogs.hiveCatalog(conf, catalogProperties)) {
          LOG.info("Dropping with purge all the data for table {}.{}", hmsTable.getDbName(), hmsTable.getTableName());
          Catalogs.dropTable(conf, catalogProperties);
        } else {
          // do nothing if metadata folder has been deleted already (Hive 4 behaviour for purge=TRUE)
          if (deleteIo.newInputFile(deleteMetadata.location()).exists()) {
            CatalogUtil.dropTableData(deleteIo, deleteMetadata);
          }
        }
      } catch (Exception e) {
        // we want to successfully complete the Hive DROP TABLE command despite catalog-related exceptions here
        // e.g. we wish to successfully delete a Hive table even if the underlying Hadoop table has already been deleted
        LOG.warn("Exception during commitDropTable operation for table {}.{}.",
            hmsTable.getDbName(), hmsTable.getTableName(), e);
      }
    }
  }

  @Override
  public void preAlterTable(org.apache.hadoop.hive.metastore.api.Table hmsTable, EnvironmentContext context)
      throws MetaException {
    HiveMetaHook.super.preAlterTable(hmsTable, context);
    catalogProperties = getCatalogProperties(hmsTable);
    try {
      icebergTable = Catalogs.loadTable(conf, catalogProperties);
    } catch (NoSuchTableException nte) {
      // If the iceberg table does not exist, and the hms table is external and not temporary and not acid
      // we will create it in commitAlterTable
      StorageDescriptor sd = hmsTable.getSd();
      canMigrateHiveTable = MetaStoreUtils.isExternalTable(hmsTable) && !hmsTable.isTemporary() &&
          !AcidUtils.isTransactionalTable(hmsTable);
      if (!canMigrateHiveTable) {
        throw new MetaException("Converting non-external, temporary or transactional hive table to iceberg " +
            "table is not allowed.");
      }

      preAlterTableProperties = new PreAlterTableProperties();
      preAlterTableProperties.tableLocation = sd.getLocation();
      preAlterTableProperties.format = sd.getInputFormat();
      preAlterTableProperties.schema = schema(catalogProperties, hmsTable);
      preAlterTableProperties.spec = spec(preAlterTableProperties.schema, catalogProperties, hmsTable);
      preAlterTableProperties.partitionKeys = hmsTable.getPartitionKeys();

      context.getProperties().put(HiveMetaHook.ALLOW_PARTITION_KEY_CHANGE, "true");
      // If there are partition keys specified remove them from the HMS table and add them to the column list
      if (hmsTable.isSetPartitionKeys()) {
        hmsTable.getSd().getCols().addAll(hmsTable.getPartitionKeys());
        hmsTable.setPartitionKeysIsSet(false);
      }
      sd.setInputFormat(HiveIcebergInputFormat.class.getCanonicalName());
      sd.setOutputFormat(HiveIcebergOutputFormat.class.getCanonicalName());
      sd.setSerdeInfo(new SerDeInfo("icebergSerde", HiveIcebergSerDe.class.getCanonicalName(),
          Collections.emptyMap()));
      updateHmsTableProperties(hmsTable);
    }
  }

  @Override
  public void commitAlterTable(org.apache.hadoop.hive.metastore.api.Table hmsTable,
      PartitionSpecProxy partitionSpecProxy) throws MetaException {
    HiveMetaHook.super.commitAlterTable(hmsTable, partitionSpecProxy);
    if (canMigrateHiveTable) {
      catalogProperties = getCatalogProperties(hmsTable);
      catalogProperties.put(InputFormatConfig.TABLE_SCHEMA, SchemaParser.toJson(preAlterTableProperties.schema));
      catalogProperties.put(InputFormatConfig.PARTITION_SPEC, PartitionSpecParser.toJson(preAlterTableProperties.spec));
      setFileFormat();
      if (Catalogs.hiveCatalog(conf, catalogProperties)) {
        catalogProperties.put(TableProperties.ENGINE_HIVE_ENABLED, true);
      }
      HiveTableUtil.importFiles(preAlterTableProperties.tableLocation, preAlterTableProperties.format,
          partitionSpecProxy, preAlterTableProperties.partitionKeys, catalogProperties, conf);
    }
  }

  private void setFileFormat() {
    String format = preAlterTableProperties.format.toLowerCase();
    if (format.contains("orc")) {
      catalogProperties.put(TableProperties.DEFAULT_FILE_FORMAT, "orc");
    } else if (format.contains("parquet")) {
      catalogProperties.put(TableProperties.DEFAULT_FILE_FORMAT, "parquet");
    } else if (format.contains("avro")) {
      catalogProperties.put(TableProperties.DEFAULT_FILE_FORMAT, "avro");
    }
  }

  private void updateHmsTableProperties(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    // Set the table type even for non HiveCatalog based tables
    hmsTable.getParameters().put(BaseMetastoreTableOperations.TABLE_TYPE_PROP,
        BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE.toUpperCase());

    // Allow purging table data if the table is created now and not set otherwise
    hmsTable.getParameters().putIfAbsent(InputFormatConfig.EXTERNAL_TABLE_PURGE, "TRUE");

    // If the table is not managed by Hive catalog then the location should be set
    if (!Catalogs.hiveCatalog(conf, catalogProperties)) {
      Preconditions.checkArgument(hmsTable.getSd() != null && hmsTable.getSd().getLocation() != null,
          "Table location not set");
    }

    // Remove creation related properties
    PARAMETERS_TO_REMOVE.forEach(hmsTable.getParameters()::remove);
  }

  /**
   * Calculates the properties we would like to send to the catalog.
   * <ul>
   * <li>The base of the properties is the properties stored at the Hive Metastore for the given table
   * <li>We add the {@link Catalogs#LOCATION} as the table location
   * <li>We add the {@link Catalogs#NAME} as TableIdentifier defined by the database name and table name
   * <li>We remove some parameters that we don't want to push down to the Iceberg table props
   * </ul>
   * @param hmsTable Table for which we are calculating the properties
   * @return The properties we can provide for Iceberg functions, like {@link Catalogs}
   */
  private static Properties getCatalogProperties(org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    Properties properties = new Properties();

    hmsTable.getParameters().forEach((key, value) -> {
      // translate key names between HMS and Iceberg where needed
      String icebergKey = HiveTableOperations.translateToIcebergProp(key);
      properties.put(icebergKey, value);
    });

    if (properties.get(Catalogs.LOCATION) == null &&
        hmsTable.getSd() != null && hmsTable.getSd().getLocation() != null) {
      properties.put(Catalogs.LOCATION, hmsTable.getSd().getLocation());
    }

    if (properties.get(Catalogs.NAME) == null) {
      properties.put(Catalogs.NAME, TableIdentifier.of(hmsTable.getDbName(), hmsTable.getTableName()).toString());
    }

    // Remove HMS table parameters we don't want to propagate to Iceberg
    PROPERTIES_TO_REMOVE.forEach(properties::remove);

    return properties;
  }

  private Schema schema(Properties properties, org.apache.hadoop.hive.metastore.api.Table hmsTable) {
    boolean autoConversion = conf.getBoolean(InputFormatConfig.SCHEMA_AUTO_CONVERSION, false);

    if (properties.getProperty(InputFormatConfig.TABLE_SCHEMA) != null) {
      return SchemaParser.fromJson(properties.getProperty(InputFormatConfig.TABLE_SCHEMA));
    } else if (hmsTable.isSetPartitionKeys() && !hmsTable.getPartitionKeys().isEmpty()) {
      // Add partitioning columns to the original column list before creating the Iceberg Schema
      List<FieldSchema> cols = Lists.newArrayList(hmsTable.getSd().getCols());
      cols.addAll(hmsTable.getPartitionKeys());
      return HiveSchemaUtil.convert(cols, autoConversion);
    } else {
      return HiveSchemaUtil.convert(hmsTable.getSd().getCols(), autoConversion);
    }
  }

  private static PartitionSpec spec(Schema schema, Properties properties,
      org.apache.hadoop.hive.metastore.api.Table hmsTable) {

    if (hmsTable.getParameters().get(InputFormatConfig.PARTITION_SPEC) != null) {
      Preconditions.checkArgument(!hmsTable.isSetPartitionKeys() || hmsTable.getPartitionKeys().isEmpty(),
          "Provide only one of the following: Hive partition specification, or the " +
              InputFormatConfig.PARTITION_SPEC + " property");
      return PartitionSpecParser.fromJson(schema, hmsTable.getParameters().get(InputFormatConfig.PARTITION_SPEC));
    } else if (hmsTable.isSetPartitionKeys() && !hmsTable.getPartitionKeys().isEmpty()) {
      // If the table is partitioned then generate the identity partition definitions for the Iceberg table
      return HiveSchemaUtil.spec(schema, hmsTable.getPartitionKeys());
    } else {
      return PartitionSpec.unpartitioned();
    }
  }

  private class PreAlterTableProperties {
    private String tableLocation;
    private String format;
    private Schema schema;
    private PartitionSpec spec;
    private List<FieldSchema> partitionKeys;
  }

  @Override
  public void commitInsertTable(org.apache.hadoop.hive.metastore.api.Table table, boolean overwrite)
      throws MetaException {
    // construct the job context
    JobConf jobConf = new JobConf(conf);
    String tableName = TableIdentifier.of(table.getDbName(), table.getTableName()).toString();
    jobConf.set(InputFormatConfig.TABLE_IDENTIFIER, tableName);
    jobConf.set(InputFormatConfig.TABLE_LOCATION, table.getSd().getLocation());
    JobID jobID = JobID.forName(jobConf.get(TezTask.HIVE_TEZ_COMMIT_JOB_ID + "." + tableName));
    JobContext jobContext = new JobContextImpl(jobConf, jobID, null);

    // commit (or abort)
    OutputCommitter committer = new HiveIcebergOutputCommitter();
    try {
      committer.commitJob(jobContext);
    } catch (Exception commitExc) {
      LOG.error("Error while trying to commit job. Will abort it now.", commitExc);
      try {
        committer.abortJob(jobContext, JobStatus.State.FAILED);
        throw new MetaException("Unable to commit job: " + commitExc.getMessage());
      } catch (IOException abortExc) {
        LOG.error("Error while trying to abort failed job. There might be uncleaned data files.", abortExc);
        throw new MetaException("Unable to commit and abort job: " + commitExc.getMessage());
      }
    }
  }

  @Override
  public void preInsertTable(org.apache.hadoop.hive.metastore.api.Table table, boolean overwrite)
      throws MetaException {
    // do nothing
  }

  @Override
  public void rollbackInsertTable(org.apache.hadoop.hive.metastore.api.Table table, boolean overwrite)
      throws MetaException {
    // do nothing
  }
}
