package com.guidewire.fnol.common;

public class ProcessingTimeoutException extends RuntimeException {
    public ProcessingTimeoutException(String message) {
        super(message);
    }
    
    public ProcessingTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
