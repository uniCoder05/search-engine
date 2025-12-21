package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.config.ListSiteConfig;
import searchengine.dto.responses.IndexingResponse;
import searchengine.dto.responses.NotOkResponse;
import searchengine.dto.responses.OkResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.ApiService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final SearchService searchService;
    private final StatisticsService statisticsService;
    private final ApiService apiService;
    private final AtomicBoolean indexingProcessing = new AtomicBoolean(false);
    private final ListSiteConfig sitesList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() throws MalformedURLException {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity startIndexing() {
        if (indexingProcessing.get()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotOkResponse("Индексация уже запущена"));
        } else {
            executor.submit(() -> {
                indexingProcessing.set(true);
                apiService.startIndexing(indexingProcessing);
            });
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity stopIndexing() {
        if (!indexingProcessing.get()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new NotOkResponse("Индексация не запущена"));
        } else {
            indexingProcessing.set(false);
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity indexPage(@RequestParam String url) throws IOException, URISyntaxException {
        try {
            log.info("Передан на индексацию url: {}", url);
            apiService.refreshPage(url);
        } catch (Exception ex) {
            var response = new IndexingResponse(false, ex.getMessage());
            return new ResponseEntity<>(response, HttpStatus.OK);
        }

        return new ResponseEntity<>(new OkResponse(), HttpStatus.OK);
    }

    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String site,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            @RequestParam(required = false, defaultValue = "20") Integer limit
    ) throws IOException {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Задан пустой поисковый запрос"));
        }
        return searchService.search(query, site, offset, limit);
    }
}
