package searchengine.services.impl;

import searchengine.dto.responses.HtmlParseResponse;
import searchengine.model.Page;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.PageIndexerService;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

public class Crawler extends RecursiveAction {

    private final HtmlParserService htmlParserService;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final PageIndexerService pageIndexerService;
    private final AtomicBoolean indexingProcessing;
    private final Set<String> visitedLinks;


    public Crawler(HtmlParserService htmlParserService,
                   PageRepository pageRepository, SiteRepository siteRepository,
                   PageIndexerService pageIndexerService,
                    AtomicBoolean indexingProcessing) {
        this.htmlParserService = htmlParserService;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.pageIndexerService = pageIndexerService;
        this.indexingProcessing = indexingProcessing;
        this.visitedLinks = ConcurrentHashMap.newKeySet();
    }
    
    private Crawler(Crawler crawler, String link) {
        this.pageRepository = crawler.pageRepository;
        this.siteRepository = crawler.siteRepository;
        this.pageIndexerService = crawler.pageIndexerService;
        this.indexingProcessing = crawler.indexingProcessing;
        this.visitedLinks = crawler.visitedLinks;
        HtmlParserService htmlParserService = crawler.htmlParserService;
        htmlParserService.setUrl(link);
        this.htmlParserService = htmlParserService;
    }

    @Override
    protected void compute() {
        if(!indexingProcessing.get()) {
            return;
        }
        Set<Crawler> subTasks = new HashSet<>();
        crawl(subTasks);
        for(Crawler task : subTasks) {
            task.join();
        }
    }

    public void crawl(Set<Crawler> subTasks) {
        HtmlParseResponse response = htmlParserService.parse();
        for(String link : response.getInternalLinks()) {
            if(visitedLinks.contains(link)) {
               continue;
            }
            Crawler task = new Crawler(this, link);
            task.fork();
            subTasks.add(task);
        }
        Page page = response.getPage();

        if(page.getAnswerCode() == 200) {
            pageRepository.save(page);
            pageIndexerService.index(page);
        }
    }


}
