/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.spi.data.sql;

import co.cask.cdap.spi.data.StructuredTableAdmin;
import co.cask.cdap.spi.data.TableAlreadyExistsException;
import co.cask.cdap.spi.data.table.StructuredTableId;
import co.cask.cdap.spi.data.table.StructuredTableRegistry;
import co.cask.cdap.spi.data.table.StructuredTableSpecification;
import co.cask.cdap.spi.data.table.field.FieldType;
import com.google.common.base.Joiner;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.sql.DataSource;

/**
 * Sql structured admin to use jdbc connection to create and drop tables.
 */
public class PostgresSqlStructuredTableAdmin implements StructuredTableAdmin {
  private static final Logger LOG = LoggerFactory.getLogger(PostgresSqlStructuredTableAdmin.class);
  private final StructuredTableRegistry registry;
  private final DataSource dataSource;

  @Inject
  public PostgresSqlStructuredTableAdmin(StructuredTableRegistry registry, DataSource dataSource) {
    this.registry = registry;
    this.dataSource = dataSource;
  }

  @Override
  public void create(StructuredTableSpecification spec) throws IOException, TableAlreadyExistsException {
    try (Connection connection = dataSource.getConnection()) {
      if (tableExistsInternal(connection, spec.getTableId())) {
        throw new TableAlreadyExistsException(spec.getTableId());
      }

      // Create table
      LOG.info("Creating table {}", spec);
      try (Statement statement = connection.createStatement()) {
        statement.execute(getCreateStatement(spec));

        // Create indexes
        for (String indexStatement : getCreateIndexStatements(spec)) {
          LOG.debug("Create index statement: {}", indexStatement);
          statement.execute(indexStatement);
        }

        registry.registerSpecification(spec);
      }
    } catch (SQLException e) {
      throw new IOException(String.format("Error creating table %s", spec.getTableId()), e);
    }
  }

  @Nullable
  @Override
  public StructuredTableSpecification getSpecification(StructuredTableId tableId) {
    return registry.getSpecification(tableId);
  }

  @Override
  public void drop(StructuredTableId tableId) throws IOException {
    LOG.info("Dropping table {}", tableId);
    String sqlQuery = getDeleteStatement(tableId.getName());
    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(sqlQuery);
      }
      registry.removeSpecification(tableId);
      // All indexes on a table are dropped when a table is dropped.
    } catch (SQLException e) {
      throw new IOException(String.format("Error dropping table %s", tableId), e);
    }
  }

  boolean tableExists(StructuredTableId tableId) throws IOException {
    try (Connection connection = dataSource.getConnection()) {
      return tableExistsInternal(connection, tableId);
    } catch (SQLException e) {
      throw new IOException(String.format("Error checking whether table %s exists", tableId.getName()), e);
    }
  }

  private boolean tableExistsInternal(Connection connection, StructuredTableId tableId) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    try (ResultSet rs = metaData.getTables(null, null, tableId.getName(), null)) {
      return rs.next();
    }
  }

  private String getCreateStatement(StructuredTableSpecification specification) {
    StringBuilder createStmt = new StringBuilder();
    createStmt.append("CREATE TABLE ").append(specification.getTableId().getName()).append(" (");

    // append the columns with sql type
    createStmt.append(
      specification.getFieldTypes().stream()
        .map(f -> f.getName() + " " + getPostgresSqlType(f))
        .collect(Collectors.joining(","))
    );

    // append primary key
    createStmt.append(", PRIMARY KEY (").append(Joiner.on(",").join(specification.getPrimaryKeys())).append("))");
    return createStmt.toString();
  }

  private List<String> getCreateIndexStatements(StructuredTableSpecification specification) {
    String table = specification.getTableId().getName();
    List<String> statements = new ArrayList<>(specification.getIndexes().size());
    for (String column : specification.getIndexes()) {
      statements.add(String.format("CREATE INDEX %s_%s_idx ON %s (%s)", table, column, table, column));
    }
    return statements;
  }

  private String getDeleteStatement(String tableName) {
    return "DROP TABLE " + tableName + ";";
  }

  private String getPostgresSqlType(FieldType field) {
    String sqlType;

    FieldType.Type type = field.getType();
    switch (type) {
      case INTEGER:
        sqlType = "int";
        break;
      case STRING:
        sqlType = "text";
        break;
      case LONG:
        sqlType = "bigint";
        break;
      case DOUBLE:
        sqlType = "double precision";
        break;
      case FLOAT:
        sqlType = "real";
        break;
      case BYTES:
        sqlType = "bytea";
        break;
      default:
        // this should never happen since all the fields are from the specification and validated there
        throw new IllegalStateException(
          String.format("The type %s of the field %s is not a valid type", type, field.getName()));
    }
    return sqlType;
  }
}