package searchengine.exception;

public class InvalidUriRuntimeException extends RuntimeException {
    public InvalidUriRuntimeException(String message) {
        super(message);
    }
    public InvalidUriRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
