package searchengine.util;


import searchengine.exception.InvalidWebLinkException;

import java.net.URI;
import java.net.URL;

public class UrlValidator {
    private static final String MASK = "[^?.#]+";

    private static boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String scheme;
        String host;
        try {
            URI uriObj = new URI(url);
            URL urlObj = uriObj.toURL();
            host = urlObj.getHost();
            scheme = uriObj.getScheme();
        } catch (Exception e) {
            return false;
        }
        return scheme != null && host != null;
    }

    private static void validate(String url) {
        if (!isValidUrl(url)) {
            throw new RuntimeException("Некорректный URL: " + url);
        }
    }


    public static URI getUri(String url) {
        validate(url);
        return URI.create(url);
    }

    public static String getPath(String url) {
        URI uri = getUri(url);

        return uri.getPath();
    }

    public static String getSiteUrl(String url) {
        URI uri = getUri(url);

        return uri.getScheme() + "://" + uri.getHost() + "/";
    }

    public static boolean isInternalUrl(String url, String siteUrl) {
        String mask = siteUrl + MASK;

        return url.matches(mask);
    }


}
