package io.github.dhluong90.bpm.process.postgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;

/** Wraps a data source so that every prepared statement's SQL text is recorded, for tests that capture a transition. */
final class SqlRecorder {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    /** @return the SQL texts prepared since the last {@link #clear()}, in order */
    List<String> statements() {
        return statements;
    }

    void clear() {
        statements.clear();
    }

    /** @return a data source on the same database whose connections record into this recorder */
    DataSource wrap(DataSource target) {
        InvocationHandler connections = (proxy, method, args) -> {
            try {
                if (method.getName().equals("getConnection")) {
                    Connection connection = (Connection) method.invoke(target, args);
                    return Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if ((m.getName().equals("prepareStatement") || m.getName().equals("prepareCall"))
                                        && a != null && a.length > 0 && a[0] instanceof String sql) {
                                    statements.add(sql);
                                }
                                try {
                                    return m.invoke(connection, a);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (DataSource) Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(), new Class<?>[] {DataSource.class},
                connections);
    }
}
