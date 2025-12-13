package searchengine.services;

import searchengine.model.Page;
import searchengine.model.SitePage;

import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис для обхода и индексации веб-страниц сайта.
 * Предоставляет функциональность для рекурсивного обхода страниц сайта
 * и их индексации.
 */
public interface CrawlerService {
    
    /**
     * Запускает рекурсивный обход и индексацию всех страниц указанного сайта.
     * 
     * @param siteDomain сайт для индексации
     * @param startPath начальный путь для обхода (обычно "/")
     * @param indexedPages коллекция для хранения уже проиндексированных страниц (для избежания дублирования)
     * @param indexingProcessing флаг для контроля процесса индексации (можно остановить извне)
     */
    void crawlSite(SitePage siteDomain, String startPath, 
                   java.util.concurrent.ConcurrentHashMap<String, Page> indexedPages,
                   AtomicBoolean indexingProcessing);
    
    /**
     * Обновляет индекс для конкретной страницы.
     * Используется для переиндексации одной страницы без обхода всего сайта.
     * 
     * @param siteDomain сайт, к которому принадлежит страница
     * @param pageUrl URL страницы для обновления
     */
    void refreshPage(SitePage siteDomain, URL pageUrl);
}

