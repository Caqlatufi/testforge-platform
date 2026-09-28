package io.testforge.cicdgateway.catalog.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "testforge.cicd.jenkins")
public class JenkinsConnectionProperties {
    private String serverUrl = "http://127.0.0.1:8082";
    private String folder = "";
    private String credentialRef = "";

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }

    public boolean isConfigured() { return serverUrl != null && !serverUrl.isBlank(); }
}
