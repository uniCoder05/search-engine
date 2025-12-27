package searchengine.service.impl;

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
import searchengine.service.LemmaService;
import searchengine.service.PageIndexerService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
            log.debug("Лемматизация страницы ID={} завершена за {} мс количество найденных лемм: {}", indexingPage.getId(), (System.currentTimeMillis() - start), lemmas.size());
        } catch (IOException e) {
            log.error("Ошибка при лемматизации страницы ID={}", indexingPage.getId(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    @Transactional
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
            log.error("Ошибка при обновлении индекса страницы ID={}", refreshPage.getId(), e);
            throw new RuntimeException("Не удалось проиндексировать страницу", e);
        }
    }

    private void refreshLemma(Page refreshPage) {
        List<Index> indexes = indexSearchRepository.findAllByPageId(refreshPage.getId());
        if (indexes.isEmpty()) {
            return;
        }
        Set<Integer> lemmaIds = indexes.stream()
                .map(idx -> idx.getLemma().getId())
                .collect(Collectors.toSet());

        Map<Integer, Lemma> lemmasMap = lemmaRepository.findAllById(lemmaIds).stream()
                .collect(Collectors.toMap(Lemma::getId, lemma -> lemma));

        indexes.forEach(idx -> {
            Lemma lemma = lemmasMap.get(idx.getLemma().getId());
            if (lemma != null) {
                lemma.setFrequency(lemma.getFrequency() - idx.getRank());
                lemmaRepository.saveAndFlush(lemma);
            }
        });
    }

    @Transactional
    private void saveLemma(String lemma, Integer rank, Page indexingPage) {
        Lemma existLemmaInDB = lemmaRepository.lemmaExist(lemma, indexingPage.getSite().getId());
        if (existLemmaInDB != null) {
            existLemmaInDB.setFrequency(existLemmaInDB.getFrequency() + rank);
            lemmaRepository.save(existLemmaInDB);
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
                log.debug("Не удалось сохранить лемму '{}'. Повторная попытка.", lemma, ex);
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
            indexSearchRepository.save(index);
        }
    }

    private void saveLemmasForPage(Map<String, Integer> lemmas, Page page) {
        lemmas.entrySet().parallelStream()
                .forEach(entry -> saveLemma(entry.getKey(), entry.getValue(), page));
    }
}