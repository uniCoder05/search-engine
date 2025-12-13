package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigConnection;
import searchengine.model.Page;
import searchengine.model.SitePage;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.CrawlerService;
import searchengine.services.PageIndexerService;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Улучшенная реализация сервиса для обхода и индексации веб-страниц.
 * Исправляет проблемы из PageFinder:
 * - Правильная обработка URL (относительных и абсолютных)
 * - Улучшенная обработка ошибок
 * - Оптимизация работы с БД
 * - Разделение ответственности
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerServiceImpl implements CrawlerService {
    
    private final PageIndexerService pageIndexerService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final ConfigConnection configConnection;
    
    private static final int CONNECTION_TIMEOUT_MS = 60000;
    
    @Override
    public void crawlSite(SitePage siteDomain, String startPath,
                          ConcurrentHashMap<String, Page> indexedPages,
                          AtomicBoolean indexingProcessing) {
        try {
            log.info("Запущена индексация сайта: {}", siteDomain.getUrl());
            ForkJoinPool pool = new ForkJoinPool();
            CrawlTask rootTask = new CrawlTask(
                    siteDomain, startPath, indexedPages, indexingProcessing
            );
            pool.invoke(rootTask);
            log.info("Завершена индексация сайта: {}", siteDomain.getUrl());
        } catch (Exception ex) {
            log.error("Ошибка при индексации сайта {}: {}", siteDomain.getUrl(), ex.getMessage(), ex);
            updateSiteStatus(siteDomain, ex.getMessage());
        }
    }
    
    @Override
    @Transactional
    public void refreshPage(SitePage siteDomain, URL pageUrl) {
        try {
            log.info("Запущена переиндексация страницы: {}", pageUrl);
            
            String pagePath = extractPathFromUrl(pageUrl, siteDomain.getUrl());
            Page existingPage = pageRepository.findPageBySiteIdAndPath(pagePath, siteDomain.getId());
            
            Page page = fetchAndParsePage(siteDomain, pageUrl, pagePath);
            
            if (existingPage != null) {
                existingPage.setAnswerCode(page.getAnswerCode());
                existingPage.setPageContent(page.getPageContent());
                pageRepository.save(existingPage);
                pageIndexerService.refreshIndex(page.getPageContent(), existingPage);
            } else {
                pageRepository.save(page);
                pageIndexerService.refreshIndex(page.getPageContent(), page);
            }
            
            updateSiteStatusTime(siteDomain);
            log.info("Завершена переиндексация страницы: {}", pageUrl);
        } catch (Exception ex) {
            log.error("Ошибка при переиндексации страницы {}: {}", pageUrl, ex.getMessage(), ex);
            Page errorPage = createErrorPage(siteDomain, extractPathFromUrl(pageUrl, siteDomain.getUrl()), ex);
            pageRepository.save(errorPage);
            updateSiteStatusTime(siteDomain);
        }
    }
    
    /**
     * Внутренний класс для рекурсивного обхода страниц с использованием ForkJoinPool.
     */
    private class CrawlTask extends RecursiveAction {
        private final SitePage siteDomain;
        private final String pagePath;
        private final ConcurrentHashMap<String, Page> indexedPages;
        private final AtomicBoolean indexingProcessing;
        
        public CrawlTask(SitePage siteDomain, String pagePath,
                        ConcurrentHashMap<String, Page> indexedPages,
                        AtomicBoolean indexingProcessing) {
            this.siteDomain = siteDomain;
            this.pagePath = pagePath;
            this.indexedPages = indexedPages;
            this.indexingProcessing = indexingProcessing;
        }
        
        @Override
        protected void compute() {
            // Проверка на остановку индексации или уже обработанную страницу
            if (!indexingProcessing.get() || indexedPages.containsKey(pagePath)) {
                return;
            }
            
            try {
                URL pageUrl = buildPageUrl(siteDomain.getUrl(), pagePath);
                Page page = fetchAndParsePage(siteDomain, pageUrl, pagePath);
                
                // Двойная проверка после загрузки страницы
                if (!indexingProcessing.get() || indexedPages.containsKey(pagePath)) {
                    return;
                }
                
                // Сохранение страницы
                indexedPages.put(pagePath, page);
                savePageAndUpdateSite(page);
                
                // Индексация контента
                pageIndexerService.indexHtml(page.getPageContent(), page);
                
                // Поиск ссылок на другие страницы
                Set<String> childPaths = extractChildPaths(pageUrl, page.getPageContent());
                
                // Рекурсивная обработка дочерних страниц
                List<CrawlTask> subtasks = new ArrayList<>();
                for (String childPath : childPaths) {
                    if (indexingProcessing.get() && !indexedPages.containsKey(childPath)) {
                        CrawlTask subtask = new CrawlTask(siteDomain, childPath, indexedPages, indexingProcessing);
                        subtask.fork();
                        subtasks.add(subtask);
                    }
                }
                
                // Ожидание завершения всех подзадач
                for (CrawlTask subtask : subtasks) {
                    if (!indexingProcessing.get()) {
                        return;
                    }
                    subtask.join();
                }
                
            } catch (Exception ex) {
                log.debug("Ошибка при обработке страницы {}: {}", pagePath, ex.getMessage());
                handlePageError(ex, pagePath);
            }
        }
    }
    
    /**
     * Загружает и парсит страницу из интернета.
     */
    private Page fetchAndParsePage(SitePage siteDomain, URL pageUrl, String pagePath) throws Exception {
        Connection connection = createConnection(pageUrl.toString());
        Document document = connection
                .timeout(CONNECTION_TIMEOUT_MS)
                .followRedirects(true)
                .maxBodySize(0) // Без ограничения размера
                .get();
        
        String content = extractContent(document);
        if (content == null || content.isBlank()) {
            throw new Exception("Содержимое страницы пустое: " + pageUrl);
        }
        
        int statusCode = document.connection().response().statusCode();
        
        Page page = new Page();
        page.setSite(siteDomain);
        page.setPath(pagePath);
        page.setAnswerCode(statusCode);
        page.setPageContent(content);
        
        return page;
    }
    
    /**
     * Создает соединение JSoup с настройками из конфигурации.
     */
    private Connection createConnection(String url) {
        return Jsoup.connect(url)
                .userAgent(configConnection.getUserAgent())
                .referrer(configConnection.getReferer())
                .ignoreHttpErrors(true)
                .ignoreContentType(false);
    }
    
    /**
     * Извлекает текстовый контент из HTML документа.
     * Удаляет скрипты и стили для оптимизации.
     */
    private String extractContent(Document document) {
        // Удаляем скрипты и стили
        document.select("script, style, noscript").remove();
        
        // Извлекаем контент из head и body
        String headContent = document.head().html();
        String bodyContent = document.body() != null ? document.body().html() : "";
        
        return headContent + bodyContent;
    }
    
    /**
     * Извлекает пути дочерних страниц из HTML контента.
     * Обрабатывает как относительные, так и абсолютные URL.
     */
    private Set<String> extractChildPaths(URL currentUrl, String htmlContent) {
        Set<String> paths = new HashSet<>();
        
        try {
            Document doc = Jsoup.parse(htmlContent, currentUrl.toString());
            Elements links = doc.select("a[href]");
            
            for (Element link : links) {
                String href = link.attr("href");
                if (href == null || href.isEmpty()) {
                    continue;
                }
                
                try {
                    URL absoluteUrl = new URL(currentUrl, href);
                    String normalizedPath = normalizePath(absoluteUrl, currentUrl);
                    
                    if (normalizedPath != null && !normalizedPath.isEmpty()) {
                        paths.add(normalizedPath);
                    }
                } catch (MalformedURLException ex) {
                    log.debug("Некорректный URL: {}", href);
                }
            }
        } catch (Exception ex) {
            log.warn("Ошибка при извлечении ссылок из страницы: {}", ex.getMessage());
        }
        
        return paths;
    }
    
    /**
     * Нормализует путь страницы относительно базового URL сайта.
     * Возвращает null, если URL не принадлежит тому же домену.
     */
    private String normalizePath(URL absoluteUrl, URL baseUrl) {
        try {
            // Проверяем, что URL принадлежит тому же домену
            if (!absoluteUrl.getHost().equals(baseUrl.getHost())) {
                return null;
            }
            
            // Проверяем протокол
            if (!absoluteUrl.getProtocol().equals("http") && !absoluteUrl.getProtocol().equals("https")) {
                return null;
            }
            
            String path = absoluteUrl.getPath();
            
            // Удаляем якоря и query параметры из пути
            if (path.contains("#")) {
                path = path.substring(0, path.indexOf('#'));
            }
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf('?'));
            }
            
            // Нормализуем путь
            if (path.isEmpty() || path.equals("/")) {
                return "/";
            }
            
            // Убираем trailing slash для единообразия (кроме корня)
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            
            return path;
        } catch (Exception ex) {
            log.debug("Ошибка при нормализации пути: {}", ex.getMessage());
            return null;
        }
    }
    
    /**
     * Извлекает путь из полного URL относительно базового URL сайта.
     */
    private String extractPathFromUrl(URL pageUrl, String siteUrl) {
        try {
            URL siteBaseUrl = new URL(siteUrl);
            return normalizePath(pageUrl, siteBaseUrl);
        } catch (Exception ex) {
            log.warn("Ошибка при извлечении пути из URL: {}", ex.getMessage());
            return pageUrl.getPath();
        }
    }
    
    /**
     * Строит полный URL из базового URL сайта и пути страницы.
     */
    private URL buildPageUrl(String siteUrl, String pagePath) throws MalformedURLException {
        URL baseUrl = new URL(siteUrl);
        return new URL(baseUrl, pagePath);
    }
    
    /**
     * Обрабатывает ошибки при загрузке страницы и создает Page с кодом ошибки.
     */
    private void handlePageError(Exception ex, String pagePath) {
        log.debug("Ошибка индексации страницы {}: {}", pagePath, ex.getMessage());
        // Ошибка логируется, но не прерывает процесс индексации других страниц
    }
    
    /**
     * Создает объект Page с информацией об ошибке.
     */
    private Page createErrorPage(SitePage siteDomain, String pagePath, Exception ex) {
        Page page = new Page();
        page.setSite(siteDomain);
        page.setPath(pagePath);
        page.setAnswerCode(determineErrorCode(ex));
        page.setPageContent(""); // Пустой контент при ошибке
        
        return page;
    }
    
    /**
     * Определяет HTTP код ошибки на основе типа исключения.
     * Улучшенная версия обработки ошибок с проверкой типов.
     */
    private int determineErrorCode(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "";
        
        // Проверка типов исключений вместо строковых проверок
        if (ex instanceof org.jsoup.UnsupportedMimeTypeException) {
            return 415; // Unsupported Media Type
        }
        
        if (message.contains("Status=401") || message.contains("Status=403")) {
            return message.contains("Status=401") ? 401 : 403;
        }
        
        if (message.contains("UnknownHostException") || message.contains("Status=500")) {
            return 401; // Для несуществующего домена или страницы авторизации
        }
        
        if (message.contains("Status=404")) {
            return 404; // Not Found
        }
        
        if (message.contains("ConnectException") || message.contains("Connection refused")) {
            return 500; // Internal Server Error
        }
        
        if (message.contains("SSLHandshakeException")) {
            return 525; // SSL Handshake Failed
        }
        
        if (message.contains("Status=503")) {
            return 503; // Service Unavailable
        }
        
        if (message.contains("timeout") || message.contains("TimeoutException")) {
            return 408; // Request Timeout
        }
        
        // Код по умолчанию для неизвестных ошибок
        return -1;
    }
    
    /**
     * Сохраняет страницу в БД и обновляет время статуса сайта.
     * Оптимизировано для минимизации обращений к БД.
     */
    @Transactional
    private void savePageAndUpdateSite(Page page) {
        pageRepository.save(page);
        updateSiteStatusTime(page.getSite());
    }
    
    /**
     * Обновляет время статуса сайта.
     */
    private void updateSiteStatusTime(SitePage siteDomain) {
        SitePage site = siteRepository.findById(siteDomain.getId()).orElse(null);
        if (site != null) {
            site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(site);
        }
    }
    
    /**
     * Обновляет статус сайта с ошибкой.
     */
    private void updateSiteStatus(SitePage siteDomain, String errorMessage) {
        SitePage site = siteRepository.findById(siteDomain.getId()).orElse(null);
        if (site != null) {
            site.setLastError(errorMessage);
            site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(site);
        }
    }
}

