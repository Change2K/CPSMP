package de.deinserver.cpsmp.claims;

public final class ClaimStorageException extends Exception {

    public ClaimStorageException(String message) {
        super(message);
    }

    public ClaimStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
