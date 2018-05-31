/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.appender.db.jdbc;

import java.io.Serializable;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.StringLayout;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.appender.ManagerFactory;
import org.apache.logging.log4j.core.appender.db.AbstractDatabaseManager;
import org.apache.logging.log4j.core.appender.db.ColumnMapping;
import org.apache.logging.log4j.core.config.plugins.convert.DateTypeConverter;
import org.apache.logging.log4j.core.config.plugins.convert.TypeConverters;
import org.apache.logging.log4j.core.util.Closer;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.spi.ThreadContextMap;
import org.apache.logging.log4j.spi.ThreadContextStack;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.IndexedReadOnlyStringMap;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.Strings;

/**
 * An {@link AbstractDatabaseManager} implementation for relational databases accessed via JDBC.
 */
public final class JdbcDatabaseManager extends AbstractDatabaseManager {

    private static StatusLogger logger() {
        return StatusLogger.getLogger();
    }

    private static final JdbcDatabaseManagerFactory INSTANCE = new JdbcDatabaseManagerFactory();

    // NOTE: prepared statements are prepared in this order: column mappings, then column configs
    private final List<ColumnMapping> columnMappings;
    private final List<ColumnConfig> columnConfigs;
    private final ConnectionSource connectionSource;
    private final String sqlStatement;

    private Connection connection;
    private PreparedStatement statement;
    private boolean isBatchSupported;

    private JdbcDatabaseManager(final String name, final int bufferSize, final ConnectionSource connectionSource,
                                final String sqlStatement, final List<ColumnConfig> columnConfigs,
                                final List<ColumnMapping> columnMappings) {
        super(name, bufferSize);
        this.connectionSource = connectionSource;
        this.sqlStatement = sqlStatement;
        this.columnConfigs = columnConfigs;
        this.columnMappings = columnMappings;
    }

    @Override
    protected void startupInternal() throws Exception {
        this.connection = this.connectionSource.getConnection();
        final DatabaseMetaData metaData = this.connection.getMetaData();
        this.isBatchSupported = metaData.supportsBatchUpdates();
        logger().debug("Closing Connection {}", this.connection);
        Closer.closeSilently(this.connection);
    }

    @Override
    protected boolean shutdownInternal() {
        if (this.connection != null || this.statement != null) {
            return this.commitAndClose();
        }
        if (connectionSource != null) {
            connectionSource.stop();
        }
        return true;
    }

    @Override
    protected void connectAndStart() {
        try {
            this.connection = this.connectionSource.getConnection();
            this.connection.setAutoCommit(false);
            logger().debug("Preparing SQL: {}", this.sqlStatement);
            this.statement = this.connection.prepareStatement(this.sqlStatement);
        } catch (final SQLException e) {
            throw new AppenderLoggingException(
                    "Cannot write logging event or flush buffer; JDBC manager cannot connect to the database.", e);
        }
    }

    @Deprecated
    @Override
    protected void writeInternal(final LogEvent event) {
        writeInternal(event, null);
    }
    
    private void setFields(final MapMessage<?, ?> mapMessage) throws SQLException {
        final IndexedReadOnlyStringMap map = mapMessage.getIndexedReadOnlyStringMap();
        final String simpleName = statement.getClass().getName();
        int i = 1; // JDBC indices start at 1
        for (final ColumnMapping mapping : this.columnMappings) {
            final String source = mapping.getSource();
            final String key = Strings.isEmpty(source) ? mapping.getName() : source;
            final Object value = map.getValue(key);
            if (logger().isTraceEnabled()) {
                final String valueStr = value instanceof String ? "\"" + value + "\"" : Objects.toString(value, null);
                logger().trace("{} setObject({}, {}) for key '{}' and mapping '{}'", simpleName, i, valueStr, key,
                        mapping.getName());
            }
            statement.setObject(i++, value);
        }
    }

