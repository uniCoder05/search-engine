package searchengine.dto.index;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import searchengine.model.SitePage;
import searchengine.repository.PageRepository;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PageDto {
    private String url;
    private String rootUrl;
    private SitePage site;
}
