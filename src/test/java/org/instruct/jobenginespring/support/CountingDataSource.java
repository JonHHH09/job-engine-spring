package org.instruct.jobenginespring.support;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statementExecutions = new AtomicInteger();

    public CountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public int statementExecutions() {
        return statementExecutions.get();
    }

    public void reset() {
        statementExecutions.set(0);
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
        return (PreparedStatement) Proxy.newProxyInstance(
                statement.getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (isExecutionMethod(method.getName())) {
                        statementExecutions.incrementAndGet();
                    }
                    return method.invoke(statement, args);
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
                    return method.invoke(statement, args);
                }
        );
    }

    private static boolean isExecutionMethod(String methodName) {
        return switch (methodName) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch" -> true;
            default -> false;
        };
    }
}
