package io.vertesia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vertesia.api.AccessControlEntriesApi;
import io.vertesia.api.AccountsApi;
import io.vertesia.api.AgentRunsApi;
import io.vertesia.api.ApiKeysApi;
import io.vertesia.api.AppsApi;
import io.vertesia.api.AuditTrailApi;
import io.vertesia.api.BulkOperationsApi;
import io.vertesia.api.CollectionsApi;
import io.vertesia.api.CommandsApi;
import io.vertesia.api.ContentObjectTypesApi;
import io.vertesia.api.CostsApi;
import io.vertesia.api.DataApi;
import io.vertesia.api.EnvironmentsApi;
import io.vertesia.api.FilesApi;
import io.vertesia.api.InteractionRunsApi;
import io.vertesia.api.InteractionsApi;
import io.vertesia.api.OAuthClientsApi;
import io.vertesia.api.OAuthGrantsApi;
import io.vertesia.api.OAuthProvidersApi;
import io.vertesia.api.ObjectsApi;
import io.vertesia.api.ProcessesApi;
import io.vertesia.api.ProjectsApi;
import io.vertesia.api.PromptTemplatesApi;
import io.vertesia.api.RemoteMcpConnectionsApi;
import io.vertesia.api.RenderingApi;
import io.vertesia.api.RolesApi;
import io.vertesia.api.SecretsApi;
import io.vertesia.api.TasksApi;
import io.vertesia.api.TokenServiceApi;
import io.vertesia.api.UserGroupsApi;
import io.vertesia.api.UsersApi;
import io.vertesia.api.WorkflowDefinitionsApi;
import io.vertesia.api.WorkflowRulesApi;
import io.vertesia.api.WorkflowRunsApi;
import io.vertesia.auth.Authentication;
import io.vertesia.model.IssueTokenResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * High-level Vertesia SDK facade over the generated OpenAPI client.
 */
public class VertesiaClient {
    public static final String DEFAULT_SITE = "api.vertesia.io";
    public static final String DEFAULT_TOKEN_URL = "https://sts.vertesia.io";
    public static final String DEFAULT_API_VERSION = "20260803";
    private static final long TOKEN_REFRESH_WINDOW_SECONDS = 60L;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String studioUrl;
    private final String storeUrl;
    private final String tokenServerUrl;
    private final String apiVersion;

    public final ApiClient studioClient;
    public final ApiClient storeClient;
    public final ApiClient tokenClient;
    public final GeneratedApiGroup studio;
    public final GeneratedApiGroup store;

    public final AccessControlEntriesApi accessControlEntries;
    public final AccountsApi accounts;
    public final ApiKeysApi apiKeys;
    public final AppsApi apps;
    public final AuditTrailApi auditTrail;
    public final EnvironmentsApi environments;
    public final InteractionRunsApi interactionRuns;
    public final InteractionsApi interactions;
    public final OAuthClientsApi oauthClients;
    public final OAuthGrantsApi oauthGrants;
    public final OAuthProvidersApi oauthProviders;
    public final ProjectsApi projects;
    public final PromptTemplatesApi promptTemplates;
    public final RemoteMcpConnectionsApi remoteMcpConnections;
    public final RolesApi roles;
    public final SecretsApi secrets;
    public final UserGroupsApi userGroups;
    public final UsersApi users;

    public final AgentRunsApi agentRuns;
    public final BulkOperationsApi bulkOperations;
    public final CollectionsApi collections;
    public final CommandsApi commands;
    public final ContentObjectTypesApi contentObjectTypes;
    public final CostsApi costs;
    public final DataApi data;
    public final FilesApi files;
    public final ObjectsApi objects;
    public final ProcessesApi processes;
    public final RenderingApi rendering;
    public final TasksApi tasks;
    public final WorkflowDefinitionsApi workflowDefinitions;
    public final WorkflowRulesApi workflowRules;
    public final WorkflowRunsApi workflowRuns;
    public final TokenServiceApi tokenService;

