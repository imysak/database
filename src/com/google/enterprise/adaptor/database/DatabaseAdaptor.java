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

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.Acl;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.AuthnIdentity;
import com.google.enterprise.adaptor.AuthzAuthority;
import com.google.enterprise.adaptor.AuthzStatus;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.InvalidConfigurationException;
import com.google.enterprise.adaptor.PollingIncrementalLister;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.logging.*;

/** Puts SQL database into GSA index. */
public class DatabaseAdaptor extends AbstractAdaptor {
  private static final Logger log
      = Logger.getLogger(DatabaseAdaptor.class.getName());
  
  private static boolean isNullOrEmptyString(String str) {
    return null == str || "".equals(str.trim());
  }

  private int maxIdsPerFeedFile;
  private String driverClass;
  private String dbUrl;
  private String user;
  private String password;
  private UniqueKey uniqueKey;
  private String everyDocIdSql;
  private String singleDocContentSql;
  @VisibleForTesting
  MetadataColumns metadataColumns;
  private ResponseGenerator respGenerator;
  private String aclSql;
  private String aclPrincipalDelimiter;
  private String aclNamespace;
  private boolean disableStreaming;
  private boolean encodeDocId;
  private String modeOfOperation;

  @Override
  public void initConfig(Config config) {
    config.addKey("db.driverClass", null);
    config.addKey("db.url", null);
    config.addKey("db.user", null);
    config.addKey("db.password", null);
    config.addKey("db.uniqueKey", null);
    config.addKey("db.everyDocIdSql", null);
    config.addKey("db.singleDocContentSql", null);
    config.addKey("db.singleDocContentSqlParameters", "");
    config.addKey("db.metadataColumns", "");
    // when set to true, if "db.metadataColumns" is blank, it will use all
    // returned columns as metadata.
    config.addKey("db.includeAllColumnsAsMetadata", "false");
    config.addKey("db.modeOfOperation", null);
    config.addKey("db.updateSql", "");
    config.addKey("db.aclSql", "");
    config.addKey("db.aclSqlParameters", "");
    // By default, the delimiter is a single comma. This delimiter will be taken
    // from admin's config as is. For example, if the delimiter is
    //   "", it means no splitting
    //   "  ", it means to split with exactly two whitespaces
    //   " , ", it means to split with one leading whitespace, one comma, and
    //          one trailing whitespace
    config.addKey("db.aclPrincipalDelimiter", ",");
    config.addKey("db.disableStreaming", "false");
    // For updateTimestampTimezone, the default value will be empty string,
    // which means it ends up being adaptor machine's timezone.
    //
    // Different values should be picked for this config based on the database
    // the adaptor is connecting to and based on the column type used as
    // timestamp.
    //
    // For MS SQL Server,
    //  datetime, datetime2 : use database server's timezone.
    //  datetimeoffset : use UTC or GMT or any equivalent.
    //
    // For Oracle,
    //  DATE, TIMESTAMP, TIMESTAMP WITH TIME ZONE : use database server's
    //      timezone.
    //  TIMESTAMP WITH LOCAL TIME ZONE : NOT supported now. The call to
    //      getTimestamp will throw exception. This is Oracle specific thing.
    //      To fix, we need to call
    //      oracle.jdbc.driver.OracleConnection.setSessionTimeZone().
    //
    // See java.util.TimeZone javadoc for valid values for TimeZone.
    // http://docs.oracle.com/javase/7/docs/api/java/util/TimeZone.html
    config.addKey("db.updateTimestampTimezone", "");
    config.addKey("adaptor.namespace", Principal.DEFAULT_NAMESPACE);
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    Config cfg = context.getConfig();
    maxIdsPerFeedFile = Integer.parseInt(cfg.getValue("feed.maxUrls"));
    if (maxIdsPerFeedFile <= 0) {
      String errmsg = "feed.maxUrls needs to be positive";
      throw new InvalidConfigurationException(errmsg);
    }

    driverClass = cfg.getValue("db.driverClass");
    Class.forName(driverClass);
    log.config("loaded driver: " + driverClass);

    dbUrl = cfg.getValue("db.url");
    log.config("db: " + dbUrl);

    user = cfg.getValue("db.user");
    log.config("db user: " + user);

    password = context.getSensitiveValueDecoder().decodeValue(
        cfg.getValue("db.password"));

    uniqueKey = new UniqueKey(
        cfg.getValue("db.uniqueKey"),
        cfg.getValue("db.singleDocContentSqlParameters"),
        cfg.getValue("db.aclSqlParameters")
    );
    log.config("primary key: " + uniqueKey);

    everyDocIdSql = cfg.getValue("db.everyDocIdSql");
    log.config("every doc id sql: " + everyDocIdSql);

    singleDocContentSql = cfg.getValue("db.singleDocContentSql");
    log.config("single doc content sql: " + singleDocContentSql);

    Boolean includeAllColumnsAsMetadata = new Boolean(cfg.getValue(
        "db.includeAllColumnsAsMetadata"));
    log.config("include all columns as metadata: "
        + includeAllColumnsAsMetadata);

    String metadataColumnsConfig = cfg.getValue("db.metadataColumns");
    if (includeAllColumnsAsMetadata && "".equals(metadataColumnsConfig)) {
      metadataColumns = new MetadataColumns.AllColumns();
    } else {
      metadataColumns = new MetadataColumns(metadataColumnsConfig);
    }
    log.config("metadata columns: " + metadataColumns);

    respGenerator = loadResponseGenerator(cfg);
    
    if (!isNullOrEmptyString(cfg.getValue("db.aclSql"))) {
      aclSql = cfg.getValue("db.aclSql");
      log.config("acl sql: " + aclSql); 
      aclPrincipalDelimiter = cfg.getValue("db.aclPrincipalDelimiter");
      log.config("aclPrincipalDelimiter: '" + aclPrincipalDelimiter + "'");
    }

    disableStreaming = new Boolean(cfg.getValue("db.disableStreaming"));
    log.config("disableStreaming: " + disableStreaming);

    DbAdaptorIncrementalLister incrementalLister
        = initDbAdaptorIncrementalLister(cfg);
    if (incrementalLister != null) {
      context.setPollingIncrementalLister(incrementalLister);
    }

    boolean leaveIdAlone = new Boolean(cfg.getValue("docId.isUrl"));
    encodeDocId = !leaveIdAlone;
    log.config("encodeDocId: " + encodeDocId);
    if (leaveIdAlone) {
      log.config("adaptor runs in lister-only mode");
    }

    modeOfOperation = cfg.getValue("db.modeOfOperation");
    if ("urlAndMetadataLister".equals(modeOfOperation) && encodeDocId) {
      String errmsg = "db.modeOfOperation of \"" + modeOfOperation
          + "\" requires docId.isUrl to be \"true\"";
      throw new InvalidConfigurationException(errmsg);
    }

    if (aclSql == null) {
      context.setAuthzAuthority(new AllPublic());
    } else {
      context.setAuthzAuthority(new AccessChecker());
    }
  
    aclNamespace = cfg.getValue("adaptor.namespace");
    log.config("namespace: " + aclNamespace);
  }

