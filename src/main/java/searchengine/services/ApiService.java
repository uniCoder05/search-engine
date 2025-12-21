package searchengine.services;

import searchengine.model.Site;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public interface ApiService {
    void startIndexing(AtomicBoolean indexingProcessing);

    void refreshPage(String urlPage) throws URISyntaxException;
}