    public VertesiaClient(ClientOptions options) {
        ClientOptions effectiveOptions = options == null ? new ClientOptions() : options;
        ResolvedEndpoints endpoints = resolveEndpoints(effectiveOptions);
        this.apiVersion = nonEmpty(effectiveOptions.getApiVersion(), DEFAULT_API_VERSION);
        OkHttpClient httpClient =
                effectiveOptions.getHttpClient() == null
                        ? new OkHttpClient.Builder()
                                .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout(180, TimeUnit.SECONDS)
                                .writeTimeout(180, TimeUnit.SECONDS)
                                .build()
                        : effectiveOptions.getHttpClient();
        TokenSource tokenSource =
                newTokenSource(effectiveOptions, endpoints, apiVersion, httpClient);

        this.studioUrl = endpoints.studioUrl;
        this.storeUrl = endpoints.storeUrl;
        this.tokenServerUrl = endpoints.tokenServerUrl;

        this.studioClient = newApiClient(endpoints.studioUrl, tokenSource, apiVersion, httpClient);
        this.storeClient = newApiClient(endpoints.storeUrl, tokenSource, apiVersion, httpClient);
        this.tokenClient =
                newApiClient(endpoints.tokenServerUrl, tokenSource, apiVersion, httpClient);
        this.studio = new GeneratedApiGroup(studioClient);
        this.store = new GeneratedApiGroup(storeClient);
        this.tokenService = new TokenServiceApi(tokenClient);

        this.accessControlEntries = studio.accessControlEntries;
        this.accounts = studio.accounts;
        this.apiKeys = studio.apiKeys;
        this.apps = studio.apps;
        this.auditTrail = studio.auditTrail;
        this.environments = studio.environments;
        this.interactionRuns = studio.interactionRuns;
        this.interactions = studio.interactions;
        this.oauthClients = studio.oauthClients;
        this.oauthGrants = studio.oauthGrants;
        this.oauthProviders = studio.oauthProviders;
        this.projects = studio.projects;
        this.promptTemplates = studio.promptTemplates;
        this.remoteMcpConnections = studio.remoteMcpConnections;
        this.roles = studio.roles;
        this.secrets = studio.secrets;
        this.userGroups = studio.userGroups;
        this.users = studio.users;

        this.agentRuns = store.agentRuns;
        this.bulkOperations = store.bulkOperations;
        this.collections = store.collections;
        this.commands = store.commands;
        this.contentObjectTypes = store.contentObjectTypes;
        this.costs = store.costs;
        this.data = store.data;
        this.files = store.files;
        this.objects = store.objects;
        this.processes = store.processes;
        this.rendering = store.rendering;
        this.tasks = store.tasks;
        this.workflowDefinitions = store.workflowDefinitions;
        this.workflowRules = store.workflowRules;
        this.workflowRuns = store.workflowRuns;
    }

    public static VertesiaClient create(ClientOptions options) {
        return new VertesiaClient(options);
    }

    public String getStudioUrl() {
        return studioUrl;
    }

    public String getStoreUrl() {
        return storeUrl;
    }

