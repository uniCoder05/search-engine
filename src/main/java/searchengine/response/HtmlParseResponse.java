package searchengine.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class HtmlParseResponse {
    private Document document;
    private int status;
}