package de.deinserver.cpsmp.teleport;

public class HomeStorageException extends Exception {

    public HomeStorageException(String message) {
        super(message);
    }

    public HomeStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
