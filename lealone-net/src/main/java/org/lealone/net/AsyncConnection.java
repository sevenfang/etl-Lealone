/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.net;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.exceptions.JdbcSQLException;
import org.lealone.common.util.IOUtils;
import org.lealone.common.util.SmallLRUCache;
import org.lealone.common.util.SmallMap;
import org.lealone.common.util.StringUtils;
import org.lealone.db.CommandParameter;
import org.lealone.db.ConnectionInfo;
import org.lealone.db.Constants;
import org.lealone.db.Session;
import org.lealone.db.SysProperties;
import org.lealone.db.result.Result;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueLob;
import org.lealone.replication.Replication;
import org.lealone.sql.PreparedStatement;
import org.lealone.storage.LobStorage;
import org.lealone.storage.StorageMap;
import org.lealone.storage.type.DataType;
import org.lealone.storage.type.WriteBuffer;
import org.lealone.storage.type.WriteBufferPool;

/**
 * One server thread is opened per client connection.
 * 
 * @author H2 Group
 * @author zhh
 */
public class AsyncConnection implements Comparable<AsyncConnection>, Handler<Buffer> {

    private final SmallMap cache = new SmallMap(SysProperties.SERVER_CACHED_OBJECTS);
    private SmallLRUCache<Long, CachedInputStream> lobs; // 大多数情况下都不使用lob，所以延迟初始化

    private Transfer transfer;
    private final NetSocket socket;

    private Session session;
    private String sessionId;
    private boolean stop;

    private CountDownLatch readyLatch;
    private final ConcurrentHashMap<Integer, AsyncCallback<?>> callbackMap = new ConcurrentHashMap<>();

    private String baseDir;
    private boolean ifExists;

    private ConnectionInfo ci;

    private final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public void addAsyncCallback(int id, AsyncCallback<?> ac) {
        callbackMap.put(id, ac);
    }

    public AsyncCallback<?> getAsyncCallback(int id) {
        return callbackMap.get(id);
    }

    public AsyncConnection(NetSocket socket) {
        this.socket = socket;
        this.transfer = new Transfer(this, socket);
    }

    public AsyncConnection(NetSocket socket, CountDownLatch readyLatch) {
        this(socket);
        this.readyLatch = readyLatch;
    }

    public Transfer getTransfer() {
        return transfer;
    }

    public CountDownLatch getReadyLatch() {
        return readyLatch;
    }

    public void writeInitPacket(ConnectionInfo ci) throws Exception {
        transfer.setSSL(ci.isSSL());
        transfer.init();
        writeRequestHeader(Session.SESSION_INIT);
        transfer.writeInt(Constants.TCP_PROTOCOL_VERSION_1); // minClientVersion
        transfer.writeInt(Constants.TCP_PROTOCOL_VERSION_1); // maxClientVersion
        transfer.writeString(ci.getDatabaseName());
        transfer.writeString(ci.getURL()); // 不带参数的URL
        transfer.writeString(ci.getUserName());
        transfer.writeBytes(ci.getUserPasswordHash());
        transfer.writeBytes(ci.getFilePasswordHash());
        transfer.writeBytes(ci.getFileEncryptionKey());
        String[] keys = ci.getKeys();
        transfer.writeInt(keys.length);
        for (String key : keys) {
            transfer.writeString(key).writeString(ci.getProperty(key));
        }
        transfer.flush();
    }

    private void readInitPacket() {
        try {
            int minClientVersion = transfer.readInt();
            if (minClientVersion < Constants.TCP_PROTOCOL_VERSION_MIN) {
                throw DbException.get(ErrorCode.DRIVER_VERSION_ERROR_2, "" + minClientVersion, ""
                        + Constants.TCP_PROTOCOL_VERSION_MIN);
            } else if (minClientVersion > Constants.TCP_PROTOCOL_VERSION_MAX) {
                throw DbException.get(ErrorCode.DRIVER_VERSION_ERROR_2, "" + minClientVersion, ""
                        + Constants.TCP_PROTOCOL_VERSION_MAX);
            }
            int clientVersion;
            int maxClientVersion = transfer.readInt();
            if (maxClientVersion >= Constants.TCP_PROTOCOL_VERSION_MAX) {
                clientVersion = Constants.TCP_PROTOCOL_VERSION_CURRENT;
            } else {
                clientVersion = minClientVersion;
            }
            transfer.setVersion(clientVersion);
            String dbName = transfer.readString();
            String originalURL = transfer.readString();
            String userName = transfer.readString();
            userName = StringUtils.toUpperEnglish(userName);
            session = createSession(originalURL, dbName, userName);
            transfer.setSession(session);
            writeResponseHeader(Session.SESSION_INIT);
            transfer.writeInt(Session.STATUS_OK);
            transfer.writeInt(clientVersion);
            transfer.flush();
        } catch (Throwable e) {
            sendError(e);
            stop = true;
        }
    }

