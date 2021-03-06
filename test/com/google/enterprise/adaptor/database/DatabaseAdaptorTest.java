// Copyright 2014 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.database;

import static com.google.enterprise.adaptor.Principal.DEFAULT_NAMESPACE;
import static org.junit.Assert.*;

import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.InvalidConfigurationException;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.TestHelper;
import com.google.enterprise.adaptor.UserPrincipal;
import com.google.enterprise.adaptor.database.DatabaseAdaptor.GsaSpecialColumns;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test cases for {@link DatabaseAdaptor}. */
public class DatabaseAdaptorTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testLoadResponseGeneratorWithFactoryMethod() {
    Map<String, String> configEntries = new HashMap<String, String>();
    configEntries.put("db.modeOfOperation", "rowToText");
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    assertNotNull("loaded response generator is null",
        DatabaseAdaptor.loadResponseGenerator(config));
  }

  @Test
  public void testLoadResponseGeneratorWithFullyQualifiedMethod() {
    Map<String, String> configEntries = new HashMap<String, String>();
    String modeOfOperation = "com.google.enterprise.adaptor.database"
        + ".DatabaseAdaptorTest.createDummy";
    configEntries.put("db.modeOfOperation", modeOfOperation);
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    assertNotNull("loaded response generator is null",
        DatabaseAdaptor.loadResponseGenerator(config));
  }

  @Test
  public void testLoadResponseGeneratorModeOfOpNoSuchFactoryMethod() {
    Map<String, String> configEntries = new HashMap<String, String>();
    configEntries.put("db.modeOfOperation", "noThisMethod");
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    thrown.expect(InvalidConfigurationException.class);
    DatabaseAdaptor.loadResponseGenerator(config);
  }

  @Test
  public void testLoadResponseGeneratorModeOfOpNoSuchClassMethod() {
    Map<String, String> configEntries = new HashMap<String, String>();
    configEntries.put("db.modeOfOperation",
        "com.google.enterprise.adaptor.database"
            + ".DatabaseAdaptorTest.noThisMode");
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    thrown.expect(InvalidConfigurationException.class);
    DatabaseAdaptor.loadResponseGenerator(config);
  }

  @Test
  public void testLoadResponseGeneratorModeOfOpNoSuchClass() {
    Map<String, String> configEntries = new HashMap<String, String>();
    configEntries.put("db.modeOfOperation", "noThisClass.noThisMethod");
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    thrown.expect(InvalidConfigurationException.class);
    DatabaseAdaptor.loadResponseGenerator(config);
  }

  @Test
  public void testInitOfUrlMetadataListerNoDocIdIsUrl() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("db.modeOfOperation", "urlAndMetadataLister");
    moreEntries.put("db.modeOfOperation.urlAndMetadataLister.columnName", "ur");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    try {
      adaptor.init(TestHelper.createConfigAdaptorContext(config));
    } catch (InvalidConfigurationException ice) {
      assertTrue(ice.getMessage().contains(
          "requires docId.isUrl to be \"true\""));
    }
  }

  @Test
  public void testInitOfUrlMetadataListerDocIdIsUrlFalse() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("db.modeOfOperation", "urlAndMetadataLister");
    moreEntries.put("db.modeOfOperation.urlAndMetadataLister.columnName", "ur");
    moreEntries.put("docId.isUrl", "false");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    try {
      adaptor.init(TestHelper.createConfigAdaptorContext(config));
    } catch (InvalidConfigurationException ice) {
      assertTrue(ice.getMessage().contains(
          "requires docId.isUrl to be \"true\""));
    }
  }

  @Test
  public void testInitOfUrlMetadataListerDocIdIsUrlTrue() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("db.modeOfOperation", "urlAndMetadataLister");
    moreEntries.put("db.modeOfOperation.urlAndMetadataLister.columnName", "ur");
    moreEntries.put("docId.isUrl", "true");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    adaptor.initConfig(config);
    adaptor.init(TestHelper.createConfigAdaptorContext(config));
  }

  private Config createStandardConfig(Map<String, String> moreEntries) {
    Map<String, String> configEntries = new HashMap<String, String>();
    // driverClass must be specified, but (other than it pointing at a valid
    // class), the value does not matther.
    configEntries.put("db.driverClass",
        "com.google.enterprise.adaptor.database.DatabaseAdaptor");
    configEntries.put("db.url", "must be set");
    configEntries.put("db.user", "must be set");
    configEntries.put("db.password", "must be set");
    configEntries.put("db.uniqueKey", "must be set:string");
    configEntries.put("db.everyDocIdSql", "must be set");
    configEntries.put("db.singleDocContentSqlParameters", "must be set");
    configEntries.put("db.singleDocContentSql", "must be set");
    configEntries.put("db.aclSqlParameters", "must be set");
    configEntries.put("db.includeAllColumnsAsMetadata", "false");
    configEntries.put("db.metadataColumns", "table_col:gsa_col");
    // configEntries.put("db.aclSql", "table_col:gsa_col");
    configEntries.put("db.aclSql", "");
    configEntries.put("db.aclPrincipalDelimiter", ",");
    configEntries.put("db.disableStreaming", "true");
    configEntries.put("db.updateTimestampTimezone", "");
    configEntries.put("db.updateSql", "");
    configEntries.putAll(moreEntries);
    final Config config = new Config();
    for (Map.Entry<String, String> entry : configEntries.entrySet()) {
      TestHelper.setConfigValue(config, entry.getKey(), entry.getValue());
    }
    return config;
  }

  static ResponseGenerator createDummy(Map<String, String> config) {
    return new DummyResponseGenerator(config);
  }

  private static class DummyResponseGenerator extends ResponseGenerator {
    public DummyResponseGenerator(Map<String, String> config) {
      super(config);
    }
    @Override
    public void generateResponse(ResultSet rs, Response resp)
        throws IOException, SQLException {
      // do nothing
    }
  }
  
  @Test
  public void testAclSqlResultSetHasNoRecord() throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    ResultSetMetaData metadata = getResultSetMetaDataHasAllAclColumns();
    Acl golden = Acl.EMPTY;
    Acl acl = DatabaseAdaptor.buildAcl(rs, metadata, ",", DEFAULT_NAMESPACE);
    assertEquals(golden, acl);
  }
  
  @Test
  public void testAclSqlResultSetHasOneRecord() throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_PERMIT_USERS.toString(), "puser1, puser2");
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1, duser2");
    record.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup1, pgroup2");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1, dgroup2");
    mockResultSet.addRecord(record);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    ResultSetMetaData metadata = getResultSetMetaDataHasAllAclColumns();
    Acl golden = new Acl.Builder()
        .setPermitUsers(Arrays.asList(
            new UserPrincipal("puser2"),
            new UserPrincipal("puser1")))
        .setDenyUsers(Arrays.asList(
            new UserPrincipal("duser1"),
            new UserPrincipal("duser2")))
        .setPermitGroups(Arrays.asList(
            new GroupPrincipal("pgroup1"),
            new GroupPrincipal("pgroup2")))
        .setDenyGroups(Arrays.asList(
            new GroupPrincipal("dgroup2"),
            new GroupPrincipal("dgroup1")))
        .build();
    Acl acl = DatabaseAdaptor.buildAcl(rs, metadata, ",", DEFAULT_NAMESPACE);
    assertEquals(golden, acl);
  }
  
  @Test
  public void testAclSqlResultSetHasNonOverlappingTwoRecord()
      throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_PERMIT_USERS.toString(), "puser1, puser2");
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1, duser2");
    record.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup1, pgroup2");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1, dgroup2");
    mockResultSet.addRecord(record);
    Map<String, String> record2 = new TreeMap<String, String>();
    record2.put(
        GsaSpecialColumns.GSA_PERMIT_USERS.toString(), "puser3, puser4");
    record2.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser3, duser4");
    record2.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup3, pgroup4");
    record2.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup3, dgroup4");
    mockResultSet.addRecord(record2);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    ResultSetMetaData metadata = getResultSetMetaDataHasAllAclColumns();
    Acl golden = new Acl.Builder()
        .setPermitUsers(Arrays.asList(
            new UserPrincipal("puser2"),
            new UserPrincipal("puser1"),
            new UserPrincipal("puser4"),
            new UserPrincipal("puser3")))
        .setDenyUsers(Arrays.asList(
            new UserPrincipal("duser2"),
            new UserPrincipal("duser1"),
            new UserPrincipal("duser4"),
            new UserPrincipal("duser3")))
        .setPermitGroups(Arrays.asList(
            new GroupPrincipal("pgroup1"),
            new GroupPrincipal("pgroup3"),
            new GroupPrincipal("pgroup4"),
            new GroupPrincipal("pgroup2")))
        .setDenyGroups(Arrays.asList(
            new GroupPrincipal("dgroup1"),
            new GroupPrincipal("dgroup3"),
            new GroupPrincipal("dgroup4"),
            new GroupPrincipal("dgroup2")))
        .build();
    Acl acl = DatabaseAdaptor.buildAcl(rs, metadata, ",", DEFAULT_NAMESPACE);
    assertEquals(golden, acl);
  }
  
  @Test
  public void testAclSqlResultSetHasOverlappingTwoRecord()
      throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_PERMIT_USERS.toString(), "puser1, puser2");
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1, duser2");
    record.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup1, pgroup1");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1, dgroup1");
    mockResultSet.addRecord(record);
    Map<String, String> record2 = new TreeMap<String, String>();
    record2.put(
        GsaSpecialColumns.GSA_PERMIT_USERS.toString(), "puser2, puser1");
    record2.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser4, duser2");
    record2.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup1");
    record2.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup2, dgroup2");
    mockResultSet.addRecord(record2);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    ResultSetMetaData metadata = getResultSetMetaDataHasAllAclColumns();
    Acl golden = new Acl.Builder()
        .setPermitUsers(Arrays.asList(
            new UserPrincipal("puser2"),
            new UserPrincipal("puser1")))
        .setDenyUsers(Arrays.asList(
            new UserPrincipal("duser2"),
            new UserPrincipal("duser1"),
            new UserPrincipal("duser4")))
        .setPermitGroups(Arrays.asList(
            new GroupPrincipal("pgroup1")))
        .setDenyGroups(Arrays.asList(
            new GroupPrincipal("dgroup1"),
            new GroupPrincipal("dgroup2")))
        .build();
    Acl acl = DatabaseAdaptor.buildAcl(rs, metadata, ",", DEFAULT_NAMESPACE);
    assertEquals(golden, acl);
  }
  
  @Test
  public void testAclSqlResultSetOneColumnMissing() throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1, duser2");
    record.put(
        GsaSpecialColumns.GSA_PERMIT_GROUPS.toString(), "pgroup1, pgroup2");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1, dgroup2");
    mockResultSet.addRecord(record);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    MockAclSqlResultSetMetaData mockMetadata =
        new MockAclSqlResultSetMetaData();
    mockMetadata.addColumn(GsaSpecialColumns.GSA_DENY_USERS.toString());
    mockMetadata.addColumn(GsaSpecialColumns.GSA_PERMIT_GROUPS.toString());
    mockMetadata.addColumn(GsaSpecialColumns.GSA_DENY_GROUPS.toString());
    ResultSetMetaData metadata =
        (ResultSetMetaData) Proxy.newProxyInstance(
            ResultSetMetaData.class.getClassLoader(),
            new Class[] {ResultSetMetaData.class}, mockMetadata);
    Acl golden = new Acl.Builder()
        .setDenyUsers(Arrays.asList(
            new UserPrincipal("duser1"),
            new UserPrincipal("duser2")))
        .setPermitGroups(Arrays.asList(
            new GroupPrincipal("pgroup1"),
            new GroupPrincipal("pgroup2")))
        .setDenyGroups(Arrays.asList(
            new GroupPrincipal("dgroup2"),
            new GroupPrincipal("dgroup1")))
        .build();
    Acl acl = DatabaseAdaptor.buildAcl(rs, metadata, ",", DEFAULT_NAMESPACE);
    assertEquals(golden, acl);
  }
  
  @Test
  public void testGetPrincipalsWithEmptyDelim() throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1, duser2");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1, dgroup2");
    mockResultSet.addRecord(record);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    rs.next();
    List<UserPrincipal> goldenUsers = Arrays.asList(
        new UserPrincipal("duser1, duser2"));
    List<GroupPrincipal> goldenGroups = Arrays.asList(
        new GroupPrincipal("dgroup1, dgroup2"));
    ArrayList<UserPrincipal> users =
        DatabaseAdaptor.getUserPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_USERS, "", DEFAULT_NAMESPACE);
    ArrayList<GroupPrincipal> groups =
        DatabaseAdaptor.getGroupPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_GROUPS, "", DEFAULT_NAMESPACE);
    assertEquals(goldenUsers, users);
    assertEquals(goldenGroups, groups);
  }
  
  @Test
  public void testGetPrincipalsWithUserDefinedDelim() throws SQLException {
    MockAclSqlResultSet mockResultSet = new MockAclSqlResultSet();
    Map<String, String> record = new TreeMap<String, String>();
    record.put(GsaSpecialColumns.GSA_DENY_USERS.toString(), "duser1 ; duser2");
    record.put(
        GsaSpecialColumns.GSA_DENY_GROUPS.toString(), "dgroup1 ; dgroup2");
    mockResultSet.addRecord(record);
    ResultSet rs =
        (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class[] {ResultSet.class}, mockResultSet);
    rs.next();
    List<UserPrincipal> goldenUsers = Arrays.asList(
        new UserPrincipal("duser1"), 
        new UserPrincipal("duser2"));
    List<GroupPrincipal> goldenGroups = Arrays.asList(
        new GroupPrincipal("dgroup1"),
        new GroupPrincipal("dgroup2"));
    ArrayList<UserPrincipal> users =
        DatabaseAdaptor.getUserPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_USERS, " ; ", DEFAULT_NAMESPACE);
    ArrayList<GroupPrincipal> groups =
        DatabaseAdaptor.getGroupPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_GROUPS, " ; ", DEFAULT_NAMESPACE);
    assertEquals(goldenUsers, users);
    assertEquals(goldenGroups, groups);
  }

  @Test
  public void testIncludeAllColumnsAsMetadata_mcBlank() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("adaptor.namespace", "Default");
    moreEntries.put("db.modeOfOperation", "rowToText");
    moreEntries.put("db.includeAllColumnsAsMetadata", "true");
    moreEntries.put("db.metadataColumns", "");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    adaptor.init(TestHelper.createConfigAdaptorContext(config));
    assertEquals("MetadataColumns.AllColumns()",
        adaptor.metadataColumns.toString());
    assertEquals("fake column",
        adaptor.metadataColumns.getMetadataName("fake column"));
    MetadataColumns emptyMetadataColumns = new MetadataColumns("");
    assertFalse(emptyMetadataColumns.equals(adaptor.metadataColumns));
    assertFalse(adaptor.metadataColumns.equals(emptyMetadataColumns));
  }

  @Test
  public void testIncludeAllColumnsAsMetadata_mcSet() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("adaptor.namespace", "Default");
    moreEntries.put("db.modeOfOperation", "rowToText");
    moreEntries.put("db.includeAllColumnsAsMetadata", "true");
    moreEntries.put("db.metadataColumns", "");
    moreEntries.put("db.metadataColumns", "db_col1:gsa1,col2:gsa2");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    adaptor.init(TestHelper.createConfigAdaptorContext(config));
    assertEquals("MetadataColumns({col2=gsa2, db_col1=gsa1})",
        adaptor.metadataColumns.toString());
    assertNull(adaptor.metadataColumns.getMetadataName("fake column"));
    assertEquals("gsa1", adaptor.metadataColumns.getMetadataName("db_col1"));
    assertEquals("gsa2", adaptor.metadataColumns.getMetadataName("col2"));
  }

  @Test
  public void testIncludeAllColumnsAsMetadataFalse_mcBlank() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("adaptor.namespace", "Default");
    moreEntries.put("db.modeOfOperation", "rowToText");
    moreEntries.put("db.metadataColumns", "");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    adaptor.init(TestHelper.createConfigAdaptorContext(config));
    assertEquals("MetadataColumns({})", adaptor.metadataColumns.toString());
    assertNull(adaptor.metadataColumns.getMetadataName("fake column"));
  }

  @Test
  public void testIncludeAllColumnsAsMetadataFalse_mcSet() throws Exception {
    Map<String, String> moreEntries = new HashMap<String, String>();
    moreEntries.put("adaptor.namespace", "Default");
    moreEntries.put("db.modeOfOperation", "rowToText");
    moreEntries.put("db.metadataColumns", "db_col1:gsa1,col2:gsa2");
    final Config config = createStandardConfig(moreEntries);
    DatabaseAdaptor adaptor = new DatabaseAdaptor();
    adaptor.init(TestHelper.createConfigAdaptorContext(config));
    assertEquals("MetadataColumns({col2=gsa2, db_col1=gsa1})",
        adaptor.metadataColumns.toString());
    assertNull(adaptor.metadataColumns.getMetadataName("fake column"));
    assertEquals("gsa1", adaptor.metadataColumns.getMetadataName("db_col1"));
    assertEquals("gsa2", adaptor.metadataColumns.getMetadataName("col2"));
  }

  private static class MockAclSqlResultSet implements InvocationHandler {
    private ArrayList<Map<String, String>> records;
    private int cursor;
    
    public MockAclSqlResultSet() {
      records = new ArrayList<Map<String, String>>();
      cursor = -1;
    }
    
    public void addRecord(Map<String, String> record) {
      records.add(record);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String methodName = method.getName(); 
      if ("getString".equals(methodName)) {
        return records.get(cursor).get(args[0]);
      } else if ("next".equals(methodName)) {
        cursor++;
        return cursor < records.size();
      } else {
        throw new IllegalStateException("unexpected call: " + methodName);
      }
    }
  }
  
  private static class MockAclSqlResultSetMetaData
      implements InvocationHandler {
    private ArrayList<String> columns;
    
    public MockAclSqlResultSetMetaData() {
      columns = new ArrayList<String>();
      columns.add(""); // ResultSetMetaData column index starts from 1
    }
    
    public void addColumn(String column) {
      columns.add(column);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String methodName = method.getName(); 
      if ("getColumnCount".equals(methodName)) {
        return columns.size() - 1; // don't count the first empty string
      } else if ("getColumnName".equals(methodName)) {
        return columns.get((Integer) args[0]);
      } else {
        throw new IllegalStateException("unexpected call: " + methodName);
      }
    }
  }

  private static ResultSetMetaData getResultSetMetaDataHasAllAclColumns() {
    MockAclSqlResultSetMetaData mockMetadata =
        new MockAclSqlResultSetMetaData();
    mockMetadata.addColumn(GsaSpecialColumns.GSA_PERMIT_USERS.toString());
    mockMetadata.addColumn(GsaSpecialColumns.GSA_DENY_USERS.toString());
    mockMetadata.addColumn(GsaSpecialColumns.GSA_PERMIT_GROUPS.toString());
    mockMetadata.addColumn(GsaSpecialColumns.GSA_DENY_GROUPS.toString());
    return (ResultSetMetaData) Proxy.newProxyInstance(
        ResultSetMetaData.class.getClassLoader(),
        new Class[] {ResultSetMetaData.class}, mockMetadata);
  }
}
