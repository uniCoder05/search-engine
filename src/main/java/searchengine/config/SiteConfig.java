package searchengine.config;

import lombok.Getter;
import lombok.Setter;

import java.net.URL;

@Setter
@Getter
public class SiteConfig {
    private URL url;
    private String name;
}
