package io.testforge.cicdgateway.provider;

public interface CicdCredentialResolver {
    Credential resolve(String reference);
    record Credential(String username, String token) { }
}
