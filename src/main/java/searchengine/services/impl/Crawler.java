package searchengine.services.impl;

import searchengine.dto.responses.HtmlParseResponse;
import searchengine.model.Page;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;

public class Crawler extends RecursiveAction {

    private final HtmlParserService htmlParserService;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final Set<String> visitedLinks;


    public Crawler(HtmlParserService htmlParserService,
                   PageRepository pageRepository, SiteRepository siteRepository) {
        this.htmlParserService = htmlParserService;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.visitedLinks = ConcurrentHashMap.newKeySet();
    }

    @Override
    protected void compute() {
        Set<Crawler> subTasks = new HashSet<>();
        crawl(subTasks);
        for(Crawler task : subTasks) {
            task.join();
        }
    }

    public void crawl(Set<Crawler> subTasks) {
        try {
            HtmlParseResponse response = htmlParserService.parse();
            if(response.get)
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
