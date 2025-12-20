package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import searchengine.config.ConfigConnection;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.PageIndexerService;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
        if (!indexingProcessing.get()) {
            return;
        }
        Page indexingPage = new Page();
        indexingPage.setSite(site);
        try {
            String path = new URI(urlPage).getPath();
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

    public void refreshPage(String urlPage) {
        Page refreshPage = pageRepository.findPageBySiteIdAndPath(site.getId(), urlPage);

        try {
            Connection connection = getConnection(urlPage);
            var response = connection.execute();
            refreshPage.setAnswerCode(response.statusCode());
            Document doc = response.parse();
            refreshPage.setPageContent(getContent(doc));
        } catch (Exception ex) {
            refreshPage.setAnswerCode(getErrorCodeFromException(ex));
            log.debug("ERROR INDEXATION, url:{}, code:{}, error:{}", urlPage, refreshPage.getAnswerCode(), ex.getMessage());
        }

        pageRepository.save(refreshPage);
        pageIndexerService.refreshIndex(refreshPage);
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

    //Метод для проверки ссылки на валидность
    private boolean isValidLink(String link) {
        //Проверка на пустоту
        if (link.isBlank()) {
            return false;
        }
        //Проверка на соответствие маске url + любые символы, кроме ?.#
        if (!link.matches(mask)) {
//            log.info("link {} is invalid for mask {}", link, mask);
            return false;
        }
        //Проверка, ссылка на уникальность
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
        } else if (e instanceof URISyntaxException) {
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