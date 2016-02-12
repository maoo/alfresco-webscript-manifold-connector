package org.alfresco.consulting.indexer.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public class WebScriptsAlfrescoClient implements AlfrescoClient {
    private static final String LAST_TXN_ID = "last_txn_id";
    private static final String DOCS = "docs";
    private static final String LAST_ACL_CS_ID = "last_acl_changeset_id";
    private static final String URL_PARAM_LAST_TXN_ID = "lastTxnId";
    private static final String URL_PARAM_LAST_ACL_CS_ID = "lastAclChangesetId";
    private static final String STORE_ID = "store_id";
    private static final String STORE_PROTOCOL = "store_protocol";
    private static final String USERNAME = "username";
    private static final String AUTHORITIES = "authorities";

    private static final String URL_PARAM_MAX_ACL_CS = "maxAclChangesets";
    private static final String URL_PARAM_MAX_TXNS = "maxTxns";
    private static final String URL_PARAM_REINDEX_FROM = "reindexfrom";
    private static final String URL_PARAM_REINDEX_START = "startIndex";
    private static final String URL_PARAM_REINDEX_TO = "toIndex";
    private static final long NOT_SET = -1;
    private final Gson gson = new Gson();
    private final String changesUrl;
    private final String metadataUrl;
    private final String authoritiesUrl;
    private final String username;
    private final String password;

    private final Logger logger = LoggerFactory.getLogger(WebScriptsAlfrescoClient.class);

    public WebScriptsAlfrescoClient(String protocol, String hostname, String endpoint, String storeProtocol,
            String storeId) {
        this(protocol, hostname, endpoint, storeProtocol, storeId, null, null);
    }

    public WebScriptsAlfrescoClient(String protocol, String hostname, String endpoint, String storeProtocol,
            String storeId, String username, String password) {
        changesUrl = String
                .format("%s://%s%s/node/changes/%s/%s", protocol, hostname, endpoint, storeProtocol, storeId);
        metadataUrl = String.format("%s://%s%s/node/details/%s/%s", protocol, hostname, endpoint, storeProtocol,
                storeId);
        authoritiesUrl = String.format("%s://%s%s/api/node/auth/resolve/", protocol, hostname, endpoint);
        this.username = username;
        this.password = password;
    }

    @Override
    public AlfrescoResponse fetchEvents(long lastTransactionId, long lastAclChangesetId, long maxTransactions,
            long maxAclChangesets) {
        HttpResponse response;
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            String urlWithParameter = String.format("%s?%s", changesUrl,
                    incrementalUrlParameters(lastTransactionId, lastAclChangesetId, maxTransactions, maxAclChangesets));

            logger.debug("Hitting url: {}", urlWithParameter);

            HttpGet httpGet = createGetRequest(urlWithParameter);
            response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            AlfrescoResponse afResponse = fromHttpEntity(entity);
            EntityUtils.consume(entity);
            return afResponse;
        } catch (IOException e) {
            logger.warn("Failed to fetch nodes.", e);
            throw new AlfrescoDownException("Alfresco appears to be down", e);
        }
    }
    
    @Override
    public AlfrescoResponse fetchNodes(long lastTransactionId, long lastAclChangesetId) {
        return fetchEvents(lastTransactionId, lastAclChangesetId, NOT_SET, NOT_SET);
    }

    @Override
    public AlfrescoResponse fetchDocuments(String path, long reIndexFrom, long reIndexTo) {
        HttpResponse response;
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            String urlWithParameter = String.format("%s?%s", changesUrl,
                    fullIndexUrlParameters(path, reIndexFrom, reIndexTo));

            logger.debug("Hitting url: {}", urlWithParameter);

            HttpGet httpGet = createGetRequest(urlWithParameter);
            response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            AlfrescoResponse afResponse = fromHttpEntity(entity);
            EntityUtils.consume(entity);
            return afResponse;
        } catch (IOException e) {
            logger.warn("Failed to fetch documents.", e);
            throw new AlfrescoDownException("Alfresco appears to be down", e);
        }
    }

    private String fullIndexUrlParameters(String path, long reIndexStart, long reIndexTo) {
        String encodedPath;
        try {
            encodedPath = URLEncoder.encode(path.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error encoding path");
        }
        StringBuilder params = new StringBuilder();
        params.append(String.format("%s=%s", URL_PARAM_REINDEX_FROM, encodedPath));
        params.append(String.format("&%s=%d", URL_PARAM_REINDEX_START, reIndexStart));
        params.append(String.format("&%s=%d", URL_PARAM_REINDEX_TO, reIndexTo));
        return params.toString();

    }

    private String incrementalUrlParameters(long lastTransactionId, long lastAclChangesetId, long maxTransactions,
            long maxAclChangesets) {
        StringBuilder params = new StringBuilder();
        params.append(String.format("%s=%d&%s=%d", URL_PARAM_LAST_TXN_ID, lastTransactionId, URL_PARAM_LAST_ACL_CS_ID,
                lastAclChangesetId));
        if (maxTransactions != NOT_SET) {
            params.append(String.format("&%s=%d", URL_PARAM_MAX_TXNS, maxTransactions));
        }
        if (maxAclChangesets != NOT_SET) {
            params.append(String.format("&%s=%d", URL_PARAM_MAX_ACL_CS, maxAclChangesets));
        }
        return params.toString();
    }

    private HttpGet createGetRequest(String url) {
        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader("Accept", "application/json");
        if (useBasicAuthentication()) {
            httpGet.addHeader(
                    "Authorization",
                    "Basic "
                            + Base64.encodeBase64String(String.format("%s:%s", username, password).getBytes(
                                    Charset.forName("UTF-8"))));
        }
        return httpGet;
    }

    private boolean useBasicAuthentication() {
        return username != null && !"".equals(username) && password != null;
    }

    private AlfrescoResponse fromHttpEntity(HttpEntity entity) throws IOException {
        Reader entityReader = new InputStreamReader(entity.getContent());
        JsonObject responseObject = gson.fromJson(entityReader, JsonObject.class);
        ArrayList<Map<String, Object>> documents = new ArrayList<Map<String, Object>>();

        long lastTransactionId = getStringAsLong(responseObject, LAST_TXN_ID, 0L);
        long lastAclChangesetId = getStringAsLong(responseObject, LAST_ACL_CS_ID, 0L);
        String storeId = getString(responseObject, STORE_ID);
        String storeProtocol = getString(responseObject, STORE_PROTOCOL);

        if (responseObject.has(DOCS) && responseObject.get(DOCS).isJsonArray()) {
            JsonArray docsArray = responseObject.get(DOCS).getAsJsonArray();
            for (JsonElement documentElement : docsArray) {
                Map<String, Object> document = createDocument(documentElement);
                document.put(STORE_ID, storeId);
                document.put(STORE_PROTOCOL, storeProtocol);
                documents.add(document);
            }
        } else {
            logger.warn("No documents found in response!");
        }

        return new AlfrescoResponse(lastTransactionId, lastAclChangesetId, storeId, storeProtocol, documents);
    }

    private long getStringAsLong(JsonObject responseObject, String key, long defaultValue) {
        String string = getString(responseObject, key);
        if (Strings.isNullOrEmpty(string)) {
            return defaultValue;
        }
        return Long.parseLong(string);
    }

    private String getString(JsonObject responseObject, String key) {
        if (responseObject.has(key)) {
            JsonElement element = responseObject.get(key);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return element.getAsString();
            } else {
                logger.warn("The {} property (={}) is not a string in document: {}", new Object[] { key, element,
                        responseObject });
            }
        } else {
            logger.warn("The key {} is missing from document: {}", key, responseObject);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDocument(JsonElement documentElement) {
        if (documentElement.isJsonObject()) {
            JsonObject documentObject = documentElement.getAsJsonObject();
            return (Map<String, Object>) gson.fromJson(documentObject, Map.class);
        }
        return new HashMap<String, Object>();
    }

    @Override
    public Map<String, Object> fetchMetadata(String nodeUuid) throws AlfrescoDownException {
        String json = fetchMetadataJson(nodeUuid);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(json, Map.class);

        List<Map<String, String>> properties = extractPropertiesFieldFromMap(map, "properties");

        for (Map<String, String> e : properties) {
            map.put(e.get("name"), e.get("value"));
        }
        return map;
    }

    private String fetchMetadataJson(String nodeUuid) {
        String fullUrl = String.format("%s/%s", metadataUrl, nodeUuid);
        logger.debug("url: {}", fullUrl);
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = createGetRequest(fullUrl);
            CloseableHttpResponse response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            return CharStreams.toString(new InputStreamReader(entity.getContent(), "UTF-8"));
        } catch (IOException e) {
            throw new AlfrescoDownException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractPropertiesFieldFromMap(Map<String, Object> map, String propertiesField) {
        Object properties = map.remove(propertiesField);
        if (!(properties instanceof List)) {
            throw new AlfrescoDownException(propertiesField + " is not of type List, it is of type "
                    + properties.getClass());
        }
        return (List<Map<String, String>>) properties;
    }

    private AlfrescoUser userFromHttpEntity(HttpEntity entity) throws IOException {
        Reader entityReader = new InputStreamReader(entity.getContent());
        JsonObject responseObject = gson.fromJson(entityReader, JsonObject.class);
        return getUser(responseObject);
    }

    private AlfrescoUser getUser(JsonObject responseObject) {
        String username = getUsername(responseObject);
        List<String> authorities = getAuthorities(responseObject);
        return new AlfrescoUser(username, authorities);
    }

    private String getUsername(JsonObject userObject) {
        if (!userObject.has(USERNAME)) {
            throw new AlfrescoParseException("Json response is missing username.");
        }
        JsonElement usernameElement = userObject.get(USERNAME);
        if (!usernameElement.isJsonPrimitive() || !usernameElement.getAsJsonPrimitive().isString()) {
            throw new AlfrescoParseException("Username must be a string. It was: " + usernameElement.toString());
        }
        return usernameElement.getAsString();
    }

    private List<String> getAuthorities(JsonObject userObject) {
        List<String> authorities = new ArrayList<String>();
        if (!userObject.has(AUTHORITIES)) {
            throw new AlfrescoParseException("Json response is authorities.");
        }
        JsonElement authoritiesElement = userObject.get(AUTHORITIES);
        if (!authoritiesElement.isJsonArray()) {
            throw new AlfrescoParseException("Authorities must be a json array. It was: "
                    + authoritiesElement.toString());
        }
        JsonArray authoritiesArray = authoritiesElement.getAsJsonArray();
        for (JsonElement authorityElement : authoritiesArray) {
            if (!authorityElement.isJsonPrimitive()) {
                throw new AlfrescoParseException("Authority entry must be a string. It was: "
                        + authoritiesElement.toString());
            }
            JsonPrimitive authorityPrimitive = authorityElement.getAsJsonPrimitive();
            if (!authorityPrimitive.isString()) {
                throw new AlfrescoParseException("Authority entry must be a string. It was: "
                        + authoritiesElement.toString());
            }
            authorities.add(authorityPrimitive.getAsString());
        }
        return authorities;
    }

    @Override
    public AlfrescoUser fetchUserAuthorities(String username) throws AlfrescoDownException {
        HttpResponse response;
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            String url = String.format("%s%s", authoritiesUrl, username);

            if (logger.isDebugEnabled()) {
                logger.debug("Hitting url: " + url);
            }

            HttpGet httpGet = createGetRequest(url);
            response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            AlfrescoUser afResponse = userFromHttpEntity(entity);
            EntityUtils.consume(entity);
            return afResponse;
        } catch (IOException e) {
            if (logger.isDebugEnabled()) {
                logger.warn("Failed to fetch nodes.", e);
            }
            throw new AlfrescoDownException("Alfresco appears to be down", e);
        }
    }

    @Override
    public List<AlfrescoUser> fetchAllUsersAuthorities() throws AlfrescoDownException {
        HttpResponse response;
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();

            if (logger.isDebugEnabled()) {
                logger.debug("Hitting url: " + authoritiesUrl);
            }

            HttpGet httpGet = createGetRequest(authoritiesUrl);
            response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            List<AlfrescoUser> users = usersFromHttpEntity(entity);
            EntityUtils.consume(entity);
            return users;
        } catch (IOException e) {
            if (logger.isDebugEnabled()) {
                logger.warn("Failed to fetch nodes.", e);
            }
            throw new AlfrescoDownException("Alfresco appears to be down", e);
        }
    }

    private List<AlfrescoUser> usersFromHttpEntity(HttpEntity entity) throws IOException {
        Reader entityReader = new InputStreamReader(entity.getContent());
        JsonElement responseObject = gson.fromJson(entityReader, JsonElement.class);
        if (!responseObject.isJsonArray()) {
            throw new AlfrescoParseException("Users must be a json array.");
        }
        List<AlfrescoUser> users = new ArrayList<AlfrescoUser>();
        JsonArray usersArray = responseObject.getAsJsonArray();
        for (JsonElement userElement : usersArray) {
            if (!userElement.isJsonObject()) {
                throw new AlfrescoParseException("User must be a json object.");
            }
            AlfrescoUser user = getUser(userElement.getAsJsonObject());
            users.add(user);
        }
        return users;
    }
}
