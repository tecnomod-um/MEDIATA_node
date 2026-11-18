package org.taniwha.privacy.pooling;

public final class PrivatePoolingUnavailableException extends RuntimeException {
    private final String code;

    public PrivatePoolingUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
