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
package org.apache.iceberg.spark.source;

import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.FORMAT_VERSION;
import static org.apache.iceberg.TableProperties.ORC_VECTORIZATION_ENABLED;
import static org.apache.iceberg.TableProperties.PARQUET_BATCH_SIZE;
import static org.apache.iceberg.TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES;
import static org.apache.iceberg.TableProperties.PARQUET_VECTORIZATION_ENABLED;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.functions.lit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Parameter;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Parameters;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.TestBase;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(ParameterizedTestExtension.class)
public class TestSparkMetadataColumns extends TestBase {

  private static final String TABLE_NAME = "test_table";
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "category", Types.StringType.get()),
          Types.NestedField.optional(3, "data", Types.StringType.get()));
  private static final PartitionSpec SPEC = PartitionSpec.unpartitioned();
  private static final PartitionSpec UNKNOWN_SPEC =
      TestHelpers.newExpectedSpecBuilder()
          .withSchema(SCHEMA)
          .withSpecId(1)
          .addField("zero", 1, "id_zero")
          .build();

  @Parameters(name = "fileFormat = {0}, vectorized = {1}, formatVersion = {2}")
  public static Object[][] parameters() {
    return new Object[][] {
      {FileFormat.PARQUET, false, 1},
      {FileFormat.PARQUET, true, 1},
      {FileFormat.PARQUET, false, 2},
      {FileFormat.PARQUET, true, 2},
      {FileFormat.PARQUET, false, 3},
      {FileFormat.PARQUET, true, 3},
      {FileFormat.AVRO, false, 1},
      {FileFormat.AVRO, false, 2},
      {FileFormat.AVRO, false, 3},
      {FileFormat.ORC, false, 1},
      {FileFormat.ORC, true, 1},
      {FileFormat.ORC, false, 2},
      {FileFormat.ORC, true, 2},
      {FileFormat.ORC, false, 3},
      {FileFormat.ORC, true, 3},
    };
  }

  @TempDir private Path temp;

  @Parameter(index = 0)
  private FileFormat fileFormat;

  @Parameter(index = 1)
  private boolean vectorized;

  @Parameter(index = 2)
  private int formatVersion;

  private Table table = null;

  @BeforeAll
  public static void setupSpark() {
    ImmutableMap<String, String> config =
        ImmutableMap.of(
            "type", "hive",
            "default-namespace", "default",
            "cache-enabled", "true");
    spark
        .conf()
        .set("spark.sql.catalog.spark_catalog", "org.apache.iceberg.spark.source.TestSparkCatalog");
    config.forEach(
        (key, value) -> spark.conf().set("spark.sql.catalog.spark_catalog." + key, value));
  }

  @BeforeEach
  public void setupTable() throws IOException {
    createAndInitTable();
  }

  @AfterEach
  public void dropTable() {
    TestTables.clearTables();
  }

  @TestTemplate
  public void testSpecAndPartitionMetadataColumns() {
    // TODO: support metadata structs in vectorized ORC reads
    assumeThat(fileFormat).isNotEqualTo(FileFormat.ORC);
    assumeThat(vectorized).isFalse();

    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1')", TABLE_NAME);

    table.refresh();
    table.updateSpec().addField("data").commit();
    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1')", TABLE_NAME);

    table.refresh();
    table.updateSpec().addField(Expressions.bucket("category", 8)).commit();
    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1')", TABLE_NAME);

    table.refresh();
    table.updateSpec().removeField("data").commit();
    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1')", TABLE_NAME);

    table.refresh();
    table.updateSpec().renameField("category_bucket_8", "category_bucket_8_another_name").commit();

    List<Object[]> expected =
        ImmutableList.of(
            row(0, row(null, null)),
            row(1, row("b1", null)),
            row(2, row("b1", 2)),
            row(3, row(null, 2)));
    assertEquals(
        "Rows must match",
        expected,
        sql("SELECT _spec_id, _partition FROM %s ORDER BY _spec_id", TABLE_NAME));
  }

  @TestTemplate
  public void testPartitionMetadataColumnWithManyColumns() {
    List<Types.NestedField> fields =
        Lists.newArrayList(Types.NestedField.required(0, "id", Types.LongType.get()));
    List<Types.NestedField> additionalCols =
        IntStream.range(1, 1010)
            .mapToObj(i -> Types.NestedField.optional(i, "c" + i, Types.StringType.get()))
            .collect(Collectors.toList());
    fields.addAll(additionalCols);
    Schema manyColumnsSchema = new Schema(fields);
    PartitionSpec spec = PartitionSpec.builderFor(manyColumnsSchema).identity("id").build();

    TableOperations ops = ((HasTableOperations) table).operations();
    TableMetadata base = ops.current();
    ops.commit(base, base.updateSchema(manyColumnsSchema).updatePartitionSpec(spec));

    Dataset<Row> df =
        spark
            .range(2)
            .withColumns(
                IntStream.range(1, 1010)
                    .boxed()
                    .collect(Collectors.toMap(i -> "c" + i, i -> expr("CAST(id as STRING)"))));
    StructType sparkSchema = spark.table(TABLE_NAME).schema();
    spark
        .createDataFrame(df.rdd(), sparkSchema)
        .coalesce(1)
        .write()
        .format("iceberg")
        .mode("append")
        .save(TABLE_NAME);

    assertThat(spark.table(TABLE_NAME).select("*", "_partition").count()).isEqualTo(2);
    List<Object[]> expected =
        ImmutableList.of(row(row(0L), 0L, "0", "0", "0"), row(row(1L), 1L, "1", "1", "1"));
    assertEquals(
        "Rows must match",
        expected,
        sql("SELECT _partition, id, c999, c1000, c1001 FROM %s ORDER BY id", TABLE_NAME));
  }

  @TestTemplate
  public void testPositionMetadataColumnWithMultipleRowGroups() throws NoSuchTableException {
    assumeThat(fileFormat).isEqualTo(FileFormat.PARQUET);

    table.updateProperties().set(PARQUET_ROW_GROUP_SIZE_BYTES, "100").commit();

    List<Long> ids = Lists.newArrayList();
    for (long id = 0L; id < 200L; id++) {
      ids.add(id);
    }
    Dataset<Row> df =
        spark
            .createDataset(ids, Encoders.LONG())
            .withColumnRenamed("value", "id")
            .withColumn("category", lit("hr"))
            .withColumn("data", lit("ABCDEF"));
    df.coalesce(1).writeTo(TABLE_NAME).append();

    assertThat(spark.table(TABLE_NAME).count()).isEqualTo(200);

    List<Object[]> expectedRows = ids.stream().map(this::row).collect(Collectors.toList());
    assertEquals("Rows must match", expectedRows, sql("SELECT _pos FROM %s", TABLE_NAME));
  }

  @TestTemplate
  public void testPositionMetadataColumnWithMultipleBatches() throws NoSuchTableException {
    assumeThat(fileFormat).isEqualTo(FileFormat.PARQUET);

    table.updateProperties().set(PARQUET_BATCH_SIZE, "1000").commit();

    List<Long> ids = Lists.newArrayList();
    for (long id = 0L; id < 7500L; id++) {
      ids.add(id);
    }
    Dataset<Row> df =
        spark
            .createDataset(ids, Encoders.LONG())
            .withColumnRenamed("value", "id")
            .withColumn("category", lit("hr"))
            .withColumn("data", lit("ABCDEF"));
    df.coalesce(1).writeTo(TABLE_NAME).append();

    assertThat(spark.table(TABLE_NAME).count()).isEqualTo(7500);

    List<Object[]> expectedRows = ids.stream().map(this::row).collect(Collectors.toList());
    assertEquals("Rows must match", expectedRows, sql("SELECT _pos FROM %s", TABLE_NAME));
  }

  @TestTemplate
  public void testPartitionMetadataColumnWithUnknownTransforms() {
    // replace the table spec to include an unknown transform
    TableOperations ops = ((HasTableOperations) table).operations();
    TableMetadata base = ops.current();
    ops.commit(base, base.updatePartitionSpec(UNKNOWN_SPEC));

    assertThatThrownBy(() -> sql("SELECT _partition FROM %s", TABLE_NAME))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Cannot build table partition type, unknown transforms: [zero]");
  }

  @TestTemplate
  public void testConflictingColumns() {
    table
        .updateSchema()
        .addColumn(MetadataColumns.SPEC_ID.name(), Types.IntegerType.get())
        .addColumn(MetadataColumns.FILE_PATH.name(), Types.StringType.get())
        .commit();

    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1', -1, 'path/to/file')", TABLE_NAME);

    assertEquals(
        "Rows must match",
        ImmutableList.of(row(1L, "a1")),
        sql("SELECT id, category FROM %s", TABLE_NAME));

    assertThatThrownBy(() -> sql("SELECT * FROM %s", TABLE_NAME))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith(
            "Table column names conflict with names reserved for Iceberg metadata columns: [_spec_id, _file].");

    table.refresh();

    table
        .updateSchema()
        .renameColumn(MetadataColumns.SPEC_ID.name(), "_renamed" + MetadataColumns.SPEC_ID.name())
        .renameColumn(
            MetadataColumns.FILE_PATH.name(), "_renamed" + MetadataColumns.FILE_PATH.name())
        .commit();

    assertEquals(
        "Rows must match",
        ImmutableList.of(row(0, null, -1)),
        sql("SELECT _spec_id, _partition, _renamed_spec_id FROM %s", TABLE_NAME));
  }

  @TestTemplate
  public void testIdentifierFields() {
    table.updateSchema().setIdentifierFields("id").commit();

    sql("INSERT INTO TABLE %s VALUES (1, 'a1', 'b1')", TABLE_NAME);

    assertEquals(
        "Rows must match",
        ImmutableList.of(row(1L, 0, null)),
        sql("SELECT id, _spec_id, _partition FROM %s", TABLE_NAME));
  }

  @TestTemplate
  public void testRowLineageColumnsAreNullBeforeV3() {
    assumeThat(formatVersion).isLessThan(3);
    // ToDo: When the other readers have row lineage plumbed through, remove these assumptions
    assumeThat(vectorized).isFalse();
    assumeThat(fileFormat).isEqualTo(FileFormat.PARQUET);

    sql("INSERT INTO TABLE %s VALUES (1L, 'a1', 'b1')", TABLE_NAME);

    assertEquals(
        "Rows must match",
        ImmutableList.of(row(1L, null, null)),
        sql("SELECT id, _row_id, _last_updated_sequence_number FROM %s", TABLE_NAME));
  }

  private void createAndInitTable() throws IOException {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(FORMAT_VERSION, String.valueOf(formatVersion));
    properties.put(DEFAULT_FILE_FORMAT, fileFormat.name());

    switch (fileFormat) {
      case PARQUET:
        properties.put(PARQUET_VECTORIZATION_ENABLED, String.valueOf(vectorized));
        break;
      case ORC:
        properties.put(ORC_VECTORIZATION_ENABLED, String.valueOf(vectorized));
        break;
      default:
        Preconditions.checkState(
            !vectorized, "File format %s does not support vectorized reads", fileFormat);
    }

    this.table =
        TestTables.create(
            Files.createTempDirectory(temp, "junit").toFile(),
            TABLE_NAME,
            SCHEMA,
            SPEC,
            properties);
  }
}
