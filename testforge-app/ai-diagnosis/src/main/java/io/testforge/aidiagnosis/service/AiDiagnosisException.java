package io.testforge.aidiagnosis.service;

public class AiDiagnosisException extends RuntimeException {
    private final String code;
    private final int status;

    public AiDiagnosisException(String code, int status, String message) {
        super(message); this.code = code; this.status = status;
    }

    public AiDiagnosisException(String code, int status, String message, Throwable cause) {
        super(message, cause); this.code = code; this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
