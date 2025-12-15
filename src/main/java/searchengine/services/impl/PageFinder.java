package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.ConfigConnection;
import searchengine.model.Page;
import searchengine.model.SitePage;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.PageIndexerService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class PageFinder extends RecursiveAction {
    private static final String MASK = "[?#.]+";

    private final PageIndexerService pageIndexerService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final AtomicBoolean indexingProcessing;
    private final ConfigConnection configConnection;
    private final Set<String> urlSet = new HashSet<>();
    private final String pagePath;
    private final SitePage siteDomain;
    private final Map<String, Page> visitedLinks;
    private final String mask;

    public PageFinder(SiteRepository siteRepository, PageRepository pageRepository,
                      SitePage siteDomain, String pagePath,
                      ConfigConnection configConnection,
                      PageIndexerService pageIndexerService,
                      AtomicBoolean indexingProcessing) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.pagePath = pagePath;
        this.configConnection = configConnection;
        this.indexingProcessing = indexingProcessing;
        this.siteDomain = siteDomain;
        this.pageIndexerService = pageIndexerService;
        this.mask = MASK + siteDomain;
        this.visitedLinks = new ConcurrentHashMap<>();
    }

    private PageFinder(PageFinder pageFinder, String url) {
        this.siteRepository = pageFinder.siteRepository;
        this.pageRepository = pageFinder.pageRepository;
        this.pagePath = url;
        this.configConnection = pageFinder.configConnection;
        this.indexingProcessing = pageFinder.indexingProcessing;
        this.siteDomain = pageFinder.siteDomain;
        this.pageIndexerService = pageFinder.pageIndexerService;
        this.mask = pageFinder.mask;
        this.visitedLinks = pageFinder.visitedLinks;
    }

    @Override
    protected void compute() {
        Set<PageFinder> subTasks = new HashSet<>();
        crawl(subTasks);
        for (PageFinder task : subTasks) {
            if(!indexingProcessing.get()) {
                return;
            }
            task.join();
        }
    }

    public void crawl(Set<PageFinder> subTasks) {
        if (visitedLinks.get(pagePath) != null || !indexingProcessing.get()) {
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
            Elements links = doc.getElementsByTag("a");
            for (Element link : links)
                if (!link.attr("href").isEmpty() && link.attr("href").charAt(0) == '/') {
                    if (visitedLinks.get(pagePath) != null || !indexingProcessing.get()) {
                        return;
                    } else if (visitedLinks.get(link.attr("href")) == null) {
                        urlSet.add(link.attr("href"));
                    }
                }
            indexingPage.setAnswerCode(doc.connection().response().statusCode());
        } catch (HttpStatusException e) {
            log.info("HttpStatusException code:{} for url: {}", siteDomain + pagePath, e.getStatusCode());
        } catch (Exception ex) {
            errorHandling(ex, indexingPage);
            visitedLinks.putIfAbsent(indexingPage.getPath(), indexingPage);
            updateSiteStatusTimeAndSave();
            pageRepository.save(indexingPage);
            log.debug("ERROR INDEXATION, siteId:{}, path:{},code:{}, error:{}", indexingPage.getSite().getId(), indexingPage.getPath(), indexingPage.getAnswerCode(), ex.getMessage());
            return;
        }
        if (visitedLinks.get(pagePath) != null || !indexingProcessing.get()) {
            return;
        }
        visitedLinks.putIfAbsent(indexingPage.getPath(), indexingPage);
        updateSiteStatusTimeAndSave();
        pageRepository.save(indexingPage);
        pageIndexerService.indexHtml(indexingPage.getPageContent(), indexingPage);
        for (String url : urlSet) {
            if (visitedLinks.get(url) == null && indexingProcessing.get()) {
                PageFinder task = new PageFinder(this, url);
                task.fork();
                subTasks.add(task);
            }
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
            updateSiteStatusTimeAndSave();
            pageRepository.save(indexingPage);
            return;
        }
        updateSiteStatusTimeAndSave();
        Page pageToRefresh = pageRepository.findPageBySiteIdAndPath(pagePath, siteDomain.getId());
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
        return document.html();
    }

    //Метод для проверки ссылки на валидность
    private boolean isValidLink(String link) {
        //Проверка на пустоту
        if(link.isBlank()) {
            return false;
        }
        //Проверка на соответствие маске url + любые символы, кроме ?.#
        if(!link.matches(mask)) {
            return false;
        }
        //Проверка, ссылка на уникальность
        if(visitedLinks.containsKey(link)) {
            return false;
        }

        return true;
    }

    private void updateSiteStatusTimeAndSave() {
        siteDomain.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(siteDomain);
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