    public String getTokenServerUrl() {
        return tokenServerUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public static class GeneratedApiGroup {
        public final AccessControlEntriesApi accessControlEntries;
        public final AccountsApi accounts;
        public final AgentRunsApi agentRuns;
        public final ApiKeysApi apiKeys;
        public final AppsApi apps;
        public final AuditTrailApi auditTrail;
        public final BulkOperationsApi bulkOperations;
        public final CollectionsApi collections;
        public final CommandsApi commands;
        public final ContentObjectTypesApi contentObjectTypes;
        public final CostsApi costs;
        public final DataApi data;
        public final EnvironmentsApi environments;
        public final FilesApi files;
        public final InteractionRunsApi interactionRuns;
        public final InteractionsApi interactions;
        public final OAuthClientsApi oauthClients;
        public final OAuthGrantsApi oauthGrants;
        public final OAuthProvidersApi oauthProviders;
        public final ObjectsApi objects;
        public final ProcessesApi processes;
        public final ProjectsApi projects;
        public final PromptTemplatesApi promptTemplates;
        public final RemoteMcpConnectionsApi remoteMcpConnections;
        public final RenderingApi rendering;
        public final RolesApi roles;
        public final SecretsApi secrets;
        public final TasksApi tasks;
        public final TokenServiceApi tokenService;
        public final UserGroupsApi userGroups;
        public final UsersApi users;
        public final WorkflowDefinitionsApi workflowDefinitions;
        public final WorkflowRulesApi workflowRules;
        public final WorkflowRunsApi workflowRuns;

        private GeneratedApiGroup(ApiClient client) {
            this.accessControlEntries = new AccessControlEntriesApi(client);
            this.accounts = new AccountsApi(client);
            this.agentRuns = new AgentRunsApi(client);
            this.apiKeys = new ApiKeysApi(client);
            this.apps = new AppsApi(client);
            this.auditTrail = new AuditTrailApi(client);
            this.bulkOperations = new BulkOperationsApi(client);
            this.collections = new CollectionsApi(client);
            this.commands = new CommandsApi(client);
            this.contentObjectTypes = new ContentObjectTypesApi(client);
            this.costs = new CostsApi(client);
            this.data = new DataApi(client);
            this.environments = new EnvironmentsApi(client);
            this.files = new FilesApi(client);
            this.interactionRuns = new InteractionRunsApi(client);
            this.interactions = new InteractionsApi(client);
            this.oauthClients = new OAuthClientsApi(client);
            this.oauthGrants = new OAuthGrantsApi(client);
            this.oauthProviders = new OAuthProvidersApi(client);
            this.objects = new ObjectsApi(client);
            this.processes = new ProcessesApi(client);
            this.projects = new ProjectsApi(client);
            this.promptTemplates = new PromptTemplatesApi(client);
            this.remoteMcpConnections = new RemoteMcpConnectionsApi(client);
            this.rendering = new RenderingApi(client);
            this.roles = new RolesApi(client);
            this.secrets = new SecretsApi(client);
            this.tasks = new TasksApi(client);
            this.tokenService = new TokenServiceApi(client);
            this.userGroups = new UserGroupsApi(client);
            this.users = new UsersApi(client);
            this.workflowDefinitions = new WorkflowDefinitionsApi(client);
            this.workflowRules = new WorkflowRulesApi(client);
            this.workflowRuns = new WorkflowRunsApi(client);
        }
    }

    private static ApiClient newApiClient(
            String baseUrl, TokenSource tokenSource, String apiVersion, OkHttpClient httpClient) {
        ApiClient client =
                new ApiClient(httpClient) {
                    {
                        Map<String, Authentication> mutable =
                                new HashMap<String, Authentication>(this.authentications);
                        mutable.put(
                                "OpenID",
                                (queryParams,
                                        headerParams,
                                        cookieParams,
                                        payload,
                                        method,
                                        uri) -> {});
                        this.authentications = Collections.unmodifiableMap(mutable);
                    }
                };
        client.setBasePath(baseUrl);
        client.setBearerToken(tokenSource);
        client.addDefaultHeader("x-api-version", apiVersion);
        return client;
    }

    private static TokenSource newTokenSource(
            ClientOptions options,
            ResolvedEndpoints endpoints,
            String apiVersion,
            OkHttpClient httpClient) {
        String apiKey = trim(options.getApiKey());
        String token = trim(options.getToken());
        if (!apiKey.isEmpty() && !token.isEmpty()) {
            throw new VertesiaClientException("set either apiKey or token, not both");
        }
        if (!token.isEmpty()) {
            return new StaticTokenSource(token);
        }
        if (!apiKey.isEmpty()) {
            if (!apiKey.startsWith("sk-")) {
                throw new VertesiaClientException("apiKey must be an sk- secret key");
            }
            if (!endpoints.tokenServerUrlExplicit && !endpoints.tokenServerUrlSafelyDerived) {
                throw new VertesiaClientException(
                        "tokenServerUrl is required when using apiKey with custom endpoints");
            }
            return new ApiKeyTokenSource(apiKey, endpoints.tokenServerUrl, apiVersion, httpClient);
        }
        return new StaticTokenSource("");
    }

    private static class StaticTokenSource implements TokenSource {
        private final String token;

        private StaticTokenSource(String token) {
            this.token = token;
        }

        @Override
        public String get() {
            return token;
        }
    }

    private static class ApiKeyTokenSource implements TokenSource {
        private final String apiKey;
        private final String tokenServerUrl;
        private final String apiVersion;
        private final OkHttpClient httpClient;
        private String token = "";
        private long expiresAtEpochSeconds = 0L;

        private ApiKeyTokenSource(
                String apiKey, String tokenServerUrl, String apiVersion, OkHttpClient httpClient) {
            this.apiKey = apiKey;
            this.tokenServerUrl = tokenServerUrl;
            this.apiVersion = apiVersion;
            this.httpClient = httpClient;
        }

        @Override
        public synchronized String get() {
            long now = System.currentTimeMillis() / 1000L;
            if (!token.isEmpty() && now < expiresAtEpochSeconds - TOKEN_REFRESH_WINDOW_SECONDS) {
                return token;
            }
            IssueTokenResponse issued = issueToken();
            String issuedToken = trim(issued.getToken());
            if (issuedToken.isEmpty()) {
                throw new VertesiaClientException("Vertesia STS returned an empty token");
            }
            token = issuedToken;
            expiresAtEpochSeconds =
                    tokenExpiry(
                            issuedToken,
                            now,
                            issued.getExpiresIn() == null
                                    ? null
                                    : issued.getExpiresIn().longValue());
            return token;
        }

        private IssueTokenResponse issueToken() {
            String body = "{\"type\":\"apikey\",\"key\":\"" + escapeJson(apiKey) + "\"}";
            Request request =
                    new Request.Builder()
                            .url(joinUrlPath(tokenServerUrl, "/token/issue"))
                            .post(
                                    RequestBody.create(
                                            body.getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header("x-api-version", apiVersion)
                            .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new VertesiaClientException(
                            "Vertesia STS token exchange failed: HTTP "
                                    + response.code()
                                    + ": "
                                    + responseBody);
                }
                IssueTokenResponse tokenResponse =
                        JSON.deserialize(responseBody, IssueTokenResponse.class);
                if (tokenResponse == null) {
                    throw new VertesiaClientException(
                            "Vertesia STS returned an invalid token payload");
                }
                return tokenResponse;
            } catch (IOException e) {
                throw new VertesiaClientException(
                        "Vertesia STS token exchange failed: " + e.getMessage(), e);
            }
        }
    }

    private static ResolvedEndpoints resolveEndpoints(ClientOptions options) {
        String site = trim(options.getSite());
        String region = trim(options.getRegion());
        String serverUrl = trim(options.getServerUrl());
        String storeUrl = trim(options.getStoreUrl());

        if (!site.isEmpty() && !region.isEmpty()) {
            throw new VertesiaClientException("set either site or region, not both");
        }
        if (!region.isEmpty()) {
            site = siteFromRegion(region, options.isPreview());
        } else if (site.isEmpty() && options.isPreview()) {
            site = previewSite(DEFAULT_SITE);
        } else if (site.isEmpty() && serverUrl.isEmpty() && storeUrl.isEmpty()) {
            site = DEFAULT_SITE;
        }

        if (serverUrl.isEmpty()) {
            if (site.isEmpty()) {
                throw new VertesiaClientException("site or serverUrl is required");
            }
            serverUrl = siteToHttpsUrl(site);
        }
        if (storeUrl.isEmpty()) {
            if (site.isEmpty()) {
                throw new VertesiaClientException("site or storeUrl is required");
            }
            storeUrl = siteToHttpsUrl(site);
        }

        String tokenUrl = trim(options.getTokenServerUrl());
        boolean tokenUrlExplicit = !tokenUrl.isEmpty();
        boolean tokenUrlSafelyDerived = false;
        if (tokenUrl.isEmpty()) {
            TokenUrlResult derived = deriveTokenServerUrl(site, serverUrl, storeUrl);
            tokenUrl = derived.url;
            tokenUrlSafelyDerived = derived.safelyDerived;
        }

        return new ResolvedEndpoints(
                normalizeApiUrl(serverUrl),
                normalizeApiUrl(storeUrl),
                normalizeServerUrl(tokenUrl),
                tokenUrlExplicit,
                tokenUrlSafelyDerived);
    }

    private static String siteToHttpsUrl(String site) {
        if (site.contains("://")) {
            return site;
        }
        if (site.contains("/")) {
            throw new VertesiaClientException("site must be a host, not a URL path");
        }
        return "https://" + site;
    }

    private static String siteFromRegion(String region, boolean preview) {
        String value = region.trim().toLowerCase();
        if (value.isEmpty() || value.startsWith("-") || value.endsWith("-")) {
            throw new VertesiaClientException("region must be a region id such as us1 or eu1");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLowerCase(c) && !Character.isDigit(c) && c != '-') {
                throw new VertesiaClientException("region must be a region id such as us1 or eu1");
            }
        }
        String site = "api." + value + ".vertesia.io";
        return preview ? previewSite(site) : site;
    }

