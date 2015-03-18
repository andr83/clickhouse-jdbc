package ru.yandex.metrika.clickhouse;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import ru.yandex.metrika.clickhouse.config.ClickHouseSource;
import ru.yandex.metrika.clickhouse.copypaste.*;
import ru.yandex.metrika.clickhouse.except.ClickhouseExceptionSpecifier;
import ru.yandex.metrika.clickhouse.util.CopypasteUtils;
import ru.yandex.metrika.clickhouse.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jkee on 14.03.15.
 */
public class CHStatement implements Statement {

    private static final Logger log = Logger.of(CHStatement.class);

    private final CloseableHttpClient client;

    private HttpConnectionProperties properties = new HttpConnectionProperties();

    private ClickHouseSource source;

    public CHStatement(CloseableHttpClient client, ClickHouseSource source) {
        this.client = client;
        this.source = source;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        log.debug("Ex: " + sql);
        InputStream is = getInputStream(sql, null, false);
        try {
            return new CHResultSet(properties.isCompress()
                    ? new ClickhouseLZ4Stream(is) : is, properties.getBufferSize());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        ResultSet rs = null;
        try {
            rs = executeQuery(sql);
            while (rs.next()) {}
        } finally {
            try { rs.close(); } catch (Exception e) {};
        }
        return 1;
    }

    @Override
    public void close() throws SQLException {
        try {
            client.close();
        } catch (IOException e) {
            throw new CHException("HTTP client close exception", e);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {

    }

    @Override
    public int getMaxRows() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {

    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {

    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {

    }

    @Override
    public void cancel() throws SQLException {

    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {

    }

    @Override
    public void setCursorName(String name) throws SQLException {

    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return null;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return 0;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {

    }

    @Override
    public int getFetchDirection() throws SQLException {
        return 0;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {

    }

    @Override
    public int getFetchSize() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return 0;
    }

    @Override
    public void addBatch(String sql) throws SQLException {

    }

    @Override
    public void clearBatch() throws SQLException {

    }

    @Override
    public int[] executeBatch() throws SQLException {
        return new int[0];
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return 0;
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return 0;
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return 0;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return false;
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return false;
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return 0;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return false;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {

    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    public static String clickhousifySql(String sql) {
        return clickhousifySql(sql, "TabSeparatedWithNamesAndTypes");
    }

    public static String clickhousifySql(String sql, String format) {
        sql = sql.trim();
        if (!sql.replace(";", "").trim().endsWith(" TabSeparatedWithNamesAndTypes")
                && !sql.replace(";", "").trim().endsWith(" TabSeparated")
                && !sql.replace(";", "").trim().endsWith(" JSONCompact")) {
            if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);
            sql += " FORMAT " + format + ';';
        }
        return sql;
    }

    private InputStream getInputStream(String sql,
                                       Map<String, String> additionalClickHouseDBParams,
                                       boolean ignoreDatabase
    ) throws CHException {
        sql = clickhousifySql(sql);
        log.debug("Executing SQL: " + sql);
        URI uri = null;
        try {
            Map<String, String> params = getParams(false);
            if (additionalClickHouseDBParams != null && !additionalClickHouseDBParams.isEmpty()) {
                params.putAll(additionalClickHouseDBParams);
            }
            List<String> paramPairs = new ArrayList<String>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                paramPairs.add(entry.getKey() + '=' + entry.getValue());
            }
            String query = CopypasteUtils.join(paramPairs, '&');
            uri = new URI("http", null, source.getHost(), source.getPort(),
                    "/", query, null);
        } catch (URISyntaxException e) {
            log.error("Mailformed URL: " + e.getMessage());
            throw new IllegalStateException("illegal configuration of db");
        }
        log.debug("Request url: " + uri);
        HttpPost post = new HttpPost(uri);
        post.setEntity(new StringEntity(sql, CopypasteUtils.UTF_8));
        HttpEntity entity = null;
        InputStream is = null;
        try {
            HttpResponse response = client.execute(post);
            entity = response.getEntity();
            if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                String chMessage = null;
                try {
                    chMessage = EntityUtils.toString(response.getEntity());
                } catch (IOException e) {
                    chMessage = "error while read response "+ e.getMessage();
                }
                EntityUtils.consumeQuietly(entity);
                throw ClickhouseExceptionSpecifier.specify(chMessage, source.getHost(), source.getPort());
            }
            if (entity.isStreaming()) {
                is = entity.getContent();
            } else {
                FastByteArrayOutputStream baos = new FastByteArrayOutputStream();
                entity.writeTo(baos);
                is = baos.convertToInputStream();
            }
            return is;
        } catch (IOException e) {
            log.info("Error during connection to " + source + ", reporting failure to data source, message: " + e.getMessage());
            EntityUtils.consumeQuietly(entity);
            try { if (is != null) is.close(); } catch (IOException ignored) { }
            log.info("Error sql: " + sql);
            throw new CHException("Unknown IO exception", e);
        }
    }

    public Map<String, String> getParams(boolean ignoreDatabase) {
        Map<String, String> params = new HashMap<String, String>();
        //в clickhouse бывают таблички без базы (т.е. в базе default)
        if (!CopypasteUtils.isBlank(source.getDb()) && !ignoreDatabase) {
            params.put("database", source.getDb());
        }
        if (properties.isCompress()) {
            params.put("compress", "1");
        }
        // нам всегда нужны min и max в ответе
        params.put("extremes", "1");
        if (CopypasteUtils.isBlank(properties.getProfile())) {
            if (properties.getMaxThreads() != null)
                params.put("max_threads", String.valueOf(properties.getMaxThreads()));
            // да, там в секундах
            params.put("max_execution_time", String.valueOf((properties.getSocketTimeout() + properties.getDataTransferTimeout()) / 1000));
            if (properties.getMaxBlockSize() != null) {
                params.put("max_block_size", String.valueOf(properties.getMaxBlockSize()));
            }
        } else {
            params.put("profile", properties.getProfile());
        }
        //в кликхаус иногда бывает user
        if (properties.getUser() != null) {
            params.put("user", properties.getUser());
        }
        return params;
    }
}