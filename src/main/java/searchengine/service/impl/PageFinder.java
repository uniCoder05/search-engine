package searchengine.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigConnection;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.PageRepository;
import searchengine.service.PageIndexerService;
import searchengine.util.UrlValidator;

import javax.net.ssl.SSLHandshakeException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class PageFinder extends RecursiveAction {
    private static final String MASK = "[^#?.]+";

    private final Site site;
    private final PageRepository pageRepository;
    private final PageIndexerService pageIndexerService;
    private final AtomicBoolean indexingProcessing;
    private final ConfigConnection configConnection;
    private final String urlPage;
    private final Set<String> visitedLinks;
    private final String mask;

    public PageFinder(Site site,
                      PageRepository pageRepository,
                      ConfigConnection configConnection,
                      PageIndexerService pageIndexerService,
                      AtomicBoolean indexingProcessing) {
        String url = site.getUrl();
        this.pageRepository = pageRepository;
        this.urlPage = url;
        this.configConnection = configConnection;
        this.indexingProcessing = indexingProcessing;
        this.site = site;
        this.pageIndexerService = pageIndexerService;
        this.mask = url + MASK;
        this.visitedLinks = ConcurrentHashMap.newKeySet();
    }

    private PageFinder(PageFinder pageFinder, String url) {
        this.site = pageFinder.site;
        this.pageRepository = pageFinder.pageRepository;
        this.urlPage = url;
        this.configConnection = pageFinder.configConnection;
        this.indexingProcessing = pageFinder.indexingProcessing;
        this.pageIndexerService = pageFinder.pageIndexerService;
        this.mask = pageFinder.mask;
        this.visitedLinks = pageFinder.visitedLinks;
    }

    @Override
    protected void compute() {
        if(!indexingProcessing.get()) {
            return;
        }
        if(!visitedLinks.add(urlPage)) {
            return;
        }
        Set<PageFinder> subTasks = new HashSet<>();
        crawl(subTasks);
        for (PageFinder task : subTasks) {
            if (!indexingProcessing.get()) {
                return;
            }
            task.join();
        }
    }

    public void crawl(Set<PageFinder> subTasks) {
        pause(100, 300);
        log.info("crawl url {}", urlPage);
        if (!indexingProcessing.get()) {
            return;
        }
        Page indexingPage = new Page();
        indexingPage.setSite(site);
        indexingPage.setPageContent("");
        try {
            String path = UrlValidator.getPath(urlPage);
            indexingPage.setPath(path);
            Connection connection = getConnection(urlPage);
            var response = connection.execute();
            indexingPage.setAnswerCode(response.statusCode());
            Document document = response.parse();
            indexingPage.setPageContent(getContent(document));
            Set<String> urlSet = getInnerLinks(document);

            for (String url : urlSet) {
                if(!indexingProcessing.get()) {
                    return;
                }
                PageFinder task = new PageFinder(this, url);
                task.fork();
                subTasks.add(task);
            }
        } catch (Exception ex) {
            indexingPage.setAnswerCode(getErrorCodeFromException(ex));
            log.debug("ERROR INDEXATION, url:{}, code:{}, error:{}", urlPage, indexingPage.getAnswerCode(), ex.getMessage());
        }

        pageRepository.save(indexingPage);
        if(shouldIndexPage(indexingPage)) {
            log.info("Indexing page url: {}", urlPage);
            pageIndexerService.index(indexingPage);
        }

    }

    @Transactional
    public void refreshPage(String urlPage) {

        Page refreshPage = new Page();
        refreshPage.setSite(site);
        String path = UrlValidator.getPath(urlPage);
        refreshPage.setPath(path);
        refreshPage.setPageContent("");

        try {
            Optional<Page> result = pageRepository.getPageBySiteIdAndPath(site.getId(), path);
            result.ifPresent(page -> refreshPage.setId(page.getId()));
            Connection connection = getConnection(urlPage);
            var response = connection.execute();
            refreshPage.setAnswerCode(response.statusCode());
            Document doc = response.parse();
            refreshPage.setPageContent(getContent(doc));
        } catch (Exception ex) {
            log.info("urlPage exception: {} message: {}", urlPage, ex.getMessage());
            refreshPage.setAnswerCode(getErrorCodeFromException(ex));
            log.debug("ERROR INDEXATION, url:{}, code:{}, error:{}", urlPage, refreshPage.getAnswerCode(), ex.getMessage());
        }
        pageRepository.save(refreshPage);
        if(shouldIndexPage(refreshPage)) {
            pageIndexerService.refreshIndex(refreshPage);
        }
    }

    private Set<String> getInnerLinks(Document document) {
        Elements links = document.select("a[href]");
        return links.stream()
                .map(link -> link.attr("abs:href"))
                .filter(this::isValidLink)
                .collect(Collectors.toSet());
    }

    private boolean shouldIndexPage(Page page) {
        return page.getAnswerCode() == 200
                && !page.getPageContent().isBlank()
                && indexingProcessing.get();
    }

    private Connection getConnection(String url) {

        return Jsoup.connect(url)
                .userAgent(configConnection.getUserAgent())
                .referrer(configConnection.getReferer())
                .timeout(configConnection.getTimeout())
                .maxBodySize(0);
    }

    private String getContent(Document document) {
        return document.html();
    }

    private boolean isValidLink(String link) {
        if (link.isBlank()) {
            return false;
        }
        if (!UrlValidator.isInternalUrl(link, site.getUrl())) {
            return false;
        }
        if (visitedLinks.contains(link)) {
            return false;
        }

        return true;
    }


    private int getErrorCodeFromException(Exception e) {
        if (e instanceof HttpStatusException) {
            return ((HttpStatusException) e).getStatusCode();
        } else if (e instanceof SSLHandshakeException) {
            return 525;
        } else if (e.getMessage().contains("timeout")) {
            return 408;
        } else if (e instanceof MalformedURLException || e instanceof URISyntaxException) {
            return 404;
        } else {
            return -1;
        }
    }

    private void pause(int min, int max)  {
        int duration = min + (int)(Math.random() * (max - min));
        try {
            //log.info(Thread.currentThread().getName() + " sleeping for " + duration + " ms");
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            log.info("InterruptedException while sleep", e);
            indexingProcessing.set(false);
        }
    }
}