    @Override
    public int compareTo(AsyncConnection o) {
        return socket.remoteAddress().host().compareTo(o.socket.remoteAddress().host());
    }

    private Session createSession(String originalURL, String dbName, String userName) throws IOException {
        ConnectionInfo ci = new ConnectionInfo(originalURL, dbName);
        ci.setUserName(userName);
        ci.setUserPasswordHash(transfer.readBytes());
        ci.setFilePasswordHash(transfer.readBytes());
        ci.setFileEncryptionKey(transfer.readBytes());

        int len = transfer.readInt();
        for (int i = 0; i < len; i++) {
            String key = transfer.readString();
            String value = transfer.readString();
            ci.addProperty(key, value, true); // 一些不严谨的client driver可能会发送重复的属性名
        }

        if (baseDir == null) {
            baseDir = SysProperties.getBaseDirSilently();
        }

        // override client's requested properties with server settings
        if (baseDir != null) {
            ci.setBaseDir(baseDir);
        }
        if (ifExists) {
            ci.setProperty("IFEXISTS", "TRUE");
        }
        this.ci = ci;
        return createSession();
    }

    private Session createSession() {
        try {
            Session session = ci.getSessionFactory().createSession(ci);
            if (ci.getProperty("IS_LOCAL") != null)
                session.setLocal(Boolean.parseBoolean(ci.getProperty("IS_LOCAL")));
            return session;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private Session getOrCreateSession(int connectionId) {
        Session session = sessions.get(connectionId);
        if (session == null) {
            session = createSession();
            Session s = sessions.putIfAbsent(connectionId, session);
            if (s != null && s != session) {
                session.close();
                session = s;
            }
        }
        return session;
    }

    private void closeSession() {
        if (session != null) {
            RuntimeException closeError = null;
            try {
                session.prepareStatement("ROLLBACK", -1).update();
            } catch (RuntimeException e) {
                closeError = e;
            } catch (Exception e) {
            }
            try {
                session.close();
            } catch (RuntimeException e) {
                if (closeError == null) {
                    closeError = e;
                }
            } catch (Exception e) {
            } finally {
                session = null;
            }
            if (closeError != null) {
                throw closeError;
            }
        }
    }

    /**
     * Close a connection.
     */
    void close() {
        try {
            stop = true;
            closeSession();
        } catch (Exception e) {
        } finally {
            transfer.close();
        }
    }

    /**
     * Cancel a running statement.
     *
     * @param targetSessionId the session id
     * @param statementId the statement to cancel
     */
    void cancelStatement(String targetSessionId, int statementId) {
        if (StringUtils.equals(targetSessionId, this.sessionId)) {
            PreparedStatement cmd = (PreparedStatement) cache.getObject(statementId, false);
            cmd.cancel();
        }
    }

    private void sendError(Throwable t) {
        try {
            SQLException e = DbException.convert(t).getSQLException();
            StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            String trace = writer.toString();
            String message;
            String sql;
            if (e instanceof JdbcSQLException) {
                JdbcSQLException j = (JdbcSQLException) e;
                message = j.getOriginalMessage();
                sql = j.getSQL();
            } else {
                message = e.getMessage();
                sql = null;
            }

            transfer.reset(); // 为什么要reset? 见reset中的注释

            transfer.writeInt(Session.STATUS_ERROR).writeString(e.getSQLState()).writeString(message).writeString(sql)
                    .writeInt(e.getErrorCode()).writeString(trace).flush();
        } catch (Exception e2) {
            // if writing the error does not work, close the connection
            stop = true;
        }
    }

    private void setParameters(PreparedStatement command) throws IOException {
        int len = transfer.readInt();
        List<? extends CommandParameter> params = command.getParameters();
        for (int i = 0; i < len; i++) {
            CommandParameter p = params.get(i);
            p.setValue(transfer.readValue());
        }
    }

    /**
     * Write the parameter meta data to the transfer object.
     *
     * @param p the parameter
     */
    private void writeParameterMetaData(CommandParameter p) throws IOException {
        transfer.writeInt(p.getType());
        transfer.writeLong(p.getPrecision());
        transfer.writeInt(p.getScale());
        transfer.writeInt(p.getNullable());
    }

    /**
     * Write a result column to the given output.
     *
     * @param result the result
     * @param i the column index
     */
    private void writeColumn(Result result, int i) throws IOException {
        transfer.writeString(result.getAlias(i));
        transfer.writeString(result.getSchemaName(i));
        transfer.writeString(result.getTableName(i));
        transfer.writeString(result.getColumnName(i));
        transfer.writeInt(result.getColumnType(i));
        transfer.writeLong(result.getColumnPrecision(i));
        transfer.writeInt(result.getColumnScale(i));
        transfer.writeInt(result.getDisplaySize(i));
        transfer.writeBoolean(result.isAutoIncrement(i));
        transfer.writeInt(result.getNullable(i));
    }

    private void writeRow(Result result, int count) throws IOException {
        try {
            int visibleColumnCount = result.getVisibleColumnCount();
            for (int i = 0; i < count; i++) {
                if (result.next()) {
                    transfer.writeBoolean(true);
                    Value[] v = result.currentRow();
                    for (int j = 0; j < visibleColumnCount; j++) {
                        transfer.writeValue(v[j]);
                    }
                } else {
                    transfer.writeBoolean(false);
                    break;
                }
            }
        } catch (Throwable e) {
            // 如果取结果集的下一行记录时发生了异常，
            // 结果集包必须加一个结束标记，结果集包后面跟一个异常包。
            transfer.writeBoolean(false);
            throw DbException.convert(e);
        }
    }

    private int getState(int oldModificationId) {
        if (session.getModificationId() == oldModificationId) {
            return Session.STATUS_OK;
        }
        return Session.STATUS_OK_STATE_CHANGED;
    }

    private void writeBatchResult(int[] result, int oldModificationId) throws IOException {
        int status;
        if (session.isClosed()) {
            status = Session.STATUS_CLOSED;
        } else {
            status = getState(oldModificationId);
        }
        transfer.writeInt(status);
        for (int i = 0; i < result.length; i++)
            transfer.writeInt(result[i]);

        transfer.flush();
    }

    final ConcurrentLinkedQueue<PreparedCommand> preparedCommandQueue = new ConcurrentLinkedQueue<>();

    private void executeQuery(Session session, int id, PreparedStatement command, int operation, int objectId,
            int maxRows, int fetchSize, int oldModificationId) throws IOException {
        PreparedCommand pc = new PreparedCommand(command, session, new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                final Result result = command.query(maxRows, false);
                cache.addObject(objectId, result);

                Response response = new Response(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        writeResponseHeader(operation);
                        transfer.writeInt(getState(oldModificationId)).writeInt(id);

                        if (operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_QUERY
                                || operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_QUERY)
                            transfer.writeString(session.getTransaction().getLocalTransactionNames());

                        int columnCount = result.getVisibleColumnCount();
                        transfer.writeInt(columnCount);
                        int rowCount = result.getRowCount();
                        transfer.writeInt(rowCount);
                        for (int i = 0; i < columnCount; i++) {
                            writeColumn(result, i);
                        }
                        int fetch = fetchSize;
                        if (rowCount != -1)
                            fetch = Math.min(rowCount, fetchSize);
                        writeRow(result, fetch);
                        transfer.flush();
                        return null;
                    }
                });
                response.run();
                return null;
            }
        });

        preparedCommandQueue.add(pc);
        CommandHandler.preparedCommandQueue.add(pc);
    }

    private void executeUpdate(Session session, int id, PreparedStatement command, int operation, int oldModificationId)
            throws IOException {
        PreparedCommand pc = new PreparedCommand(command, session, new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                int updateCount = command.update();
                int status;
                if (session.isClosed()) {
                    status = Session.STATUS_CLOSED;
                } else {
                    status = getState(oldModificationId);
                }
                writeResponseHeader(operation);
                transfer.writeInt(status);
                transfer.writeInt(id);
                if (operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_UPDATE
                        || operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_UPDATE)
                    transfer.writeString(session.getTransaction().getLocalTransactionNames());

                transfer.writeInt(updateCount);
                transfer.flush();
                return null;
            }
        });

        preparedCommandQueue.add(pc);
        CommandHandler.preparedCommandQueue.add(pc);
    }

    private void readStatus() throws IOException {
        int status = transfer.readInt();
        if (status == Session.STATUS_ERROR) {
            parseError();
        } else if (status == Session.STATUS_CLOSED) {
            transfer = null;
        } else if (status == Session.STATUS_OK_STATE_CHANGED) {
            // sessionStateChanged = true;
        } else if (status == Session.STATUS_OK) {
            // ok
        } else {
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "unexpected status " + status);
        }
    }

    public void parseError() throws IOException {
        String sqlstate = transfer.readString();
        String message = transfer.readString();
        String sql = transfer.readString();
        int errorCode = transfer.readInt();
        String stackTrace = transfer.readString();
        JdbcSQLException s = new JdbcSQLException(message, sql, sqlstate, errorCode, null, stackTrace);
        if (errorCode == ErrorCode.CONNECTION_BROKEN_1) {
            // allow re-connect
            IOException e = new IOException(s.toString());
            e.initCause(s);
            throw e;
        }
        throw DbException.convert(s);
    }

    private int clientVersion;

    public void flush() throws IOException {
        transfer.flush();
    }

    private void writeResponseHeader(int packetType) throws IOException {
        transfer.writeResponseHeader(packetType);
    }

    private void writeRequestHeader(int packetType) throws IOException {
        transfer.writeRequestHeader(packetType);
    }

    private boolean autoCommit = true;

    public boolean isAutoCommit() {
        return autoCommit;
    }

    private void process() throws IOException {
        int operation = transfer.readInt();
        boolean isRequest = (operation & 1) == 0;
        if (!isRequest)
            readStatus();
        operation = operation >> 1;
        switch (operation) {
        case Session.SESSION_INIT: {
            if (isRequest) {
                readInitPacket();
            } else {
                clientVersion = transfer.readInt();
                transfer.setVersion(clientVersion);
                writeRequestHeader(Session.SESSION_SET_ID);
                transfer.writeString(sessionId);
                flush();
            }
            break;
        }
        case Session.SESSION_SET_ID: {
            if (isRequest) {
                sessionId = transfer.readString();
                writeResponseHeader(Session.SESSION_SET_ID);
                transfer.writeInt(Session.STATUS_OK);
                transfer.writeBoolean(session.isAutoCommit());
                flush();
            } else {
                autoCommit = transfer.readBoolean();
                if (readyLatch != null) {
                    readyLatch.countDown();
                    readyLatch = null;
                }
            }
            break;
        }
        case Session.COMMAND_PREPARE_READ_PARAMS:
        case Session.COMMAND_PREPARE: {
            if (isRequest) {
                int id = transfer.readInt();
                int connectionId = transfer.readInt();
                Session session = getOrCreateSession(connectionId);
                String sql = transfer.readString();
                int old = session.getModificationId();
                PreparedStatement command = session.prepareStatement(sql, -1);
                command.setConnectionId(connectionId);
                cache.addObject(id, command);
                boolean isQuery = command.isQuery();
                writeResponseHeader(operation);
                transfer.writeInt(getState(old)).writeInt(id).writeBoolean(isQuery);
                if (operation == Session.COMMAND_PREPARE_READ_PARAMS) {
                    List<? extends CommandParameter> params = command.getParameters();
                    transfer.writeInt(params.size());
                    for (CommandParameter p : params) {
                        writeParameterMetaData(p);
                    }
                }
                flush();
            } else {
                int id = transfer.readInt();
                AsyncCallback<?> ac = getAsyncCallback(id);
                ac.run(transfer);
            }
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_QUERY: {
            if (isRequest) {
                session.setAutoCommit(false);
                session.setRoot(false);
            }
        }
        case Session.COMMAND_QUERY: {
            if (isRequest) {
                int id = transfer.readInt();
                int connectionId = transfer.readInt();
                Session session = getOrCreateSession(connectionId);
                String sql = transfer.readString();
                int objectId = transfer.readInt();
                int maxRows = transfer.readInt();
                int fetchSize = transfer.readInt();
                int old = session.getModificationId();
                PreparedStatement command = session.prepareStatement(sql, fetchSize);
                command.setConnectionId(connectionId);
                cache.addObject(id, command);
                executeQuery(session, id, command, operation, objectId, maxRows, fetchSize, old);
            } else {
                int id = transfer.readInt();
                AsyncCallback<?> ac = getAsyncCallback(id);
                ac.run(transfer);
            }
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_QUERY: {
            if (isRequest) {
                session.setAutoCommit(false);
                session.setRoot(false);
            }
        }
        case Session.COMMAND_PREPARED_QUERY: {
            if (isRequest) {
                int id = transfer.readInt();
                int connectionId = transfer.readInt();
                Session session = getOrCreateSession(connectionId);
                int objectId = transfer.readInt();
                int maxRows = transfer.readInt();
                int fetchSize = transfer.readInt();
                PreparedStatement command = (PreparedStatement) cache.getObject(id, false);
                command.setFetchSize(fetchSize);
                setParameters(command);
                int old = session.getModificationId();
                executeQuery(session, id, command, operation, objectId, maxRows, fetchSize, old);
            } else {
                int id = transfer.readInt();
                AsyncCallback<?> ac = getAsyncCallback(id);
                ac.run(transfer);
            }
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_UPDATE: {
            if (isRequest) {
                session.setAutoCommit(false);
                session.setRoot(false);
            }
        }
        case Session.COMMAND_UPDATE:
        case Session.COMMAND_REPLICATION_UPDATE: {
            if (isRequest) {
                int id = transfer.readInt();
                int connectionId = transfer.readInt();
                Session session = getOrCreateSession(connectionId);
                String sql = transfer.readString();
                int old = session.getModificationId();
                if (operation == Session.COMMAND_REPLICATION_UPDATE)
                    session.setReplicationName(transfer.readString());

                PreparedStatement command = session.prepareStatement(sql, -1);
                command.setConnectionId(connectionId);
                cache.addObject(id, command);
                executeUpdate(session, id, command, operation, old);
            } else {
                int id = transfer.readInt();
                if (operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_UPDATE)
                    session.getTransaction().addLocalTransactionNames(transfer.readString());

                int updateCount = transfer.readInt();

                IntAsyncCallback ac = (IntAsyncCallback) getAsyncCallback(id);
                ac.setResult(updateCount);
            }
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_UPDATE: {
            if (isRequest) {
                session.setAutoCommit(false);
                session.setRoot(false);
            }
        }
        case Session.COMMAND_PREPARED_UPDATE:
        case Session.COMMAND_REPLICATION_PREPARED_UPDATE: {
            if (isRequest) {
                int id = transfer.readInt();
                int connectionId = transfer.readInt();
                Session session = getOrCreateSession(connectionId);
                if (operation == Session.COMMAND_REPLICATION_PREPARED_UPDATE)
                    session.setReplicationName(transfer.readString());
                PreparedStatement command = (PreparedStatement) cache.getObject(id, false);
                setParameters(command);
                int old = session.getModificationId();
                executeUpdate(session, id, command, operation, old);
            } else {
                int id = transfer.readInt();
                if (operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_UPDATE)
                    session.getTransaction().addLocalTransactionNames(transfer.readString());

                int updateCount = transfer.readInt();

                IntAsyncCallback ac = (IntAsyncCallback) getAsyncCallback(id);
                ac.setResult(updateCount);
            }
            break;
        }
        case Session.COMMAND_STORAGE_DISTRIBUTED_PUT: {
            session.setAutoCommit(false);
            session.setRoot(false);
        }
        case Session.COMMAND_STORAGE_PUT:
        case Session.COMMAND_STORAGE_REPLICATION_PUT: {
            String mapName = transfer.readString();
            byte[] key = transfer.readBytes();
            byte[] value = transfer.readBytes();
            int old = session.getModificationId();
            if (operation == Session.COMMAND_STORAGE_REPLICATION_PUT)
                session.setReplicationName(transfer.readString());

            StorageMap<Object, Object> map = session.getStorageMap(mapName);

            DataType valueType = map.getValueType();
            Object k = map.getKeyType().read(ByteBuffer.wrap(key));
            Object v = valueType.read(ByteBuffer.wrap(value));
            Object result = map.put(k, v);
            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            if (operation == Session.COMMAND_STORAGE_DISTRIBUTED_PUT)
                transfer.writeString(session.getTransaction().getLocalTransactionNames());

            WriteBuffer writeBuffer = WriteBufferPool.poll();
            valueType.write(writeBuffer, result);
            ByteBuffer buffer = writeBuffer.getBuffer();
            buffer.flip();
            WriteBufferPool.offer(writeBuffer);
            transfer.writeByteBuffer(buffer);
            transfer.flush();
            break;
        }
        case Session.COMMAND_STORAGE_DISTRIBUTED_GET: {
            session.setAutoCommit(false);
            session.setRoot(false);
        }
        case Session.COMMAND_STORAGE_GET: {
            String mapName = transfer.readString();
            byte[] key = transfer.readBytes();
            int old = session.getModificationId();

            StorageMap<Object, Object> map = session.getStorageMap(mapName);

            DataType valueType = map.getValueType();
            Object result = map.get(map.getKeyType().read(ByteBuffer.wrap(key)));

            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            if (operation == Session.COMMAND_STORAGE_DISTRIBUTED_PUT)
                transfer.writeString(session.getTransaction().getLocalTransactionNames());

            WriteBuffer writeBuffer = WriteBufferPool.poll();
            valueType.write(writeBuffer, result);
            ByteBuffer buffer = writeBuffer.getBuffer();
            buffer.flip();
            WriteBufferPool.offer(writeBuffer);
            transfer.writeByteBuffer(buffer);
            transfer.flush();
            break;
        }
        case Session.COMMAND_STORAGE_MOVE_LEAF_PAGE: {
            String mapName = transfer.readString();
            ByteBuffer splitKey = transfer.readByteBuffer();
            ByteBuffer page = transfer.readByteBuffer();
            int old = session.getModificationId();
            StorageMap<Object, Object> map = session.getStorageMap(mapName);

            if (map instanceof Replication) {
                ((Replication) map).addLeafPage(splitKey, page);
            }

            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.flush();
            break;
        }
        case Session.COMMAND_STORAGE_REMOVE_LEAF_PAGE: {
            String mapName = transfer.readString();
            ByteBuffer key = transfer.readByteBuffer();
            int old = session.getModificationId();
            StorageMap<Object, Object> map = session.getStorageMap(mapName);

            if (map instanceof Replication) {
                ((Replication) map).removeLeafPage(key);
            }

            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.flush();
            break;
        }
        case Session.COMMAND_GET_META_DATA: {
            if (isRequest) {
                int id = transfer.readInt();
                int objectId = transfer.readInt();
                PreparedStatement command = (PreparedStatement) cache.getObject(id, false);
                Result result = command.getMetaData();
                cache.addObject(objectId, result);
                int columnCount = result.getVisibleColumnCount();
                writeResponseHeader(operation);
                transfer.writeInt(Session.STATUS_OK).writeInt(id).writeInt(columnCount).writeInt(0);
                for (int i = 0; i < columnCount; i++) {
                    writeColumn(result, i);
                }
                transfer.flush();
            } else {
                int id = transfer.readInt();
                AsyncCallback<?> ac = getAsyncCallback(id);
                ac.run(transfer);
            }
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_COMMIT: {
            int old = session.getModificationId();
            session.commit(false, transfer.readString());
            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.flush();
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_ROLLBACK: {
            int old = session.getModificationId();
            session.rollback();
            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.flush();
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_ADD_SAVEPOINT:
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_ROLLBACK_SAVEPOINT: {
            int old = session.getModificationId();
            String name = transfer.readString();
            if (operation == Session.COMMAND_DISTRIBUTED_TRANSACTION_ADD_SAVEPOINT)
                session.addSavepoint(name);
            else
                session.rollbackToSavepoint(name);
            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.flush();
            break;
        }
        case Session.COMMAND_DISTRIBUTED_TRANSACTION_VALIDATE: {
            int old = session.getModificationId();
            boolean isValid = session.validateTransaction(transfer.readString());
            int status;
            if (session.isClosed()) {
                status = Session.STATUS_CLOSED;
            } else {
                status = getState(old);
            }
            transfer.writeInt(status);
            transfer.writeBoolean(isValid);
            transfer.flush();
            break;
        }
        case Session.COMMAND_BATCH_STATEMENT_UPDATE: {
            int size = transfer.readInt();
            int[] result = new int[size];
            int old = session.getModificationId();
            for (int i = 0; i < size; i++) {
                String sql = transfer.readString();
                PreparedStatement command = session.prepareStatement(sql, -1);
                try {
                    result[i] = command.update();
                } catch (Exception e) {
                    result[i] = Statement.EXECUTE_FAILED;
                }
            }
            writeBatchResult(result, old);
            break;
        }
        case Session.COMMAND_BATCH_STATEMENT_PREPARED_UPDATE: {
            int id = transfer.readInt();
            int connectionId = transfer.readInt();
            Session session = getOrCreateSession(connectionId);
            int size = transfer.readInt();
            PreparedStatement command = (PreparedStatement) cache.getObject(id, false);
            List<? extends CommandParameter> params = command.getParameters();
            int paramsSize = params.size();
            int[] result = new int[size];
            int old = session.getModificationId();
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < paramsSize; j++) {
                    CommandParameter p = params.get(j);
                    p.setValue(transfer.readValue());
                }
                try {
                    result[i] = command.update();
                } catch (Exception e) {
                    result[i] = Statement.EXECUTE_FAILED;
                }
            }
            writeBatchResult(result, old);
            break;
        }
        case Session.COMMAND_CLOSE: {
            int id = transfer.readInt();
            PreparedStatement command = (PreparedStatement) cache.getObject(id, true);
            if (command != null) {
                command.close();
                cache.freeObject(id);
            }
            break;
        }
        case Session.RESULT_FETCH_ROWS: {
            int id = transfer.readInt();
            int count = transfer.readInt();
            Result result = (Result) cache.getObject(id, false);
            transfer.writeInt(Session.STATUS_OK);
            writeRow(result, count);
            transfer.flush();
            break;
        }
        case Session.RESULT_RESET: {
            int id = transfer.readInt();
            Result result = (Result) cache.getObject(id, false);
            result.reset();
            break;
        }
        case Session.RESULT_CHANGE_ID: {
            int oldId = transfer.readInt();
            int newId = transfer.readInt();
            Object obj = cache.getObject(oldId, false);
            cache.freeObject(oldId);
            cache.addObject(newId, obj);
            break;
        }
        case Session.RESULT_CLOSE: {
            int id = transfer.readInt();
            Result result = (Result) cache.getObject(id, true);
            if (result != null) {
                result.close();
                cache.freeObject(id);
            }
            break;
        }
        case Session.SESSION_SET_AUTO_COMMIT: {
            boolean autoCommit = transfer.readBoolean();
            session.setAutoCommit(autoCommit);
            transfer.writeInt(Session.STATUS_OK).flush();
            break;
        }
        case Session.SESSION_CLOSE: {
            stop = true;
            closeSession();
            transfer.writeInt(Session.STATUS_OK).flush();
            close();
            break;
        }
        case Session.SESSION_CANCEL_STATEMENT: {
            transfer.readString();
            int id = transfer.readInt();
            PreparedStatement command = (PreparedStatement) cache.getObject(id, false);
            if (command != null) {
                command.cancel();
                command.close();
                cache.freeObject(id);
            }
            break;
        }
        case Session.COMMAND_READ_LOB: {
            if (lobs == null) {
                lobs = SmallLRUCache.newInstance(Math.max(SysProperties.SERVER_CACHED_OBJECTS,
                        SysProperties.SERVER_RESULT_SET_FETCH_SIZE * 5));
            }
            long lobId = transfer.readLong();
            byte[] hmac = transfer.readBytes();
            CachedInputStream in = lobs.get(lobId);
            if (in == null) {
                in = new CachedInputStream(null);
                lobs.put(lobId, in);
            }
            long offset = transfer.readLong();
            int length = transfer.readInt();
            transfer.verifyLobMac(hmac, lobId);
            if (in.getPos() != offset) {
                LobStorage lobStorage = session.getDataHandler().getLobStorage();
                // only the lob id is used
                ValueLob lob = ValueLob.create(Value.BLOB, null, -1, lobId, hmac, -1);
                InputStream lobIn = lobStorage.getInputStream(lob, hmac, -1);
                in = new CachedInputStream(lobIn);
                lobs.put(lobId, in);
                lobIn.skip(offset);
            }
            // limit the buffer size
            length = Math.min(16 * Constants.IO_BUFFER_SIZE, length);
            byte[] buff = new byte[length];
            length = IOUtils.readFully(in, buff, length);
            transfer.writeInt(Session.STATUS_OK);
            transfer.writeInt(length);
            transfer.writeBytes(buff, 0, length);
            transfer.flush();
            break;
        }
        default:
            closeSession();
            close();
        }
    }

    /**
     * An input stream with a position.
     */
    private static class CachedInputStream extends FilterInputStream {

        private static final ByteArrayInputStream DUMMY = new ByteArrayInputStream(new byte[0]);
        private long pos;

        CachedInputStream(InputStream in) {
            super(in == null ? DUMMY : in);
            if (in == null) {
                pos = -1;
            }
        }

        @Override
        public int read(byte[] buff, int off, int len) throws IOException {
            len = super.read(buff, off, len);
            if (len > 0) {
                pos += len;
            }
            return len;
        }

        @Override
        public int read() throws IOException {
            int x = in.read();
            if (x >= 0) {
                pos++;
            }
            return x;
        }

        @Override
        public long skip(long n) throws IOException {
            n = super.skip(n);
            if (n > 0) {
                pos += n;
            }
            return n;
        }

        public long getPos() {
            return pos;
        }

    }

    private Buffer tmpBuffer;
    private final ConcurrentLinkedQueue<Buffer> bufferQueue = new ConcurrentLinkedQueue<>();

    @Override
    public void handle(Buffer buffer) {
        if (tmpBuffer != null) {
            buffer = tmpBuffer.appendBuffer(buffer);
            tmpBuffer = null;
        }

        int length = buffer.length();
        if (length < 4) {
            tmpBuffer = buffer;
            return;
        }
        int pos = 0;
        while (true) {
            int packetLength = buffer.getInt(pos);
            if (length - 4 == packetLength) {
                if (pos == 0) {
                    bufferQueue.offer(buffer);
                } else {
                    Buffer b = Buffer.buffer();
                    b.appendBuffer(buffer, pos, packetLength + 4);
                    bufferQueue.offer(b);
                }
                break;
            } else if (length - 4 > packetLength) {
                Buffer b = Buffer.buffer();
                b.appendBuffer(buffer, pos, packetLength + 4);
                bufferQueue.offer(b);

                pos = pos + packetLength + 4;
                length = length - (packetLength + 4);
                continue;
            } else {
                tmpBuffer = Buffer.buffer();
                tmpBuffer.appendBuffer(buffer, pos, length);
                break;
            }
        }

        parsePackets();
    }

    private void parsePackets() {
        while (!stop) {
            Buffer buffer = bufferQueue.poll();
            if (buffer == null)
                return;

            try {
                transfer.setBuffer(buffer);
                transfer.readInt(); // packetLength
                process();
            } catch (Throwable e) {
                sendError(e);
            }
        }
    }

    public void executeOneCommand() {
        PreparedCommand c = preparedCommandQueue.poll();
        if (c == null)
            return;
        try {
            c.run();
        } catch (Throwable e) {
            sendError(e);
        }
    }
}
