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

import java.net.URI;
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
    private final String urlPage;
    private final SitePage siteDomain;
    private final Set<String> visitedLinks;
    private final String mask;

    public PageFinder(SitePage siteDomain,
                      SiteRepository siteRepository, PageRepository pageRepository,
                      ConfigConnection configConnection,
                      PageIndexerService pageIndexerService,
                      AtomicBoolean indexingProcessing) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.urlPage = siteDomain.getUrl();
        this.configConnection = configConnection;
        this.indexingProcessing = indexingProcessing;
        this.siteDomain = siteDomain;
        this.pageIndexerService = pageIndexerService;
        this.mask = MASK + siteDomain;
        this.visitedLinks = ConcurrentHashMap.newKeySet();
    }

    private PageFinder(PageFinder pageFinder, String url) {
        this.siteDomain = pageFinder.siteDomain;
        this.siteRepository = pageFinder.siteRepository;
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
        if (visitedLinks.contains(urlPage) || !indexingProcessing.get()) {
            return;
        }
        visitedLinks.add(urlPage);
        Page indexingPage = new Page();
        indexingPage.setSite(siteDomain);
        String path = "";
        String content = "";
        int statusCode = 0;
        try {
            path = new URI(urlPage).getPath();
            indexingPage.setPath(path);
            Connection connection = getConnection(urlPage);
            var response = connection.execute();
            statusCode = response.statusCode();
            if (statusCode == 200) {
                Document document = connection
                        .timeout(60_000)
                        .get();
                content = getContent(document);//Даже если контент пустой, всё равно сохраняем и идём дальше
                Elements links = document.getElementsByTag("a");
                Set<String> urlSet = new HashSet<>(); //Собираем все ссылки на странице
                for (Element link : links) {
                    String absLink = link.attr("abs:href");
                    if (isValidLink(absLink)) {
                        urlSet.add(absLink);
                    }
                }
                for (String url : urlSet) { // Отправляем найденные ссылки в crawl задачи
                    if (visitedLinks.contains(url) || !indexingProcessing.get()) {
                        return;
                    }
                    PageFinder task = new PageFinder(this, url);
                    task.fork();
                    subTasks.add(task);
                }
            }
        } catch (HttpStatusException e) {
            indexingPage.setAnswerCode(statusCode);
            log.info("HttpStatusException code:{} for url: {}", urlPage, e.getStatusCode());
        } catch (Exception ex) {
            log.debug("ERROR INDEXATION, url:{}, code:{}, error:{}", urlPage, statusCode, ex.getMessage());
        } finally {
            indexingPage.setPageContent(content);
            indexingPage.setAnswerCode(statusCode);
            pageRepository.save(indexingPage);
            updateSite();
            if (!content.isBlank()) {
                pageIndexerService.indexHtml(content, indexingPage);
            }
        }
    }

    public void refreshPage() {

        Page indexingPage = new Page();
        indexingPage.setSite(siteDomain);
        String content = "";
        int statusCode = 0;
        String path = "";
        try {
            path = new URI(urlPage).getPath();
            Connection connection = getConnection(urlPage);
            var response = connection.execute();
            statusCode = response.statusCode();
            if (statusCode == 200) {
                Document doc = connection
                        .timeout(60000)
                        .get();
                content = getContent(doc); //Даже если контент пустой, всё равно сохраняем и идём дальше
            }
//            if (indexingPage.getPageContent() == null || indexingPage.getPageContent().isEmpty() || indexingPage.getPageContent().isBlank()) {
//                throw new Exception("Content of site id:" + indexingPage.getSite().getId() + ", page:" + indexingPage.getPath() + " is null or empty");
//            }

        } catch (HttpStatusException e) {
            statusCode = e.getStatusCode();
            log.info("HttpStatusException code {} for url {}", statusCode, urlPage);
        } catch (Exception ex) {
            log.debug("ERROR INDEXATION, url:{}, code:{}, error:{}", urlPage, statusCode, ex.getMessage());
        } finally {
            indexingPage.setPath(path);
            indexingPage.setPageContent(content);
            indexingPage.setAnswerCode(statusCode);
            pageRepository.save(indexingPage);
            updateSite();
            pageIndexerService.refreshIndex(content, indexingPage);
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
        if (link.isBlank()) {
            return false;
        }
        //Проверка на соответствие маске url + любые символы, кроме ?.#
        if (!link.matches(mask)) {
            return false;
        }
        //Проверка, ссылка на уникальность
        if (visitedLinks.contains(link)) {
            return false;
        }

        return true;
    }

    private void updateSite() {
//        siteDomain.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(siteDomain);
    }

//    void errorHandling(Exception ex, Page indexingPage) {
//        String message = ex.toString();
//        int errorCode;
//        if (message.contains("UnsupportedMimeTypeException")) {
//            errorCode = 415;    // Ссылка на pdf, jpg, png документы
//        } else if (message.contains("Status=401")) {
//            errorCode = 401;    // На несуществующий домен
//        } else if (message.contains("UnknownHostException")) {
//            errorCode = 401;
//        } else if (message.contains("Status=403")) {
//            errorCode = 403;    // Нет доступа, 403 Forbidden
//        } else if (message.contains("Status=404")) {
//            errorCode = 404;    // // Ссылка на pdf-документ, несущ. страница, проигрыватель
//        } else if (message.contains("Status=500")) {
//            errorCode = 401;    // Страница авторизации
//        } else if (message.contains("ConnectException: Connection refused")) {
//            errorCode = 500;    // ERR_CONNECTION_REFUSED, не удаётся открыть страницу
//        } else if (message.contains("SSLHandshakeException")) {
//            errorCode = 525;
//        } else if (message.contains("Status=503")) {
//            errorCode = 503; // Сервер временно не имеет возможности обрабатывать запросы по техническим причинам (обслуживание, перегрузка и прочее).
//        } else {
//            errorCode = -1;
//        }
//        indexingPage.setAnswerCode(errorCode);
//    }
}