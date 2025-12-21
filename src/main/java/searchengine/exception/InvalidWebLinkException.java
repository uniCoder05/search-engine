package searchengine.exception;

import java.io.IOException;

public class InvalidWebLinkException extends IOException {
    public static final String defaultMessage = "Некорректный URL: ";

    public InvalidWebLinkException(String message) {
        super(message);
    }
    public InvalidWebLinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
