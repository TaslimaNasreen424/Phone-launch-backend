package com.bell.phone.launch.exception;

public class InventoryLockedException extends RuntimeException {
    public InventoryLockedException(String message) {
        super(message);
    }

    public InventoryLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