    @Override
    protected void writeInternal(final LogEvent event, final Serializable serializable) {
        StringReader reader = null;
        try {
            if (!this.isRunning() || this.connection == null || this.connection.isClosed() || this.statement == null
                    || this.statement.isClosed()) {
                throw new AppenderLoggingException(
                        "Cannot write logging event; JDBC manager not connected to the database.");
            }

            if (serializable instanceof MapMessage) {
                setFields((MapMessage<?, ?>) serializable);
            }
            int i = 1; // JDBC indices start at 1
            for (final ColumnMapping mapping : this.columnMappings) {
                if (ThreadContextMap.class.isAssignableFrom(mapping.getType())
                        || ReadOnlyStringMap.class.isAssignableFrom(mapping.getType())) {
                    this.statement.setObject(i++, event.getContextData().toMap());
                } else if (ThreadContextStack.class.isAssignableFrom(mapping.getType())) {
                    this.statement.setObject(i++, event.getContextStack().asList());
                } else if (Date.class.isAssignableFrom(mapping.getType())) {
                    this.statement.setObject(i++, DateTypeConverter.fromMillis(event.getTimeMillis(),
                            mapping.getType().asSubclass(Date.class)));
                } else {
                    StringLayout layout = mapping.getLayout();
                    if (layout != null) {
                        if (Clob.class.isAssignableFrom(mapping.getType())) {
                            this.statement.setClob(i++, new StringReader(layout.toSerializable(event)));
                        } else if (NClob.class.isAssignableFrom(mapping.getType())) {
                            this.statement.setNClob(i++, new StringReader(layout.toSerializable(event)));
                        } else {
                            final Object value = TypeConverters.convert(layout.toSerializable(event), mapping.getType(),
                                    null);
                            if (value == null) {
                                this.statement.setNull(i++, Types.NULL);
                            } else {
                                this.statement.setObject(i++, value);
                            }
                        }
                    }
                }
            }
            for (final ColumnConfig column : this.columnConfigs) {
                if (column.isEventTimestamp()) {
                    this.statement.setTimestamp(i++, new Timestamp(event.getTimeMillis()));
                } else if (column.isClob()) {
                    reader = new StringReader(column.getLayout().toSerializable(event));
                    if (column.isUnicode()) {
                        this.statement.setNClob(i++, reader);
                    } else {
                        this.statement.setClob(i++, reader);
                    }
                } else if (column.isUnicode()) {
                    this.statement.setNString(i++, column.getLayout().toSerializable(event));
                } else {
                    this.statement.setString(i++, column.getLayout().toSerializable(event));
                }
            }

            if (this.isBatchSupported) {
                this.statement.addBatch();
            } else if (this.statement.executeUpdate() == 0) {
                throw new AppenderLoggingException(
                        "No records inserted in database table for log event in JDBC manager.");
            }
        } catch (final SQLException e) {
            throw new AppenderLoggingException("Failed to insert record for log event in JDBC manager: " +
                    e.getMessage(), e);
        } finally {
            Closer.closeSilently(reader);
        }
    }

    @Override
    protected boolean commitAndClose() {
        boolean closed = true;
        try {
            if (this.connection != null && !this.connection.isClosed()) {
                if (this.isBatchSupported) {
                    logger().debug("Executing batch PreparedStatement {}", this.statement);
                    this.statement.executeBatch();
                }
                logger().debug("Committing Connection {}", this.connection);
                this.connection.commit();
            }
        } catch (final SQLException e) {
            throw new AppenderLoggingException("Failed to commit transaction logging event or flushing buffer.", e);
        } finally {
            try {
                logger().debug("Closing PreparedStatement {}", this.statement);
                Closer.close(this.statement);
            } catch (final Exception e) {
                logWarn("Failed to close SQL statement logging event or flushing buffer", e);
                closed = false;
            } finally {
                this.statement = null;
            }

            try {
                logger().debug("Closing Connection {}", this.connection);
                Closer.close(this.connection);
            } catch (final Exception e) {
                logWarn("Failed to close database connection logging event or flushing buffer", e);
                closed = false;
            } finally {
                this.connection = null;
            }
        }
        return closed;
    }

    /**
     * Creates a JDBC manager for use within the {@link JdbcAppender}, or returns a suitable one if it already exists.
     *
     * @param name The name of the manager, which should include connection details and hashed passwords where possible.
     * @param bufferSize The size of the log event buffer.
     * @param connectionSource The source for connections to the database.
     * @param tableName The name of the database table to insert log events into.
     * @param columnConfigs Configuration information about the log table columns.
     * @return a new or existing JDBC manager as applicable.
     * @deprecated use {@link #getManager(String, int, Layout, ConnectionSource, String, ColumnConfig[], ColumnMapping[])}
     */
    @Deprecated
    public static JdbcDatabaseManager getJDBCDatabaseManager(final String name, final int bufferSize,
                                                             final ConnectionSource connectionSource,
                                                             final String tableName,
                                                             final ColumnConfig[] columnConfigs) {

        return getManager(name,
            new FactoryData(bufferSize, null, connectionSource, tableName, columnConfigs, new ColumnMapping[0]),
            getFactory());
    }

    /**
     * Creates a JDBC manager for use within the {@link JdbcAppender}, or returns a suitable one if it already exists.
     *
     * @param name The name of the manager, which should include connection details and hashed passwords where possible.
     * @param bufferSize The size of the log event buffer.
     * @param connectionSource The source for connections to the database.
     * @param tableName The name of the database table to insert log events into.
     * @param columnConfigs Configuration information about the log table columns.
     * @param columnMappings column mapping configuration (including type conversion).
     * @return a new or existing JDBC manager as applicable.
     * @deprecated use {@link #getManager(String, int, Layout, ConnectionSource, String, ColumnConfig[], ColumnMapping[])}
     */
    @Deprecated
    public static JdbcDatabaseManager getManager(final String name,
                                                 final int bufferSize,
                                                 final ConnectionSource connectionSource,
                                                 final String tableName,
                                                 final ColumnConfig[] columnConfigs,
                                                 final ColumnMapping[] columnMappings) {
        return getManager(name, new FactoryData(bufferSize, null, connectionSource, tableName, columnConfigs, columnMappings),
            getFactory());
    }

