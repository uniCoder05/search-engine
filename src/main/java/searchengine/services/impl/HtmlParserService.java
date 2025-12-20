package searchengine.services.impl;

import lombok.Setter;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.config.ConfigConnection;
import searchengine.dto.responses.HtmlParseResponse;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;


@Setter
public class HtmlParserService {
    private static final String MASK = "[^?#.]+";

    private String url;
    private final String rootUrl;
    private final ConfigConnection configConnection;
    private final String mask;

    public HtmlParserService(String url, String rootUrl, ConfigConnection configConnection) {
        this.url = url;
        this.configConnection = configConnection;
        this.rootUrl = rootUrl;
        this.mask = rootUrl + MASK;
    }

    public HtmlParseResponse parse() {
        Page page = new Page();
        Site site = new Site();
        HtmlParseResponse parseResponse = new HtmlParseResponse();
        try {
            URI uri = new URI(url);
            page.setPath(uri.getPath());
            Connection connection = getConnection();
            var response = connection.execute();
            page.setAnswerCode(response.statusCode());
            var document = response.parse();
            page.setPageContent(document.html());
            parseResponse.setInternalLinks(getInternalLinks(document));
        } catch (HttpStatusException e) {
            page.setAnswerCode(e.getStatusCode());
        } catch (URISyntaxException | IOException e) {
            page.setAnswerCode(404);
        }
        parseResponse.setPage(page);

        return parseResponse;
    }

    private Connection getConnection() {
        return Jsoup.connect(url)
                .userAgent(configConnection.getUserAgent())
                .referrer(configConnection.getReferer())
                .timeout(configConnection.getTimeout())
                .maxBodySize(0);
    }

    private Set<String> getInternalLinks(Document document) {
        if(document == null) {
            return new HashSet<>();
        }
        return document.select("a[href]").stream()
                .map(a -> a.attr("abs:href"))
                .filter(this::isValidLink)
                .collect(Collectors.toSet());
    }

    private boolean isValidLink(String link) {
        if (link.isBlank()) {
            return false;
        }
        if (!link.matches(mask)) {
            return false;
        }

        return true;
    }
}
