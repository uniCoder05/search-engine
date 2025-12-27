package searchengine.service;

import searchengine.model.Page;

public interface PageIndexerService {
    void index(Page indexingPage);

    void refreshIndex(Page refreshPage);
}