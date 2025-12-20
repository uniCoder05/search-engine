package searchengine.dto.responses;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jsoup.nodes.Document;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class HtmlParseResponse {
    private int statusCode;
    private Document document;
    private Set<String> internalLinks;
}