  /** Get all doc ids from database. */
  @Override
  public void getDocIds(DocIdPusher pusher) throws IOException,
      InterruptedException {
    BufferedPusher outstream = new BufferedPusher(pusher);
    Connection conn = null;
    StatementAndResult statementAndResult = null;
    try {
      conn = makeNewConnection();
      statementAndResult = getStreamFromDb(conn, everyDocIdSql);
      ResultSet rs = statementAndResult.resultSet;
      while (rs.next()) {
        DocId id = new DocId(uniqueKey.makeUniqueId(rs, encodeDocId));
        DocIdPusher.Record.Builder builder = new DocIdPusher.Record.Builder(id);
        if ("urlAndMetadataLister".equals(modeOfOperation)) {
          addMetadataToRecordBuilder(builder, rs);
        }
        DocIdPusher.Record record = builder.build();
        log.log(Level.FINEST, "doc id: {0}", id);
        outstream.add(record);
      }
    } catch (SQLException ex) {
      throw new IOException(ex);
    } finally {
      tryClosingStatementAndResult(statementAndResult);
      tryClosingConnection(conn);
    }
    outstream.forcePush();
  }

  /**
   * Adds all specified metadata columns to the {@code DocIdPusher.Record} being
   * built.
   */
  @VisibleForTesting
  void addMetadataToRecordBuilder(DocIdPusher.Record.Builder builder,
      ResultSet rs) throws SQLException {
    ResultSetMetaData rsMetaData = rs.getMetaData();
    int numberOfColumns = rsMetaData.getColumnCount();
    for (int i = 1; i < (numberOfColumns + 1); i++) {
      String columnName = rsMetaData.getColumnName(i);
      Object value = rs.getObject(i);
      String key = metadataColumns.getMetadataName(columnName);
      if (key != null) {
        builder.addMetadata(key, "" + value);
      }
    }
  }

