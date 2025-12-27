package searchengine.service;

import searchengine.dto.statistic.StatisticsResponse;

import java.net.MalformedURLException;

public interface StatisticsService {
    StatisticsResponse getStatistics() throws MalformedURLException;
}
