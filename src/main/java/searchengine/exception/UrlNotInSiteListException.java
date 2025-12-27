package searchengine.exception;

public class UrlNotInSiteListException extends RuntimeException {
    public UrlNotInSiteListException() {
        super("Данный адрес находится за пределами сайтов указанных в конфигурационном файле");
    }

    public UrlNotInSiteListException(String message) {
        super(message);
    }
}