  /** Gives the bytes of a document referenced with id. */
  @Override
  public void getDocContent(Request req, Response resp) throws IOException {
    if (!encodeDocId) {
      // adaptor operating in lister-only mode 
      resp.respondNotFound();
      return;
    }
    DocId id = req.getDocId();
    Connection conn = null;
    StatementAndResult statementAndResult = null;
    try {
      conn = makeNewConnection();
      statementAndResult = getDocFromDb(conn, id.getUniqueId());
      ResultSet rs = statementAndResult.resultSet;
      // First handle cases with no data to return.
      boolean hasResult = rs.next();
      if (!hasResult) {
        resp.respondNotFound();
        return;
      }
      // Generate response metadata first.
      ResultSetMetaData rsMetaData = rs.getMetaData();
      int numberOfColumns = rsMetaData.getColumnCount();
      for (int i = 1; i < (numberOfColumns + 1); i++) {
        String columnName = rsMetaData.getColumnName(i);
        Object value = rs.getObject(i);
        String key = metadataColumns.getMetadataName(columnName);
        if (key != null) {
          resp.addMetadata(key, "" + value);
        }
      }
      // Generate Acl if aclSql is provided.
      if (aclSql != null) {
        Acl acl = getAcl(conn, id.getUniqueId());
        if (acl != null) {
          resp.setAcl(acl);
        }
      }
      // Generate response body.
      // In database adaptor's case, we almost never want to follow the URLs.
      // One record means one document.
      resp.setNoFollow(true); 
      respGenerator.generateResponse(rs, resp);
    } catch (SQLException ex) {
      throw new IOException("retrieval error", ex);
    } finally {
      tryClosingStatementAndResult(statementAndResult);
      tryClosingConnection(conn);
    }
  }

  public static void main(String[] args) {
    AbstractAdaptor.main(new DatabaseAdaptor(), args);
  }
  
  private Acl getAcl(Connection conn, String uniqueId) throws SQLException {
    StatementAndResult statementAndResult = null;
    try {
      statementAndResult = getAclFromDb(conn, uniqueId);
      ResultSet rs = statementAndResult.resultSet;
      ResultSetMetaData metadata = rs.getMetaData();
      return buildAcl(rs, metadata, aclPrincipalDelimiter, aclNamespace);
    } finally {
      tryClosingStatementAndResult(statementAndResult);
    }
  }
  
