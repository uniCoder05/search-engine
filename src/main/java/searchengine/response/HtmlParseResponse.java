package searchengine.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import searchengine.model.Page;

import java.util.HashSet;
import java.util.Set;


@NoArgsConstructor
@Getter
@Setter
public class HtmlParseResponse {
    private Page page;
    private Set<String> internalLinks = new HashSet<>();
}