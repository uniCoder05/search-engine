package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import searchengine.config.ConfigConnection;
import searchengine.model.Page;
import searchengine.model.SitePage;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class PageFinder extends RecursiveAction {
    private final PageIndexerService pageIndexerService;
    private final LemmaService lemmaService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final AtomicBoolean indexingProcessing;
    private final ConfigConnection configConnection;
    private final Set<String> urlSet = new HashSet<>();
    private final String pagePath;
    private final SitePage siteDomain;
    private final ConcurrentHashMap<String, Page> resultForkJoinPoolIndexedPages;

    public PageFinder(SiteRepository siteRepository, PageRepository pageRepository,
                      SitePage siteDomain, String pagePath,
                      ConcurrentHashMap<String, Page> resultForkJoinPoolIndexedPages,
                      ConfigConnection configConnection, LemmaService lemmaService,
                      PageIndexerService pageIndexerService, AtomicBoolean indexingProcessing) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.pagePath = pagePath;
        this.resultForkJoinPoolIndexedPages = resultForkJoinPoolIndexedPages;
        this.configConnection = configConnection;
        this.indexingProcessing = indexingProcessing;
        this.siteDomain = siteDomain;
        this.lemmaService = lemmaService;
        this.pageIndexerService = pageIndexerService;
    }

    @Override
    protected void compute() {
        if (resultForkJoinPoolIndexedPages.get(pagePath) != null || !indexingProcessing.get()) {
            return;
        }
        Page indexingPage = new Page();
        indexingPage.setPath(pagePath);
        indexingPage.setSite(siteDomain);

        try {
            Connection connect = getConnection(siteDomain.getUrl() + pagePath);
            Document doc = connect.timeout(60000).get();

            indexingPage.setPageContent(getContent(doc));
            if (indexingPage.getPageContent() == null || indexingPage.getPageContent().isEmpty() || indexingPage.getPageContent().isBlank()) {
                throw new Exception("Content of site id:" + indexingPage.getSite().getId() + ", page:" + indexingPage.getPath() + " is null or empty");
            }
            Elements pages = doc.getElementsByTag("a");
            for (org.jsoup.nodes.Element element : pages)
                if (!element.attr("href").isEmpty() && element.attr("href").charAt(0) == '/') {
                    if (resultForkJoinPoolIndexedPages.get(pagePath) != null || !indexingProcessing.get()) {
                        return;
                    } else if (resultForkJoinPoolIndexedPages.get(element.attr("href")) == null) {
                        urlSet.add(element.attr("href"));
                    }
                }
            indexingPage.setAnswerCode(doc.connection().response().statusCode());
        } catch (Exception ex) {
            errorHandling(ex, indexingPage);resultForkJoinPoolIndexedPages.putIfAbsent(indexingPage.getPath(), indexingPage);
            SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
            sitePage.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(sitePage);
            pageRepository.save(indexingPage);
            log.debug("ERROR INDEXATION, siteId:{}, path:{},code:{}, error:{}", indexingPage.getSite().getId(), indexingPage.getPath(), indexingPage.getAnswerCode(), ex.getMessage());
            return;
        }
        if (resultForkJoinPoolIndexedPages.get(pagePath) != null || !indexingProcessing.get()) {
            return;
        }
        resultForkJoinPoolIndexedPages.putIfAbsent(indexingPage.getPath(), indexingPage);
        SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
        sitePage.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(sitePage);
        pageRepository.save(indexingPage);
        pageIndexerService.indexHtml(indexingPage.getPageContent(), indexingPage);
        List<PageFinder> indexingPagesTasks = new ArrayList<>();
        for (String url : urlSet) {
            if (resultForkJoinPoolIndexedPages.get(url) == null && indexingProcessing.get()) {
                PageFinder task = new PageFinder(siteRepository, pageRepository, sitePage, url, resultForkJoinPoolIndexedPages, configConnection, lemmaService, pageIndexerService, indexingProcessing);
                task.fork();
                indexingPagesTasks.add(task);
            }
        }
        for (PageFinder page : indexingPagesTasks) {
            if (!indexingProcessing.get()) {
                return;
            }
            page.join();
        }

    }

    public void refreshPage() {

        Page indexingPage = new Page();
        indexingPage.setPath(pagePath);
        indexingPage.setSite(siteDomain);

        try {
            Connection connect = getConnection(siteDomain.getUrl() + pagePath);
            Document doc = connect.timeout(60000).get();
            indexingPage.setPageContent(getContent(doc));
            indexingPage.setAnswerCode(doc.connection().response().statusCode());
            if (indexingPage.getPageContent() == null || indexingPage.getPageContent().isEmpty() || indexingPage.getPageContent().isBlank()) {
                throw new Exception("Content of site id:" + indexingPage.getSite().getId() + ", page:" + indexingPage.getPath() + " is null or empty");
            }
        } catch (Exception ex) {
            errorHandling(ex, indexingPage);
            SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
            sitePage.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(sitePage);
            pageRepository.save(indexingPage);
            return;
        }
        SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
        sitePage.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(sitePage);

        Page pageToRefresh = pageRepository.findPageBySiteIdAndPath(pagePath, sitePage.getId());
        if (pageToRefresh != null) {
            pageToRefresh.setAnswerCode(indexingPage.getAnswerCode());
            pageToRefresh.setPageContent(indexingPage.getPageContent());
            pageRepository.save(pageToRefresh);
            pageIndexerService.refreshIndex(indexingPage.getPageContent(), pageToRefresh);
        } else {
            pageRepository.save(indexingPage);
            pageIndexerService.refreshIndex(indexingPage.getPageContent(), indexingPage);
        }
    }

    private Connection getConnection(String url) {

        return Jsoup.connect(url)
                .userAgent(configConnection.getUserAgent())
                .referrer(configConnection.getReferer());
    }

    private String getContent(Document document) {
        return document.head() + String.valueOf(document.body());
    }

    void errorHandling(Exception ex, Page indexingPage) {
        String message = ex.toString();
        int errorCode;
        if (message.contains("UnsupportedMimeTypeException")) {
            errorCode = 415;    // Ссылка на pdf, jpg, png документы
        } else if (message.contains("Status=401")) {
            errorCode = 401;    // На несуществующий домен
        } else if (message.contains("UnknownHostException")) {
            errorCode = 401;
        } else if (message.contains("Status=403")) {
            errorCode = 403;    // Нет доступа, 403 Forbidden
        } else if (message.contains("Status=404")) {
            errorCode = 404;    // // Ссылка на pdf-документ, несущ. страница, проигрыватель
        } else if (message.contains("Status=500")) {
            errorCode = 401;    // Страница авторизации
        } else if (message.contains("ConnectException: Connection refused")) {
            errorCode = 500;    // ERR_CONNECTION_REFUSED, не удаётся открыть страницу
        } else if (message.contains("SSLHandshakeException")) {
            errorCode = 525;
        } else if (message.contains("Status=503")) {
            errorCode = 503; // Сервер временно не имеет возможности обрабатывать запросы по техническим причинам (обслуживание, перегрузка и прочее).
        } else {
            errorCode = -1;
        }
        indexingPage.setAnswerCode(errorCode);
    }
}