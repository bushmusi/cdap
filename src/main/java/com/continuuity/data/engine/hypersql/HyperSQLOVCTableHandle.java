/*
 * Copyright (c) 2012 Continuuity Inc. All rights reserved.
 */
package com.continuuity.data.engine.hypersql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.apache.hadoop.hbase.util.Bytes;

import com.continuuity.data.table.OrderedVersionedColumnarTable;
import com.continuuity.data.table.SimpleOVCTableHandle;
import com.google.inject.Inject;
import com.google.inject.name.Named;

public class HyperSQLOVCTableHandle extends SimpleOVCTableHandle {
  
  private final String hyperSqlJDBCString;
  private final Connection connection;
  
  @Inject
  public HyperSQLOVCTableHandle(
      @Named("HyperSQLOVCTableHandleJDBCString")String hyperSqlJDBCString)
          throws SQLException {
    this.hyperSqlJDBCString = hyperSqlJDBCString;
    this.connection = DriverManager.getConnection(this.hyperSqlJDBCString,
        "sa", "");
  }
  
  @Override
  public OrderedVersionedColumnarTable createNewTable(byte[] tableName) {
    return new HyperSQLOVCTable(Bytes.toString(tableName), this.connection);
  }
}
