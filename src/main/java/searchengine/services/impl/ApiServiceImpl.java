package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigConnection;
import searchengine.config.SiteConfig;
import searchengine.config.ListSiteConfig;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.ApiService;
import searchengine.services.PageIndexerService;

import java.net.URL;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiServiceImpl implements ApiService {
    private static final ForkJoinPool FORK_JOIN_POOL = ForkJoinPool.commonPool();

    private final PageIndexerService pageIndexerService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final ListSiteConfig sitesToIndexing;
    private final ConfigConnection configConnection;
    private AtomicBoolean indexingProcessing;


    @Override
    public void startIndexing(AtomicBoolean indexingProcessing) {
        this.indexingProcessing = indexingProcessing;
        try {
            resetAndSaveAllSites();
            indexAllSite();
        } catch (RuntimeException | InterruptedException ex) {
            indexingProcessing.set(false);
            log.error("Error: ", ex);
        }
    }

    @Override
    public void refreshPage(Site site, URL url) {
        Site existSite = siteRepository.getSiteByUrl(site.getUrl());
        site.setId(existSite.getId());
        try {
            log.info("Запущена переиндексация страницы:{}", url.toString());
            PageFinder pageFinder = new PageFinder(site,
                    pageRepository,
            configConnection, pageIndexerService, indexingProcessing);
            pageFinder.refreshPage(url.toString());
        } catch (SecurityException ex) {
            handleIndexingError(site, ex.getMessage());
        }
        log.info("Проиндексирован сайт: {}", site.getName());
        saveIndexedSite(site);
    }

    private void indexAllSite() throws InterruptedException {
        Set<Site> sites = new HashSet<>(siteRepository.findAll());
        List<Thread> indexingThreadList = new ArrayList<>();
        for (Site site : sites) {
            Runnable indexSite = () -> {
                try {
                    log.info("Запущена индексация сайта id: {} url: {}", site.getId(), site.getUrl());
                    new ForkJoinPool().invoke(new PageFinder(site,
                            pageRepository,
                            configConnection, pageIndexerService,
                            indexingProcessing));
                } catch (SecurityException ex) {
                    handleIndexingError(site, ex.getMessage());
                } catch (Exception ex) {
                    handleIndexingError(site, "Неожиданная ошибка");
                }
                if (!indexingProcessing.get()) {
                    log.warn("Индексация остановлена пользователем, сайт:" + site.getUrl());
                    handleIndexingError(site, "Индексация остановлена пользователем");
                } else {
                    log.info("Проиндексирован сайт: {}", site.getUrl());
                    saveIndexedSite(site);
                }
            };
            Thread thread = new Thread(indexSite);
            indexingThreadList.add(thread);
            thread.start();
        }
        for (Thread thread : indexingThreadList) {
            thread.join();
        }
        indexingProcessing.set(false);
    }

    @Transactional
    private void resetAndSaveAllSites() {
        siteRepository.deleteAll();
        for(SiteConfig siteConfig : sitesToIndexing.getSites()) {
            Site site = new Site();
            site.setStatus(Status.INDEXING);
            site.setName(siteConfig.getName());
            site.setUrl(siteConfig.getUrl().toString());
            site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(site);
            log.info("Save site id: {} url: {}", site.getId(), site.getUrl());
        }
    }

    @Transactional
    private void handleIndexingError(Site indexingSite, String errorMessage) {
        int id = indexingSite.getId();
        Site site = siteRepository.findById(id).orElseThrow();
        site.setStatus(Status.FAILED);
        site.setLastError(errorMessage);
        site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(site);
    }

    @Transactional
    private void saveIndexedSite(Site indexingSite) {
        int id = indexingSite.getId();
        Site site = siteRepository.findById(id).orElseThrow();
        site.setStatus(Status.INDEXED);
        site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(site);
    }
}