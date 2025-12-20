package searchengine.dto.index;

import lombok.*;

@Setter
@Getter
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class SiteDto {
    private String url;
    private String name;
}