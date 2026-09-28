package io.testforge.casecatalog.testcase.asset.service;

public record CaseAssetContent(String fileName, String contentType, String sha256, byte[] bytes) {
}
