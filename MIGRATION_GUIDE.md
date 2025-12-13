# Руководство по миграции с PageFinder на CrawlerService

## Как заменить PageFinder на CrawlerService

Новый `CrawlerService` полностью совместим с существующим кодом и может заменить `PageFinder` без изменения остальной части приложения.

## Изменения в ApiServiceImpl

### Метод `refreshPage()` (строки 56-77)

**Было:**
```java
@Override
public void refreshPage(SitePage siteDomain, URL url) {
    SitePage existSitePage = siteRepository.getSitePageByUrl(siteDomain.getUrl());
    siteDomain.setId(existSitePage.getId());
    ConcurrentHashMap<String, Page> resultForkJoinPageIndexer = new ConcurrentHashMap<>();
    try {
        log.info("Запущена переиндексация страницы:{}", url.toString());
        PageFinder pageFinder = new PageFinder(pageIndexerService, lemmaService,
                siteRepository, pageRepository, indexingProcessing, configConnection,
                url.getPath(), siteDomain, resultForkJoinPageIndexer);
        pageFinder.refreshPage();
    } catch (SecurityException ex) {
        // обработка ошибок
    }
    // обновление статуса сайта
}
```

**Стало:**
```java
@Override
public void refreshPage(SitePage siteDomain, URL url) {
    SitePage existSitePage = siteRepository.getSitePageByUrl(siteDomain.getUrl());
    siteDomain.setId(existSitePage.getId());
    try {
        log.info("Запущена переиндексация страницы:{}", url.toString());
        crawlerService.refreshPage(siteDomain, url);
    } catch (Exception ex) {
        SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
        sitePage.setStatus(Status.FAILED);
        sitePage.setLastError(ex.getMessage());
        siteRepository.save(sitePage);
    }
    log.info("Проиндексирован сайт: {}", siteDomain.getName());
    SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
    sitePage.setStatus(Status.INDEXED);
    siteRepository.save(sitePage);
}
```

**Необходимые изменения:**
1. Добавить поле `private final CrawlerService crawlerService;` в конструктор
2. Удалить создание `PageFinder` и вызов `refreshPage()`
3. Заменить на вызов `crawlerService.refreshPage(siteDomain, url)`

### Метод `indexAllSitePages()` (строки 101-149)

**Было:**
```java
private void indexAllSitePages() throws InterruptedException {
    // ... подготовка сайтов ...
    for (SitePage siteDomain : sitePagesAllFromDB) {
        Runnable indexSite = () -> {
            ConcurrentHashMap<String, Page> resultForkJoinPageIndexer = new ConcurrentHashMap<>();
            try {
                log.info("Запущена индексация {}", siteDomain.getUrl());
                new ForkJoinPool().invoke(new PageFinder(pageIndexerService,
                        lemmaService, siteRepository,
                        pageRepository, indexingProcessing,
                        configConnection, "", siteDomain,
                        resultForkJoinPageIndexer));
            } catch (SecurityException ex) {
                // обработка ошибок
            }
            // обновление статуса
        };
        Thread thread = new Thread(indexSite);
        indexingThreadList.add(thread);
        thread.start();
    }
    // ожидание завершения потоков
}
```

**Стало:**
```java
private void indexAllSitePages() throws InterruptedException {
    // ... подготовка сайтов ...
    for (SitePage siteDomain : sitePagesAllFromDB) {
        Runnable indexSite = () -> {
            ConcurrentHashMap<String, Page> indexedPages = new ConcurrentHashMap<>();
            try {
                log.info("Запущена индексация {}", siteDomain.getUrl());
                crawlerService.crawlSite(siteDomain, "/", indexedPages, indexingProcessing);
            } catch (Exception ex) {
                SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
                sitePage.setStatus(Status.FAILED);
                sitePage.setLastError(ex.getMessage());
                siteRepository.save(sitePage);
            }
            if (!indexingProcessing.get()) {
                log.warn("Indexing stopped by user, site:" + siteDomain.getUrl());
                SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
                sitePage.setStatus(Status.FAILED);
                sitePage.setLastError("Indexing stopped by user");
                siteRepository.save(sitePage);
            } else {
                log.info("Проиндексирован сайт: {}", siteDomain.getUrl());
                SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
                sitePage.setStatus(Status.INDEXED);
                siteRepository.save(sitePage);
            }
        };
        Thread thread = new Thread(indexSite);
        indexingThreadList.add(thread);
        thread.start();
    }
    // ожидание завершения потоков
}
```

**Необходимые изменения:**
1. Заменить создание `PageFinder` на вызов `crawlerService.crawlSite()`
2. Убрать зависимость от `lemmaService` в этом месте (она больше не нужна)
3. Остальная логика остается без изменений

## Полный пример измененного ApiServiceImpl

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiServiceImpl implements ApiService {
    private final PageIndexerService pageIndexerService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SitesList sitesToIndexing;
    private final Set<SitePage> sitePagesAllFromDB;
    private final CrawlerService crawlerService; // НОВОЕ: добавлен CrawlerService
    private AtomicBoolean indexingProcessing;

    // остальные методы без изменений...
    
    @Override
    public void refreshPage(SitePage siteDomain, URL url) {
        SitePage existSitePage = siteRepository.getSitePageByUrl(siteDomain.getUrl());
        siteDomain.setId(existSitePage.getId());
        try {
            log.info("Запущена переиндексация страницы:{}", url.toString());
            crawlerService.refreshPage(siteDomain, url);
        } catch (Exception ex) {
            SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
            sitePage.setStatus(Status.FAILED);
            sitePage.setLastError(ex.getMessage());
            siteRepository.save(sitePage);
        }
        log.info("Проиндексирован сайт: {}", siteDomain.getName());
        SitePage sitePage = siteRepository.findById(siteDomain.getId()).orElseThrow();
        sitePage.setStatus(Status.INDEXED);
        siteRepository.save(sitePage);
    }
    
    private void indexAllSitePages() throws InterruptedException {
        // ... подготовка ...
        for (SitePage siteDomain : sitePagesAllFromDB) {
            Runnable indexSite = () -> {
                ConcurrentHashMap<String, Page> indexedPages = new ConcurrentHashMap<>();
                try {
                    log.info("Запущена индексация {}", siteDomain.getUrl());
                    crawlerService.crawlSite(siteDomain, "/", indexedPages, indexingProcessing);
                } catch (Exception ex) {
                    // обработка ошибок
                }
                // обновление статуса
            };
            // создание и запуск потока
        }
    }
}
```

## Преимущества нового подхода

1. **Разделение ответственности**: CrawlerService отвечает только за обход страниц
2. **Улучшенная обработка URL**: Правильная нормализация и проверка URL
3. **Лучшая обработка ошибок**: Проверка типов исключений вместо строковых проверок
4. **Оптимизация БД**: Меньше обращений к базе данных
5. **Тестируемость**: Легче тестировать отдельные компоненты
6. **Расширяемость**: Легче добавлять новую функциональность

## Что можно удалить после миграции

После полной миграции на `CrawlerService` можно удалить:
- `PageFinder.java` (больше не используется)
- Зависимость от `lemmaService` в `ApiServiceImpl` (если она больше нигде не используется)

## Обратная совместимость

Новый `CrawlerService` полностью совместим с существующим API и не требует изменений в:
- Контроллерах
- DTO классах
- Репозиториях
- Других сервисах