    private static String previewSite(String site) {
        if (site.startsWith("api-preview.")) {
            return site;
        }
        if (site.startsWith("api.")) {
            return "api-preview." + site.substring("api.".length());
        }
        return site;
    }

    private static String normalizeApiUrl(String raw) {
        URI uri = URI.create(normalizeServerUrl(raw));
        String path = uri.getPath() == null ? "" : trimTrailingSlash(uri.getPath());
        if (path.isEmpty()) {
            path = "/api/v1";
        } else if (!path.endsWith("/api/v1")) {
            path = path + "/api/v1";
        }
        return replacePath(uri, path);
    }

    private static String normalizeServerUrl(String raw) {
        String value = trimTrailingSlash(trim(raw));
        URI uri = URI.create(value);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new VertesiaClientException("URL must include scheme and host");
        }
        return replacePath(uri, trimTrailingSlash(uri.getPath() == null ? "" : uri.getPath()));
    }

    private static TokenUrlResult deriveTokenServerUrl(
            String site, String serverUrl, String storeUrl) {
        String candidate = trim(site);
        if (!candidate.isEmpty()) {
            if (!candidate.contains("://")) {
                candidate = "https://" + candidate;
            }
            String tokenUrl = tokenUrlFromApiHost(candidate);
            if (!tokenUrl.isEmpty()) {
                return new TokenUrlResult(tokenUrl, true);
            }
        }
        String tokenUrl = tokenUrlFromApiHost(serverUrl);
        if (!tokenUrl.isEmpty()) {
            return new TokenUrlResult(tokenUrl, true);
        }
        tokenUrl = tokenUrlFromApiHost(storeUrl);
        if (!tokenUrl.isEmpty()) {
            return new TokenUrlResult(tokenUrl, true);
        }
        return new TokenUrlResult(DEFAULT_TOKEN_URL, false);
    }

    private static String tokenUrlFromApiHost(String raw) {
        URI uri = URI.create(raw);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!host.startsWith("api")) {
            return "";
        }
        String stsHost =
                host.startsWith("api-preview.")
                        ? "api." + host.substring("api-preview.".length())
                        : host;
        if (stsHost.startsWith("api")) {
            stsHost = "sts" + stsHost.substring("api".length());
        }
        return "https://" + stsHost;
    }

    private static String joinUrlPath(String baseUrl, String path) {
        URI uri = URI.create(baseUrl);
        String basePath = trimTrailingSlash(uri.getPath() == null ? "" : uri.getPath());
        String suffix = path.startsWith("/") ? path : "/" + path;
        return replacePath(uri, basePath + suffix);
    }

    private static long tokenExpiry(String token, long now, Long expiresIn) {
        Long jwtExpiry = jwtExpiry(token);
        if (jwtExpiry != null) {
            return jwtExpiry;
        }
        long ttl = expiresIn == null || expiresIn <= 0 ? 3600L : expiresIn;
        return now + ttl;
    }

    private static Long jwtExpiry(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonObject claims =
                    JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            JsonElement exp = claims.get("exp");
            return exp == null ? null : exp.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String padBase64(String value) {
        int missing = (4 - value.length() % 4) % 4;
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < missing; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static String replacePath(URI uri, String path) {
        try {
            return new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            uri.getHost(),
                            uri.getPort(),
                            path,
                            uri.getQuery(),
                            uri.getFragment())
                    .toString();
        } catch (Exception e) {
            throw new VertesiaClientException("invalid URL: " + uri, e);
        }
    }

    private static String nonEmpty(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class ResolvedEndpoints {
        private final String studioUrl;
        private final String storeUrl;
        private final String tokenServerUrl;
        private final boolean tokenServerUrlExplicit;
        private final boolean tokenServerUrlSafelyDerived;

        private ResolvedEndpoints(
                String studioUrl,
                String storeUrl,
                String tokenServerUrl,
                boolean tokenServerUrlExplicit,
                boolean tokenServerUrlSafelyDerived) {
            this.studioUrl = studioUrl;
            this.storeUrl = storeUrl;
            this.tokenServerUrl = tokenServerUrl;
            this.tokenServerUrlExplicit = tokenServerUrlExplicit;
            this.tokenServerUrlSafelyDerived = tokenServerUrlSafelyDerived;
        }
    }

    private static class TokenUrlResult {
        private final String url;
        private final boolean safelyDerived;

        private TokenUrlResult(String url, boolean safelyDerived) {
            this.url = url;
            this.safelyDerived = safelyDerived;
        }
    }
}