  @VisibleForTesting
  static Acl buildAcl(ResultSet rs, ResultSetMetaData metadata, String delim,
      String namespace) throws SQLException {
    boolean hasResult = rs.next();
    if (!hasResult) {
      // empty Acl ensures adaptor will mark this document as secure
      return Acl.EMPTY;
    }
    Acl.Builder builder = new Acl.Builder();
    ArrayList<UserPrincipal> permitUsers = new ArrayList<UserPrincipal>();
    ArrayList<UserPrincipal> denyUsers = new ArrayList<UserPrincipal>();
    ArrayList<GroupPrincipal> permitGroups = new ArrayList<GroupPrincipal>();
    ArrayList<GroupPrincipal> denyGroups = new ArrayList<GroupPrincipal>();
    boolean hasPermitUsers =
        hasColumn(metadata, GsaSpecialColumns.GSA_PERMIT_USERS.toString());
    boolean hasDenyUsers =
        hasColumn(metadata, GsaSpecialColumns.GSA_DENY_USERS.toString());
    boolean hasPermitGroups =
        hasColumn(metadata, GsaSpecialColumns.GSA_PERMIT_GROUPS.toString());
    boolean hasDenyGroups =
        hasColumn(metadata, GsaSpecialColumns.GSA_DENY_GROUPS.toString());
    do {
      if (hasPermitUsers) {
        permitUsers.addAll(getUserPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_PERMIT_USERS, delim, namespace));
      }
      if (hasDenyUsers) {
        denyUsers.addAll(getUserPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_USERS, delim, namespace));
      }
      if (hasPermitGroups) {
        permitGroups.addAll(getGroupPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_PERMIT_GROUPS, delim, namespace));
      }
      if (hasDenyGroups) {
        denyGroups.addAll(getGroupPrincipalsFromResultSet(rs,
            GsaSpecialColumns.GSA_DENY_GROUPS, delim, namespace));
      }
    } while (rs.next());
    return builder
        .setPermitUsers(permitUsers)
        .setDenyUsers(denyUsers)
        .setPermitGroups(permitGroups)
        .setDenyGroups(denyGroups)
        .build();
  }
  
  @VisibleForTesting
  static ArrayList<UserPrincipal> getUserPrincipalsFromResultSet(ResultSet rs,
      GsaSpecialColumns column, String delim, String namespace)
      throws SQLException {
    ArrayList<UserPrincipal> principals = new ArrayList<UserPrincipal>();
    String value = rs.getString(column.toString());
    if (!isNullOrEmptyString(value)) {
      if ("".equals(delim)) {
        principals.add(new UserPrincipal(value.trim(), namespace));
      } else {
        // drop trailing empties
        String principalNames[] = value.split(delim, 0);
        for (String principalName : principalNames) {
          principals.add(new UserPrincipal(principalName.trim(), namespace));
        }
      }
    }
    return principals;
  }

  @VisibleForTesting
  static ArrayList<GroupPrincipal> getGroupPrincipalsFromResultSet(ResultSet rs,
      GsaSpecialColumns column, String delim, String namespace) throws SQLException {
    ArrayList<GroupPrincipal> principals = new ArrayList<GroupPrincipal>();
    String value = rs.getString(column.toString());
    if (!isNullOrEmptyString(value)) {
      if ("".equals(delim)) {
        principals.add(new GroupPrincipal(value.trim(), namespace));
      } else {
        // drop trailing empties
        String principalNames[] = value.split(delim, 0);
        for (String principalName : principalNames) {
          principals.add(new GroupPrincipal(principalName.trim(), namespace));
        }
      }
    }
    return principals;
  }
  
  private static boolean hasColumn(ResultSetMetaData metadata, String column)
      throws SQLException {
    int columns = metadata.getColumnCount();
    for (int x = 1; x <= columns; x++) {
      if (column.equals(metadata.getColumnName(x))) {
        return true;
      }
    }
    return false;
  }

  private static class StatementAndResult {
    Statement statement;
    ResultSet resultSet;
    StatementAndResult(Statement st, ResultSet rs) { 
      if (null == st) {
        throw new NullPointerException();
      }
      if (null == rs) {
        throw new NullPointerException();
      }
      statement = st;
      resultSet = rs;
    }
  }

  private Connection makeNewConnection() throws SQLException {
    log.finest("about to connect");
    Connection conn = DriverManager.getConnection(dbUrl, user, password);
    log.finest("connected");
    return conn;
  }

  private StatementAndResult getDocFromDb(Connection conn,
      String uniqueId) throws SQLException {
    PreparedStatement st = conn.prepareStatement(singleDocContentSql);
    uniqueKey.setContentSqlValues(st, uniqueId);  
    log.log(Level.FINER, "about to get doc: {0}",  uniqueId);
    ResultSet rs = st.executeQuery();
    log.finer("got doc");
    return new StatementAndResult(st, rs); 
  }

  private StatementAndResult getAclFromDb(Connection conn,
      String uniqueId) throws SQLException {
    PreparedStatement st = conn.prepareStatement(aclSql);
    uniqueKey.setAclSqlValues(st, uniqueId);  
    log.log(Level.FINER, "about to get acl: {0}",  uniqueId);
    ResultSet rs = st.executeQuery();
    log.finer("got acl");
    return new StatementAndResult(st, rs); 
  }

  private StatementAndResult getStreamFromDb(Connection conn,
      String query) throws SQLException {
    Statement st;
    if (disableStreaming) {
      st = conn.createStatement();
    } else {
      st = conn.createStatement(
          /* 1st streaming flag */ java.sql.ResultSet.TYPE_FORWARD_ONLY,
          /* 2nd streaming flag */ java.sql.ResultSet.CONCUR_READ_ONLY);
    }
    st.setFetchSize(maxIdsPerFeedFile);  // Integer.MIN_VALUE for MySQL?
    log.log(Level.FINER, "about to query for stream: {0}", query);
    ResultSet rs = st.executeQuery(query);
    log.finer("queried for stream");
    return new StatementAndResult(st, rs); 
  }

  private static void tryClosingStatementAndResult(StatementAndResult strs) {
    if (null != strs) {
      try {
        strs.resultSet.close();
      } catch (SQLException ex) {
        log.log(Level.WARNING, "result set close failed", ex);
      }
      try {
        strs.statement.close();
      } catch (SQLException ex) {
        log.log(Level.WARNING, "statement close failed", ex);
      }
    }
  }

  private static void tryClosingConnection(Connection conn) {
    if (null != conn) {
      try {
        conn.close();
      } catch (SQLException ex) {
        log.log(Level.WARNING, "connection close failed", ex);
      }
    }
  }

  /**
   * Mechanism that accepts stream of DocIdPusher.Record instances, buffers
   * them, and sends them when it has accumulated maximum allowed amount per
   * feed file.
   */
  private class BufferedPusher {
    DocIdPusher wrapped;
    ArrayList<DocIdPusher.Record> saved;
    
    BufferedPusher(DocIdPusher underlying) {
      if (null == underlying) {
        throw new NullPointerException();
      }
      wrapped = underlying;
      saved = new ArrayList<DocIdPusher.Record>(maxIdsPerFeedFile);
    }
    
    void add(DocIdPusher.Record record) throws InterruptedException {
      saved.add(record);
      if (saved.size() >= maxIdsPerFeedFile) {
        forcePush();
      }
    }
    
    void forcePush() throws InterruptedException {
      wrapped.pushRecords(saved);
      log.log(Level.FINE, "sent {0} doc ids to pusher", saved.size());
      saved.clear();
    }
    
    protected void finalize() throws Throwable {
      if (0 != saved.size()) {
        log.warning("still have saved ids that weren't sent");
      }
    }
  }

  @VisibleForTesting
  static ResponseGenerator loadResponseGenerator(Config config) {
    String mode = config.getValue("db.modeOfOperation");
    if (isNullOrEmptyString(mode)) {
      String errmsg = "modeOfOperation can not be an empty string";
      throw new InvalidConfigurationException(errmsg);
    }
    log.fine("about to look for " + mode + " in ResponseGenerator");
    Method method = null;
    try {
      method = ResponseGenerator.class.getDeclaredMethod(mode, Map.class);
      return loadResponseGeneratorInternal(method,
          config.getValuesWithPrefix("db.modeOfOperation." + mode + "."));
    } catch (NoSuchMethodException ex) {
      log.fine("did not find " + mode + " in ResponseGenerator, going to look"
          + " for fully qualified name");
    }
    log.fine("about to try " + mode + " as a fully qualified method name");
    int sepIndex = mode.lastIndexOf(".");
    if (sepIndex == -1) {
      String errmsg = mode + " cannot be parsed as a fully quailfied name";
      throw new InvalidConfigurationException(errmsg);
    }
    String className = mode.substring(0, sepIndex);
    String methodName = mode.substring(sepIndex + 1);
    log.log(Level.FINE, "Split {0} into class {1} and method {2}",
        new Object[] {mode, className, methodName});
    Class<?> klass;
    try {
      klass = Class.forName(className);
      method = klass.getDeclaredMethod(methodName, Map.class);
    } catch (ClassNotFoundException ex) {
      String errmsg = "No class " + className + " found";
      throw new InvalidConfigurationException(errmsg, ex);
    } catch (NoSuchMethodException ex) {
      String errmsg = "No method " + methodName + " found for class "
          + className;
      throw new InvalidConfigurationException(errmsg, ex);
    }
    return loadResponseGeneratorInternal(method,
        config.getValuesWithPrefix("db.modeOfOperation." + mode + "."));
  }
  
  @VisibleForTesting
  static ResponseGenerator loadResponseGeneratorInternal(Method method,
      Map<String, String> config) {
    log.fine("loading response generator specific configuration");
    ResponseGenerator respGenerator = null;
    Object retValue = null;
    try {
      retValue = method.invoke(/*static method*/null, config);
    } catch (IllegalAccessException | IllegalArgumentException
        | InvocationTargetException e) {
      String errmsg = "Unexpected exception happened in invoking method";
      throw new InvalidConfigurationException(errmsg, e);
    }
    if (retValue instanceof ResponseGenerator) {
      respGenerator = (ResponseGenerator) retValue;
    } else {
      String errmsg = String.format("Method %s needs to return a %s",
          method.getName(), ResponseGenerator.class.getName()); 
      throw new InvalidConfigurationException(errmsg);
    }
    log.config("loaded response generator: " + respGenerator.toString());
    return respGenerator;
  }

  // Incremental pushing in Database Adaptor based on Timestamp does NOT
  // guarantee to pick ALL updates. Some updates might still need to wait for 
  // next full push to be sent to GSA.
  private class DbAdaptorIncrementalLister implements PollingIncrementalLister {
    private final String updateSql;
    private Calendar updateTimestampTimezone;
    private Timestamp lastUpdateTimestamp;
    private final DateFormat formatter;

    public DbAdaptorIncrementalLister(String updateSql, Calendar updateTsTz) {
      this.updateSql = updateSql;
      this.updateTimestampTimezone = updateTsTz;
      log.config("update sql: " + this.updateSql);
      this.lastUpdateTimestamp = new Timestamp(System.currentTimeMillis());
      formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z");
      formatter.setTimeZone(updateTimestampTimezone.getTimeZone());
    }

    @Override
    public void getModifiedDocIds(DocIdPusher pusher)
        throws IOException, InterruptedException {
      BufferedPusher outstream = new BufferedPusher(pusher);
      Connection conn = null;
      StatementAndResult statementAndResult = null;
      // latestTimestamp will be used to update lastUpdateTimestampInMillis
      // if GSA_TIMESTAMP column is present in the ResultSet and there is at 
      // least one non-null value of that column in the ResultSet.
      Timestamp latestTimestamp = null;
      boolean hasTimestamp = false;
      try {
        conn = makeNewConnection();
        statementAndResult = getUpdateStreamFromDb(conn);
        ResultSet rs = statementAndResult.resultSet;
        hasTimestamp =
            hasColumn(rs.getMetaData(),
                GsaSpecialColumns.GSA_TIMESTAMP.toString());
        log.log(Level.FINEST, "hasTimestamp: {0}", hasTimestamp);
        while (rs.next()) {
          DocId id = new DocId(uniqueKey.makeUniqueId(rs, encodeDocId));
          DocIdPusher.Record.Builder builder =
              new DocIdPusher.Record.Builder(id).setCrawlImmediately(true);
          if ("urlAndMetadataLister".equals(modeOfOperation)) {
            addMetadataToRecordBuilder(builder, rs);
          }
          DocIdPusher.Record record = builder.build();
          log.log(Level.FINEST, "doc id: {0}", id);
          outstream.add(record);
          
          // update latestTimestamp
          if (hasTimestamp) {
            Timestamp ts =
                rs.getTimestamp(GsaSpecialColumns.GSA_TIMESTAMP.toString(),
                    updateTimestampTimezone);
            if (ts != null) {
              if (latestTimestamp == null || ts.after(latestTimestamp)) {
                latestTimestamp = ts;
                log.log(Level.FINE, "latestTimestamp updated: {0}",
                    formatter.format(new Date(latestTimestamp.getTime())));
              }
            }
          }
        }
      } catch (SQLException ex) {
        throw new IOException(ex);
      } finally {
        tryClosingStatementAndResult(statementAndResult);
        tryClosingConnection(conn);
      }
      outstream.forcePush();
      if (!hasTimestamp) {
        lastUpdateTimestamp = new Timestamp(System.currentTimeMillis());
      } else if (latestTimestamp != null) {
        lastUpdateTimestamp = latestTimestamp;
      }
      // The Timestamp here will be printed in database timezone.
      log.fine("last pushing timestamp set to: "
          + formatter.format(new Date(lastUpdateTimestamp.getTime())));
    }

    private StatementAndResult getUpdateStreamFromDb(Connection conn)
        throws SQLException {
      PreparedStatement st;
      if (disableStreaming) {
        st = conn.prepareStatement(updateSql);
      } else {
        st = conn.prepareStatement(updateSql,
            /* 1st streaming flag */ java.sql.ResultSet.TYPE_FORWARD_ONLY,
            /* 2nd streaming flag */ java.sql.ResultSet.CONCUR_READ_ONLY);
      }
      st.setTimestamp(1, lastUpdateTimestamp, updateTimestampTimezone);
      ResultSet rs = st.executeQuery();
      return new StatementAndResult(st, rs);
    }
  }

  private DbAdaptorIncrementalLister initDbAdaptorIncrementalLister(
      Config config) {
    String tzString = config.getValue("db.updateTimestampTimezone");
    final Calendar updateTimestampTimezone;
    if (isNullOrEmptyString(tzString)) {
      updateTimestampTimezone = Calendar.getInstance();
    } else {
      updateTimestampTimezone =
          Calendar.getInstance(TimeZone.getTimeZone(tzString));
    }
    log.config("updateTimestampTimezone: "
        + updateTimestampTimezone.getTimeZone().getDisplayName());
    String updateSql = config.getValue("db.updateSql");
    if (!isNullOrEmptyString(updateSql)) {
      return new DbAdaptorIncrementalLister(updateSql, updateTimestampTimezone);
    } else {
      return null;
    }
  }
  
  @VisibleForTesting
  enum GsaSpecialColumns {
    GSA_PERMIT_USERS("GSA_PERMIT_USERS"),
    GSA_DENY_USERS("GSA_DENY_USERS"),
    GSA_PERMIT_GROUPS("GSA_PERMIT_GROUPS"),
    GSA_DENY_GROUPS("GSA_DENY_GROUPS"),
    GSA_TIMESTAMP("GSA_TIMESTAMP")
    ;

    private final String text;

    private GsaSpecialColumns(final String text) {
      this.text = text;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  private static class AllPublic implements AuthzAuthority {
    public Map<DocId, AuthzStatus> isUserAuthorized(AuthnIdentity userIdentity,
        Collection<DocId> ids) throws IOException {
      Map<DocId, AuthzStatus> result =
          new HashMap<DocId, AuthzStatus>(ids.size() * 2);
      for (DocId docId : ids) {
        result.put(docId, AuthzStatus.PERMIT);
      }
      return Collections.unmodifiableMap(result);
    }
  }

  private static Map<DocId, AuthzStatus> allDeny(Collection<DocId> ids) {
    Map<DocId, AuthzStatus> result
        = new HashMap<DocId, AuthzStatus>(ids.size() * 2);
    for (DocId id : ids) {
      result.put(id, AuthzStatus.DENY); 
    }
    return Collections.unmodifiableMap(result);  
  }

  private class AccessChecker implements AuthzAuthority {
    public Map<DocId, AuthzStatus> isUserAuthorized(AuthnIdentity userIdentity,
        Collection<DocId> ids) throws IOException {
     if (null == userIdentity) {
        log.info("null identity to authorize");
        return allDeny(ids);  // TODO: consider way to permit public
      }
      UserPrincipal user = userIdentity.getUser();
      if (null == user) {
        log.info("null user to authorize");
        return allDeny(ids);  // TODO: consider way to permit public
      }
      log.log(Level.INFO, "about to authorize {0} {1}",
          new Object[]{user, userIdentity.getGroups()});
      Map<DocId, AuthzStatus> result
          = new HashMap<DocId, AuthzStatus>(ids.size() * 2);
      Connection conn = null;
      try {
        conn = makeNewConnection();
        for (DocId id : ids) {
          log.log(Level.FINE, "about to get acl of doc {0}", id);
          Acl acl = getAcl(conn, id.getUniqueId());
          List<Acl> aclChain = Arrays.asList(acl);
          log.log(Level.FINE,
              "about to autorize user {0} for doc {1} and acl {2}",
              new Object[]{user, id, acl});
          AuthzStatus decision = Acl.isAuthorized(userIdentity, aclChain); 
          log.log(Level.FINE,
              "authorization decision {0} for user {1} and doc {2}",
              new Object[]{decision, user, id});
          result.put(id, decision);
        }
      } catch (SQLException ex) {
        throw new IOException("authz retrieval error", ex);
      } finally {
        tryClosingConnection(conn);
      }
      return Collections.unmodifiableMap(result);
    }
  }
}