    /**
     * Creates a JDBC manager for use within the {@link JdbcAppender}, or returns a suitable one if it already exists.
     *
     * @param name The name of the manager, which should include connection details and hashed passwords where possible.
     * @param bufferSize The size of the log event buffer.
     * @param layout The Appender-level layout
     * @param connectionSource The source for connections to the database.
     * @param tableName The name of the database table to insert log events into.
     * @param columnConfigs Configuration information about the log table columns.
     * @param columnMappings column mapping configuration (including type conversion).
     * @return a new or existing JDBC manager as applicable.
     */
    public static JdbcDatabaseManager getManager(final String name,
                                                 final int bufferSize,
                                                 final Layout<? extends Serializable> layout,
                                                 final ConnectionSource connectionSource,
                                                 final String tableName,
                                                 final ColumnConfig[] columnConfigs,
                                                 final ColumnMapping[] columnMappings) {
        return getManager(name, new FactoryData(bufferSize, layout, connectionSource, tableName, columnConfigs, columnMappings),
            getFactory());
    }

    private static JdbcDatabaseManagerFactory getFactory() {
        return INSTANCE;
    }

    /**
     * Encapsulates data that {@link JdbcDatabaseManagerFactory} uses to create managers.
     */
    private static final class FactoryData extends AbstractDatabaseManager.AbstractFactoryData {
        private final ConnectionSource connectionSource;
        private final String tableName;
        private final ColumnConfig[] columnConfigs;
        private final ColumnMapping[] columnMappings;

        protected FactoryData(final int bufferSize, final Layout<? extends Serializable> layout,
                final ConnectionSource connectionSource, final String tableName, final ColumnConfig[] columnConfigs,
                final ColumnMapping[] columnMappings) {
            super(bufferSize, layout);
            this.connectionSource = connectionSource;
            this.tableName = tableName;
            this.columnConfigs = columnConfigs;
            this.columnMappings = columnMappings;
        }
    }

    /**
     * Creates managers.
     */
    private static final class JdbcDatabaseManagerFactory implements ManagerFactory<JdbcDatabaseManager, FactoryData> {
        
        private static final char PARAMETER_MARKER = '?';

        @Override
        public JdbcDatabaseManager createManager(final String name, final FactoryData data) {
            final StringBuilder sb = new StringBuilder("INSERT INTO ").append(data.tableName).append(" (");
            // so this gets a little more complicated now that there are two ways to configure column mappings, but
            // both mappings follow the same exact pattern for the prepared statement
            int i = 1;
            for (final ColumnMapping mapping : data.columnMappings) {
                final  String mappingName = mapping.getName();
                logger().trace("Adding INSERT ColumnMapping[{}]: {}={} ", i++, mappingName, mapping);
                sb.append(mappingName).append(',');
            }
            for (final ColumnConfig config : data.columnConfigs) {
                sb.append(config.getColumnName()).append(',');
            }
            // at least one of those arrays is guaranteed to be non-empty
            sb.setCharAt(sb.length() - 1, ')');
            sb.append(" VALUES (");
            i = 1;
            final List<ColumnMapping> columnMappings = new ArrayList<>(data.columnMappings.length);
            for (final ColumnMapping mapping : data.columnMappings) {
                final String mappingName = mapping.getName();
                if (Strings.isNotEmpty(mapping.getLiteralValue())) {
                    logger().trace("Adding INSERT VALUES literal for ColumnMapping[{}]: {}={} ", i, mappingName, mapping.getLiteralValue());
                    sb.append(mapping.getLiteralValue());
                }
                if (Strings.isNotEmpty(mapping.getParameter())) {
                    logger().trace("Adding INSERT VALUES parameter for ColumnMapping[{}]: {}={} ", i, mappingName, mapping.getParameter());
                    sb.append(mapping.getParameter());
                    columnMappings.add(mapping);
                } else {
                    logger().trace("Adding INSERT VALUES parameter marker for ColumnMapping[{}]: {}={} ", i, mappingName, PARAMETER_MARKER);
                    sb.append(PARAMETER_MARKER);
                    columnMappings.add(mapping);
                }
                sb.append(',');
                i++;
            }
            final List<ColumnConfig> columnConfigs = new ArrayList<>(data.columnConfigs.length);
            for (final ColumnConfig config : data.columnConfigs) {
                if (Strings.isNotEmpty(config.getLiteralValue())) {
                    sb.append(config.getLiteralValue());
                } else {
                    sb.append(PARAMETER_MARKER);
                    columnConfigs.add(config);
                }
                sb.append(',');
            }
            // at least one of those arrays is guaranteed to be non-empty
            sb.setCharAt(sb.length() - 1, ')');
            final String sqlStatement = sb.toString();

            return new JdbcDatabaseManager(name, data.getBufferSize(), data.connectionSource, sqlStatement,
                columnConfigs, columnMappings);
        }
    }

}
