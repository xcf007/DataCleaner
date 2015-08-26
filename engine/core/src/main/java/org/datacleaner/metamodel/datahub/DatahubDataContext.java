/**
 * DataCleaner (community edition)
 * Copyright (C) 2014 Neopost - Customer Information Management
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.datacleaner.metamodel.datahub;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.AccessControlException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.metamodel.AbstractDataContext;
import org.apache.metamodel.UpdateScript;
import org.apache.metamodel.UpdateableDataContext;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.datacleaner.metamodel.datahub.utils.JsonSchemasResponseParser;
import org.datacleaner.util.http.MonitorHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatahubDataContext extends AbstractDataContext implements
        UpdateableDataContext {
    private static final Logger logger = LoggerFactory
            .getLogger(DatahubDataContext.class);

    private DatahubConnection _connection;

    private Map<String, DatahubSchema> _schemas;

    public DatahubDataContext(String host, Integer port, String username,
            String password, String tenantId, boolean https,
            boolean acceptUnverifiedSslPeers, String securityMode) {
        _connection = new DatahubConnection(host, port, username, password,
                tenantId, https, acceptUnverifiedSslPeers, securityMode);
        _schemas = getDatahubSchemas();
    }

    private static final Pattern COLON = Pattern
            .compile("%3A", Pattern.LITERAL);
    private static final Pattern SLASH = Pattern
            .compile("%2F", Pattern.LITERAL);
    private static final Pattern QUEST_MARK = Pattern.compile("%3F",
            Pattern.LITERAL);
    private static final Pattern EQUAL = Pattern
            .compile("%3D", Pattern.LITERAL);
    private static final Pattern AMP = Pattern.compile("%26", Pattern.LITERAL);

    public static String encodeUrl(String url) {
        // if (checkForExternal(url)) {
        String value;
        try {
            value = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        value = COLON.matcher(value).replaceAll(":");
        value = SLASH.matcher(value).replaceAll("/");
        value = QUEST_MARK.matcher(value).replaceAll("?");
        value = EQUAL.matcher(value).replaceAll("=");
        return AMP.matcher(value).replaceAll("&");
    }

    private Map<String, DatahubSchema> getDatahubSchemas() {
        Map<String, DatahubSchema> schemas = new HashMap<String, DatahubSchema>();
        List<String> datastoreNames = getDataStoreNames();
        for (String datastoreName : datastoreNames) {
            String uri = _connection.getRepositoryUrl() + "/datastores" + "/"
                    + datastoreName + ".schemas";
            logger.debug("request {}", uri);
            HttpGet request = new HttpGet(encodeUrl(uri));
            HttpResponse response = executeRequest(request);
            HttpEntity entity = response.getEntity();
            JsonSchemasResponseParser parser = new JsonSchemasResponseParser();
            try {
                DatahubSchema schema = parser.parseJsonSchema(entity
                        .getContent());
                schema.setDatastoreName(datastoreName);
                schemas.put(schema.getName(), schema);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return schemas;
    }

    @Override
    public void executeUpdate(UpdateScript arg0) {
        // TODO Auto-generated method stub
    }
    
    @Override
    public DataSet executeQuery(final Query query) {
        Table table = query.getFromClause().getItem(0).getTable();
        String dataStoreName = ((DatahubSchema) table.getSchema())
                .getDatastoreName();
        String queryString = getQueryString(table, query);

        String uri = encodeUrl(_connection.getRepositoryUrl() + "/datastores" + "/"
                + dataStoreName + ".query?");

        return new DatahubDataSet(uri, query, queryString, _connection);
    }

    private String getQueryString(Table table, Query query) {
        String queryString = query.toSql();
        queryString = queryString.replace(table.getName() + ".", "");        
        return queryString;
    }

    private List<String> getDataStoreNames() {
        String uri = _connection.getRepositoryUrl() + "/datastores";
        logger.debug("request {}", uri);
        HttpGet request = new HttpGet(encodeUrl(uri));
        HttpResponse response = executeRequest(request);
        HttpEntity entity = response.getEntity();
        JsonSchemasResponseParser parser = new JsonSchemasResponseParser();
        try {
            return parser.parseDataStoreArray(entity.getContent());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Schema testGetMainSchema() {
        return getDefaultSchema();
    }

    private HttpResponse executeRequest(HttpGet request) {

        MonitorHttpClient httpClient = _connection.getHttpClient();
        HttpResponse response;
        try {
            response = httpClient.execute(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 403) {
            throw new AccessControlException(
                    "You are not authorized to access the service");
        }
        if (statusCode == 404) {
            throw new AccessControlException(
                    "Could not connect to Datahub: not found");
        }
        if (statusCode != 200) {
            throw new IllegalStateException("Unexpected response status code: "
                    + statusCode);
        }
        return response;
    }

    @Override
    protected String[] getSchemaNamesInternal() {
        return _schemas.keySet().toArray(new String[_schemas.size()]);
    }

    @Override
    protected String getDefaultSchemaName() {
        return "MDM";
    }

    @Override
    protected Schema getSchemaByNameInternal(String name) {
        return _schemas.get(name);
    }

}
