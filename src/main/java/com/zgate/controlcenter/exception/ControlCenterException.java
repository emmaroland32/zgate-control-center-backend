package com.zgate.controlcenter.exception;

public class ControlCenterException extends RuntimeException {
    public ControlCenterException(String message) { super(message); }
    public ControlCenterException(String message, Throwable cause) { super(message, cause); }
}
