package net.zyonmc.zyonpay.storage;

/** MariaDB access failure. */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
