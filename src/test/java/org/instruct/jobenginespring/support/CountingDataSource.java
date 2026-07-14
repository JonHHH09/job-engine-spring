package org.instruct.jobenginespring.support;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statementExecutions = new AtomicInteger();
    private final AtomicInteger rowsRead = new AtomicInteger();
    private final AtomicInteger maxRowsReadPerResultSet = new AtomicInteger();
    private final AtomicInteger maxFetchSize = new AtomicInteger();
    private final AtomicInteger maxBatchSize = new AtomicInteger();

    public CountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public int statementExecutions() {
        return statementExecutions.get();
    }

    public int rowsRead() {
        return rowsRead.get();
    }

    public int maxRowsReadPerResultSet() {
        return maxRowsReadPerResultSet.get();
    }

    public int maxFetchSize() {
        return maxFetchSize.get();
    }

    public int maxBatchSize() {
        return maxBatchSize.get();
    }

    public void reset() {
        statementExecutions.set(0);
        rowsRead.set(0);
        maxRowsReadPerResultSet.set(0);
        maxFetchSize.set(0);
        maxBatchSize.set(0);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    private Connection wrapConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                connection.getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(connection, args);
                    if (result instanceof PreparedStatement preparedStatement) {
                        return wrapPreparedStatement(preparedStatement);
                    }
                    if (result instanceof Statement statement) {
                        return wrapStatement(statement);
                    }
                    return result;
                }
        );
    }

    private PreparedStatement wrapPreparedStatement(PreparedStatement statement) {
        var pendingBatch = new AtomicInteger();
        return (PreparedStatement) Proxy.newProxyInstance(
                statement.getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setFetchSize".equals(method.getName())) {
                        maxFetchSize.accumulateAndGet((Integer) args[0], Math::max);
                    } else if ("addBatch".equals(method.getName())) {
                        pendingBatch.incrementAndGet();
                    } else if ("clearBatch".equals(method.getName())) {
                        pendingBatch.set(0);
                    } else if ("executeBatch".equals(method.getName()) || "executeLargeBatch".equals(method.getName())) {
                        maxBatchSize.accumulateAndGet(pendingBatch.getAndSet(0), Math::max);
                    }
                    if (isExecutionMethod(method.getName())) {
                        statementExecutions.incrementAndGet();
                    }
                    Object result = method.invoke(statement, args);
                    return result instanceof ResultSet resultSet ? wrapResultSet(resultSet) : result;
                }
        );
    }

    private Statement wrapStatement(Statement statement) {
        return (Statement) Proxy.newProxyInstance(
                statement.getClass().getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if (isExecutionMethod(method.getName())) {
                        statementExecutions.incrementAndGet();
                    }
                    Object result = method.invoke(statement, args);
                    return result instanceof ResultSet resultSet ? wrapResultSet(resultSet) : result;
                }
        );
    }

    private static boolean isExecutionMethod(String methodName) {
        return switch (methodName) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch" -> true;
            default -> false;
        };
    }

    private ResultSet wrapResultSet(ResultSet resultSet) {
        var resultSetRows = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(
                resultSet.getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(resultSet, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                    if ("next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
                        rowsRead.incrementAndGet();
                        maxRowsReadPerResultSet.accumulateAndGet(resultSetRows.incrementAndGet(), Math::max);
                    }
                    return result;
                }
        );
    }
}
