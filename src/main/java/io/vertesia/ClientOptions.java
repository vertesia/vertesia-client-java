package io.vertesia;

import okhttp3.OkHttpClient;

/**
 * Options for the high-level Vertesia SDK client.
 */
public class ClientOptions {
    private String region;
    private boolean preview;
    private String site;
    private String serverUrl;
    private String storeUrl;
    private String tokenServerUrl;
    private String apiKey;
    private String token;
    private String apiVersion;
    private OkHttpClient httpClient;

    public String getRegion() {
        return region;
    }

    public ClientOptions setRegion(String region) {
        this.region = region;
        return this;
    }

    public boolean isPreview() {
        return preview;
    }

    public ClientOptions setPreview(boolean preview) {
        this.preview = preview;
        return this;
    }

    public String getSite() {
        return site;
    }

    public ClientOptions setSite(String site) {
        this.site = site;
        return this;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public ClientOptions setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
        return this;
    }

    public String getStoreUrl() {
        return storeUrl;
    }

    public ClientOptions setStoreUrl(String storeUrl) {
        this.storeUrl = storeUrl;
        return this;
    }

    public String getTokenServerUrl() {
        return tokenServerUrl;
    }

    public ClientOptions setTokenServerUrl(String tokenServerUrl) {
        this.tokenServerUrl = tokenServerUrl;
        return this;
    }

    public String getApiKey() {
        return apiKey;
    }

    public ClientOptions setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public String getToken() {
        return token;
    }

    public ClientOptions setToken(String token) {
        this.token = token;
        return this;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public ClientOptions setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
        return this;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public ClientOptions setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }
}
