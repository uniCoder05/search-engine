package searchengine.services;

import searchengine.model.Page;

public interface PageIndexerService {
    void indexHtml(Page indexingPage);

    void refreshIndex(Page refreshPage);
}