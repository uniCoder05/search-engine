package searchengine.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigConnection;
import searchengine.config.SiteConfig;
import searchengine.config.ListSiteConfig;
import searchengine.exception.UrlNotInSiteListException;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.service.ApiService;
import searchengine.service.PageIndexerService;
import searchengine.util.UrlValidator;

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
                    indexErrorHandler(site, ex.getMessage());
                } catch (Exception ex) {
                    log.info("Unexpected exception site: {} message: {}", site.getUrl() , ex.getMessage());
                    indexErrorHandler(site, "Неожиданная ошибка");
                }
                if (!indexingProcessing.get()) {
                    log.warn("Индексация остановлена пользователем, сайт:" + site.getUrl());
                    indexErrorHandler(site,"Индексация остановлена пользователем");
                } else {
                    site.setStatus(Status.INDEXED);
                    log.info("Проиндексирован сайт: {}", site.getUrl());
                }
                saveIndexingSite(site);
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

    @Override
    public void refreshPage(String urlPage)  {
        String siteUrl = UrlValidator.getSiteUrl(urlPage);
        if (!isValidUrlPage(urlPage,siteUrl)) {
            log.info("not valid urlPage: {}", urlPage );
            throw new UrlNotInSiteListException();
        }
        Site site = new Site();
        Site existSite = siteRepository.getSiteByUrl(siteUrl);
        site.setId(existSite.getId());
        site.setUrl(existSite.getUrl());
        site.setStatus(Status.INDEXING);

        try {
            log.info("Запущена переиндексация страницы: {}", urlPage);
            PageFinder pageFinder = new PageFinder(site,
                    pageRepository,
                    configConnection, pageIndexerService, indexingProcessing);
            pageFinder.refreshPage(urlPage);
        } catch (SecurityException ex) {
            log.info("Security Exception: {}", ex.getMessage());
            indexErrorHandler(site,ex.getMessage());
        } catch (Exception ex) {
            log.info("Unexpected exception: {}", ex.getMessage());
            indexErrorHandler(site,"Неожиданная ошибка");
        }

        log.info("Проиндексирован сайт: {}", site.getName());
        site.setStatus(Status.INDEXED);

        saveIndexingSite(site);
    }

    private boolean isValidUrlPage(String urlPage, String urlSite) {
        if (urlPage.isBlank()) {
            return false;
        }

        if (!isTrustedUrl(urlPage, urlSite)) {
            return false;
        }

        return true;
    }

    private List<String> getUrlsToIndexing() {
        return sitesToIndexing.getSites().stream()
                .map(siteConfig -> siteConfig.getUrl().toString())
                .toList();
    }

    private boolean isTrustedUrl(String urlPage, String urlSite) {
        for (String trustedUrl : getUrlsToIndexing()) {
            if (UrlValidator.isInternalUrl(urlPage, urlSite)) {
                return true;
            }
        }

        return false;
    }

    private void indexErrorHandler(Site site, String errorMessage) {
        site.setStatus(Status.FAILED);
        site.setLastError(errorMessage);
    }

    @Transactional
    private void resetAndSaveAllSites() {
        siteRepository.deleteAll();
        for (SiteConfig siteConfig : sitesToIndexing.getSites()) {
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
    private void saveIndexingSite(Site indexingSite) {
        int id = indexingSite.getId();
        Site site = siteRepository.findById(id).orElseThrow(() -> new RuntimeException("Сайт id = " + id + " не найден в БД"));
        site.setStatus(indexingSite.getStatus());
        site.setLastError(indexingSite.getLastError());
        site.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(site);
    }

}