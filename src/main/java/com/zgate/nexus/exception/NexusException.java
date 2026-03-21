package com.zgate.nexus.exception;

public class NexusException extends RuntimeException {
    public NexusException(String message) { super(message); }
    public NexusException(String message, Throwable cause) { super(message, cause); }
}
