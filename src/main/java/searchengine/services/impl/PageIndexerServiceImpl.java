package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageIndexerServiceImpl implements PageIndexerService {
    private final LemmaService lemmaService;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexSearchRepository;

    @Override
    public void index(Page indexingPage) {
        String html = indexingPage.getPageContent();
        long start = System.currentTimeMillis();
        try {
            Map<String, Integer> lemmas = lemmaService.getLemmasFromText(html);
            saveLemmasForPage(lemmas, indexingPage);
            log.debug("Лемматизация страницы завершена за {} мс количество найденных лемм: {}", (System.currentTimeMillis() - start), lemmas.size());
        } catch (IOException e) {
            log.error(String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    @Override
    public void refreshIndex(Page refreshPage) {
        String html = refreshPage.getPageContent();
        long start = System.currentTimeMillis();
        try {
            Map<String, Integer> lemmas = lemmaService.getLemmasFromText(html);
            //уменьшение frequency у лемм которые присутствуют на обновляемой странице
            refreshLemma(refreshPage);
            //удаление индекса
            indexSearchRepository.deleteAllByPageId(refreshPage.getId());
            //обновление лемм и индексов у обновленной страницы
            saveLemmasForPage(lemmas, refreshPage);
            log.debug("Лемматизация страницы обновлена за {} мс количество найденных лемм: {}", (System.currentTimeMillis() - start), lemmas.size());
        } catch (IOException e) {
            log.error(String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    @Transactional
    private void refreshLemma(Page refreshPage) {
        List<Index> indexes = indexSearchRepository.findAllByPageId(refreshPage.getId());
        indexes.forEach(idx -> {
            Optional<Lemma> lemmaToRefresh = lemmaRepository.findById(idx.getLemma().getId());
            lemmaToRefresh.ifPresent(lemma -> {
                lemma.setFrequency(lemma.getFrequency() - idx.getLemma().getFrequency());
                lemmaRepository.saveAndFlush(lemma);
            });
        });
    }

    @Transactional
    private void saveLemma(String lemma, Integer rank, Page indexingPage) {
        Lemma existLemmaInDB = lemmaRepository.lemmaExist(lemma, indexingPage.getSite().getId());
        if (existLemmaInDB != null) {
            existLemmaInDB.setFrequency(existLemmaInDB.getFrequency() + rank);
            lemmaRepository.saveAndFlush(existLemmaInDB);
            createIndex(indexingPage, existLemmaInDB, rank);
        } else {
            try {
                Lemma newLemmaToDB = new Lemma();
                newLemmaToDB.setSite(indexingPage.getSite());
                newLemmaToDB.setLemma(lemma);
                newLemmaToDB.setFrequency(rank);
                lemmaRepository.saveAndFlush(newLemmaToDB);
                createIndex(indexingPage, newLemmaToDB, rank);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Ошибка при сохранении леммы, такая лемма уже существует. Вызов повторного сохранения");
                saveLemma(lemma, rank, indexingPage);
            }
        }
    }

    private void createIndex(Page indexingPage, Lemma lemmaInDB, Integer rank) {
        Index indexSearchExist = indexSearchRepository.findIndexByPageIdAndLemmaId(indexingPage.getId(), lemmaInDB.getId());
        if (indexSearchExist != null) {
            indexSearchExist.setRank(indexSearchExist.getRank() + rank);
            indexSearchRepository.save(indexSearchExist);
        } else {
            Index index = new Index();
            index.setPage(indexingPage);
            index.setLemma(lemmaInDB);
            index.setRank(rank);
            index.setLemma(lemmaInDB);
            index.setPage(indexingPage);
            indexSearchRepository.save(index);
        }
    }

    private void saveLemmasForPage(Map<String, Integer> lemmas, Page page) {
        lemmas.entrySet().parallelStream()
                .forEach(entry -> saveLemma(entry.getKey(), entry.getValue(), page));
    }
}