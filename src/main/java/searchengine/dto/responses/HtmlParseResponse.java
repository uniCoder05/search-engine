package searchengine.dto.responses;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import searchengine.model.Page;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class HtmlParseResponse {
    private  Page page;    
    private Set<String> internalLinks;
}
