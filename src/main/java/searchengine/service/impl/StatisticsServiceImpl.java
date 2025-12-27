package searchengine.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.ListSiteConfig;
import searchengine.dto.statistic.DetailedStatisticsItem;
import searchengine.dto.statistic.StatisticsData;
import searchengine.dto.statistic.StatisticsResponse;
import searchengine.dto.statistic.TotalStatistics;
import searchengine.model.Site;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.service.StatisticsService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final ListSiteConfig sitesConfig;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        List<Site> sites = siteRepository.findAll();

        return sites.isEmpty() ? initialStatistics() : indexedStatistics(sites);
    }

    private StatisticsResponse indexedStatistics(List<Site> sites) {
        StatisticsResponse response = new StatisticsResponse();
        TotalStatistics total = new TotalStatistics();
        StatisticsData data = new StatisticsData();
        List<DetailedStatisticsItem> detailed = new ArrayList<>();

        for(Site site : sites) {
            var item = createDetailedItemFromSite(site);
            total.setPages(total.getPages() + item.getPages());
            total.setLemmas(total.getLemmas() + item.getLemmas());
            detailed.add(item);
        }

        total.setIndexing(true);
        total.setSites(sites.size());
        data.setDetailed(detailed);
        data.setTotal(total);
        response.setStatistics(data);
        response.setResult(true);

        return response;
    }

    private DetailedStatisticsItem createDetailedItemFromSite(Site site) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        int siteId = site.getId();
        int pageCount = pageRepository.findCountRecordBySiteId(siteId);
        int lemmaCount = lemmaRepository.findCountRecordBySiteId(siteId);

        item.setUrl(site.getUrl());
        item.setName(site.getName());
        item.setStatus(String.valueOf(site.getStatus()));
        item.setStatusTime(site.getStatusTime().getTime());
        item.setError(site.getLastError());
        item.setPages(pageCount);
        item.setLemmas(lemmaCount);

        return item;
    }

    private StatisticsResponse initialStatistics() {
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        TotalStatistics total = getInitialTotalStatistics();

        List<SiteConfig> sites = sitesConfig.getSites();
        List<DetailedStatisticsItem> detailed = sites.stream()
                .map(this::createDetailedItemFromSiteConfig)
                .toList();

        data.setTotal(total);
        data.setDetailed(detailed);

        response.setStatistics(data);
        response.setResult(true);

        return response;
    }

    private DetailedStatisticsItem createDetailedItemFromSiteConfig(SiteConfig siteConfig) {
        var item = new DetailedStatisticsItem();
        item.setName(siteConfig.getName());
        item.setUrl(String.valueOf(siteConfig.getUrl()));
        item.setPages(0);
        item.setLemmas(0);
        item.setStatus("WAIT");
        item.setStatusTime(Instant.now().toEpochMilli());

        return item;
    }

    private TotalStatistics getInitialTotalStatistics() {
        TotalStatistics total = new TotalStatistics();
        total.setIndexing(false);
        total.setSites(sitesConfig.getSites().size());
        total.setPages(0);
        total.setLemmas(0);

        return total;
    }
}