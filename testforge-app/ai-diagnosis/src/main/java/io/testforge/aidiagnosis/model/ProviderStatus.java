package io.testforge.aidiagnosis.model;

public record ProviderStatus(boolean available, String status, String message,
                             String provider, String model, String reasoningEffort) { }
