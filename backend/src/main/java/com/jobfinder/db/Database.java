package com.jobfinder.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin JDBC access layer (no ORM). Statements auto-commit unless they run inside
 * {@link #inTransaction(Supplier)}. Rows are returned as ordered maps with JSON friendly values.
 */
@Repository
public class Database {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqlQueries queries;
    private final ObjectMapper mapper;

    public Database(JdbcTemplate jdbc, PlatformTransactionManager manager, SqlQueries queries, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.queries = queries;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> fetchAll(String queryName, Object... params) {
        String sql = queries.get(queryName);
        SqlLog.record(sql, params);
        RowMapper<Map<String, Object>> rows = (resultSet, index) -> toRow(resultSet);
        return jdbc.query(sql, statement -> bind(statement, params), rows);
    }

    /** @return the first row, or {@code null} when the query returns nothing */
    public Map<String, Object> fetchOne(String queryName, Object... params) {
        List<Map<String, Object>> rows = fetchAll(queryName, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void execute(String queryName, Object... params) {
        String sql = queries.get(queryName);
        SqlLog.record(sql, params);
        jdbc.execute(sql, (PreparedStatementCallback<Void>) statement -> {
            bind(statement, params);
            statement.execute();
            return null;
        });
    }

    /** Runs the work in one transaction; any runtime exception rolls it back. */
    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                statement.setNull(i + 1, Types.OTHER);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
    }

    private Map<String, Object> toRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), readValue(resultSet, i, meta.getColumnTypeName(i)));
        }
        return row;
    }

    private Object readValue(ResultSet resultSet, int index, String type) throws SQLException {
        switch (type) {
            case "timestamptz": {
                OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
                return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            case "uuid":
            case "date":
            case "citext":
                return resultSet.getString(index);
            case "json":
            case "jsonb": {
                String value = resultSet.getString(index);
                try {
                    return value == null ? null : mapper.readValue(value, Object.class);
                } catch (JsonProcessingException error) {
                    throw new SQLException("Invalid JSON in database", error);
                }
            }
            default: {
                Object value = resultSet.getObject(index);
                if (value instanceof Array array) {
                    return new ArrayList<>(Arrays.asList((Object[]) array.getArray()));
                }
                return value;
            }
        }
    }
}
