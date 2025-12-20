package searchengine.dto.responses;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jsoup.nodes.Document;
import searchengine.model.Page;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class HtmlParseResponse {
    private  Page page;    
    private Set<String> internalLinks;
}
