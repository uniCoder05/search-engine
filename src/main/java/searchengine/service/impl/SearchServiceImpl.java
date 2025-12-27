package searchengine.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.data.domain.Example;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.RankDto;
import searchengine.dto.response.NotOkResponse;
import searchengine.dto.response.SearchDataResponse;
import searchengine.dto.response.SearchResponse;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.service.LemmaService;
import searchengine.service.SearchService;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private static final double FREQUENCY_LIMIT_PROPORTION = 80.0;
    private static final int MAX_SNIPPET_LENGTH = 200;
    private static final int LEFT_OFFSET = 30;   // отступ влево от первого найденного слова
    private static final int RIGHT_OFFSET = 30;  // отступ вправо от последнего найденного слова

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaService lemmaService;
    private final Status indexSuccessStatus = Status.INDEXED;

    private List<SearchDataResponse> lastSearchResult = new ArrayList<>();
    private String lastQuery = "";

    @Transactional
    @Override
    public ResponseEntity<Object> search(String query, String site, int offset, int limit) throws IOException {
        if (query == null || query.isEmpty()) {
            return ResponseEntity.ok().body(new NotOkResponse("Задан пустой поисковый запрос"));
        }
        if (checkIndexStatusNotIndexed(site)) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
        }

        if (lastQuery.equals(query) && offset != 0) {
            return createResponse(lastSearchResult, offset, limit);
        }

        lastQuery = query;//Запоминаем текст последнего поискового запроса
        Site searchSite = siteRepository.getSiteByUrl(site);
        Map<String, Integer> lemmasMapOfQuery = lemmaService.getLemmasFromText(query);
        log.info("lemmas for search size: {}", lemmasMapOfQuery.size());
        Map<String, Integer> lemmasMap = excludeFrequentlyLemmas(lemmasMapOfQuery, searchSite);
        if (lemmasMap.isEmpty()) {
            return getNoResultsResponse();
        }

        Map<String, Integer> sortedLemmasMap = sortByFrequencyAsc(lemmasMap);
        List<String> lemmasSortList = sortedLemmasMap.keySet().stream().toList();
        List<Page> pages = findPageMatchingQuery(lemmasSortList, searchSite);
        log.info("Количество страниц с леммами из поискового запроса {}", pages.size());
        if (pages.isEmpty()) {
            return getNoResultsResponse();
        }
        // Расчёт релевантности
        List<RankDto> pagesRelevance = getPagesRelevance(pages, lemmasSortList, searchSite);
        // Сортировка страниц по релевантности (от большей к меньшей)
        sortByRelativeRelevanceDesc(pagesRelevance);
        // Преобразование в SearchDataResponse
        List<SearchDataResponse> searchDataResponseList = convertToSearchDataResponse(pagesRelevance, lemmasSortList);
        //Сортировка по релевантности и по количеству найденных слов
        lastSearchResult = searchDataResponseList.stream()
                .sorted(Comparator.comparingDouble(SearchDataResponse::getRelevance).reversed())
                .toList();

        return createResponse(lastSearchResult, offset, limit);
    }

    private ResponseEntity<Object> createResponse(List<SearchDataResponse> searchResult, int offset, int limit) {

        int totalSize = searchResult.size(); //количеств строк в поисковом ответе
        //Если количество строк в ответе меньше лимита для вывода, то выводим весь результат сразу
        if (totalSize <= limit) {
            return ResponseEntity.ok().body(new SearchResponse(true, totalSize, searchResult));
        }
        //Если смещение выходит за размеры ответа, возвращаем пустой результат
        if (offset > totalSize) {
            return getNoResultsResponse();
        }

        int to = Math.min(offset + limit, totalSize);

        List<SearchDataResponse> result = searchResult.subList(offset, to);

        return ResponseEntity.ok().body(new SearchResponse(true, searchResult.size(), result));
    }

    private Boolean checkIndexStatusNotIndexed(String site) {
        if (site == null || site.isBlank()) {
            List<Site> sites = siteRepository.findAll();
            return sites.stream().anyMatch(s -> !s.getStatus().equals(indexSuccessStatus));
        }
        Site foundSite = siteRepository.getSiteByUrl(site);
        return foundSite == null || !foundSite.getStatus().equals(indexSuccessStatus);
    }

    private Map<String, Integer> excludeFrequentlyLemmas(Map<String, Integer> lemmasMap, Site site) {
        Map<String, Integer> result = new HashMap<>();
        Integer siteId = site != null ? site.getId() : null;
        int countPages = pageRepository.getCountPages(siteId);
        log.info("count pages: {}", countPages);
        Set<String> uniqSimpleLemmas = lemmasMap.keySet();
        log.info("uniq lemmas for search: {}", String.join("; ", uniqSimpleLemmas));
        //Получаем мапу частотности поисковых лемм одним запросом в БД (текст леммы - общая частота)
        List<Object[]> results = lemmaRepository.findLemmaFrequencies(uniqSimpleLemmas, siteId);
        Map<String, Integer> lemmaFrequencies = results.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> ((Long) row[1]).intValue()

        ));
        log.info("lemma frequencies size: {}", lemmaFrequencies.size());
        if (lemmaFrequencies.isEmpty()) {
            return result;
        }
        //Исключаем высокочастотные леммы
        for (String lemma : uniqSimpleLemmas) {
            int frequency = lemmaFrequencies.get(lemma);
            double frequencyProportion = (double) frequency / countPages * 100;
            log.info("Лемма: {} Частотная пропорция: {}", lemma, frequencyProportion);
            //Если лемма из поискового запроса есть в БД и её частотность не выше установленного лимита, добавляем в результат
            if (frequency != 0 && (frequencyProportion <= FREQUENCY_LIMIT_PROPORTION)) {
                result.put(lemma, frequency);
            }
        }
        return result;
    }

    private Map<String, Integer> sortByFrequencyAsc(Map<String, Integer> lemmas) {

        return lemmas.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private List<Page> findPageMatchingQuery(List<String> lemmasStrings, Site site) {
        List<Page> pages = getListPagesFoundLemmas(lemmasStrings.get(0), site);
        log.info("findPageMatchingQuery pages count: {}", pages.size());
        for (int i = 1; i < lemmasStrings.size(); i++) {
            List<Page> pagesNext = getListPagesFoundLemmas(lemmasStrings.get(i), site);
            pages = pages.stream()
                    .filter(pagesNext::contains)
                    .toList();
        }
        return pages;
    }

    private List<RankDto> getPagesRelevance(List<Page> pages, List<String> lemmasList, Site site) {
        List<RankDto> result = new ArrayList<>();
        List<Lemma> lemmas = findAllLemmasByName(lemmasList, site);
        int maxAbsRelevance = 0;

        for (Page page : pages) {
            RankDto rankDto = new RankDto();
            Index index = new Index();
            index.setPage(page);
            List<Index> indexes = indexRepository.findAllByPageId(page.getId());
            indexes = indexes.stream()
                    .filter(i -> lemmas.contains(i.getLemma()))
                    .toList();
            Map<Lemma, Integer> lemmasMap = indexes.stream()
                    .collect(Collectors.toMap(Index::getLemma, Index::getRank));
            int absRelevance = lemmasMap.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
            rankDto.setPage(page);
            rankDto.setPageId(page.getId());
            rankDto.setAbsRelevance(absRelevance);
            result.add(rankDto);
        }

        return resolveRelRelevance(result, maxAbsRelevance);
    }

    private void sortByRelativeRelevanceDesc(List<RankDto> ranks) {
        ranks.sort(Comparator.comparingDouble(RankDto::getRelativeRelevance).reversed());
    }

    private List<SearchDataResponse> convertToSearchDataResponse(List<RankDto> ranks, List<String> lemmas) {
        List<SearchDataResponse> result = new ArrayList<>();

        for (RankDto rank : ranks) {
            Document doc = Jsoup.parse(rank.getPage().getPageContent());
            List<String> sentences = doc.body()
                    .getElementsMatchingOwnText("[\\p{IsCyrillic}]")
                    .stream()
                    .map(Element::text)
                    .toList();

            for (String sentence : sentences) {
                SnippetResult snippetResult = extractSnippetWithHighlightingAndContext(sentence, lemmas);
                if (snippetResult.hasMatches()) {
                    String truncatedSnippet = truncateSnippet(snippetResult.getSnippet(), MAX_SNIPPET_LENGTH);
                    Site sitePage = rank.getPage().getSite();
                    result.add(new SearchDataResponse(
                            sitePage.getUrl().substring(sitePage.getUrl().length() - 1),
                            sitePage.getName(),
                            rank.getPage().getPath(),
                            doc.title(),
                            truncatedSnippet,
                            rank.getRelativeRelevance(),
                            snippetResult.getMatchCount()
                    ));
                }
            }
        }
        return result;
    }


    //Формирует сниппет с выделением ключевых слов и контекстом вокруг них.
    private SnippetResult extractSnippetWithHighlightingAndContext(String sentence, List<String> lemmas) {
        List<HighlightedWord> highlightedWords = new ArrayList<>();
        int matchCount = 0;

        String[] words = sentence.split("[\\s\\p{Punct}]+");
        int[] wordPositions = calculateWordPositions(sentence, words);

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;

            String cleanedWord = word.replaceAll("\\p{Punct}", "");
            String lemmaFromWord = lemmaService.getLemmaByWord(cleanedWord);


            if (lemmas.contains(lemmaFromWord)) {
                highlightedWords.add(new HighlightedWord(word, wordPositions[i], word.length()));
                matchCount++;
            }
        }

        if (highlightedWords.isEmpty()) {
            return new SnippetResult("", 0);
        }

        int firstWordStart = highlightedWords.get(0).startPos;
        int lastWordEnd = highlightedWords.get(highlightedWords.size() - 1).endPos;

        int contextStart = Math.max(0, firstWordStart - LEFT_OFFSET);
        int contextEnd = Math.min(sentence.length(), lastWordEnd + RIGHT_OFFSET);

        StringBuilder snippet = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            int wordStart = wordPositions[i];
            int wordEnd = wordStart + words[i].length();


            if (wordEnd < contextStart || wordStart > contextEnd) {
                continue; // слово вне контекста
            }

            if (isWordHighlighted(words[i], highlightedWords)) {
                snippet.append("<b>").append(words[i]).append("</b> ");
            } else {
                snippet.append(words[i]).append(" ");
            }
        }

        return new SnippetResult(snippet.toString().trim(), matchCount);
    }


    //Рассчитывает стартовые позиции каждого слова в исходной строке.
    private int[] calculateWordPositions(String sentence, String[] words) {
        int[] positions = new int[words.length];
        int pos = 0;
        for (int i = 0; i < words.length; i++) {
            // Находим позицию слова, пропуская разделители
            while (pos < sentence.length() && !sentence.substring(pos).startsWith(words[i])) {
                pos++;
            }
            positions[i] = pos;
            pos += words[i].length();
        }
        return positions;
    }


     //Проверяет, нужно ли выделять слово.
    private boolean isWordHighlighted(String word, List<HighlightedWord> highlightedWords) {
        return highlightedWords.stream().anyMatch(hw -> hw.word.equals(word));
    }


    //Обрезает сниппет до максимальной длины, сохраняя целостность слов.
    private String truncateSnippet(String snippet, int maxLength) {
        if (snippet.length() <= maxLength) {
            return snippet;
        }
        int lastSpace = snippet.lastIndexOf(' ', maxLength);
        if (lastSpace == -1) {
            return snippet.substring(0, maxLength) + "...";
        } else {
            return snippet.substring(0, lastSpace).trim() + "...";
        }
    }

    // Вспомогательные классы
    private static class HighlightedWord {
        String word;
        int startPos;
        int endPos;

        public HighlightedWord(String word, int startPos, int length) {
            this.word = word;
            this.startPos = startPos;
            this.endPos = startPos + length;
        }
    }

    private static class SnippetResult {
        private final String snippet;
        private final int matchCount;

        public SnippetResult(String snippet, int matchCount) {
            this.snippet = snippet;
            this.matchCount = matchCount;
        }

        public String getSnippet() { return snippet; }
        public int getMatchCount() { return matchCount; }
        public boolean hasMatches() { return matchCount > 0; }
    }

    private ResponseEntity<Object> getNoResultsResponse() {
        return ResponseEntity.ok().body(new SearchResponse(true, 0, Collections.emptyList()));
    }

    //Вернуть список страниц по тексту леммы и сайту
    private List<Page> getListPagesFoundLemmas(String lemmaStr, Site site) {
        List<Index> indexes = new ArrayList<>();
        List<Lemma> lemmasList = findLemmaByName(lemmaStr, site);
        for (Lemma lemma : lemmasList) {
            Index entity = new Index();
            entity.setLemma(lemma);
            indexes.addAll(indexRepository.findAll(Example.of(entity)));
        }
        return indexes.stream()
                .map(Index::getPage)
                .collect(Collectors.toList());
    }

    private List<Lemma> findLemmaByName(String lemmaStr, Site site) {
        Lemma exLemma = new Lemma();
        exLemma.setLemma(lemmaStr);
        exLemma.setSite(site);
        return lemmaRepository.findAll(Example.of(exLemma));
    }

    private List<RankDto> resolveRelRelevance(List<RankDto> ranks, int maxAbsRelevance) {

        Function<RankDto, RankDto> setMaxAndRelRelevance = (rank) -> {
            rank.setMaxLemmaRank(maxAbsRelevance);
            rank.setRelativeRelevance(rank.getAbsRelevance() / rank.getMaxLemmaRank());
            return rank;
        };

        return ranks.stream()
                .map(setMaxAndRelRelevance)
                .collect(Collectors.toList());
    }

    private List<Lemma> findAllLemmasByName(List<String> lemmasList, Site site) {

        return lemmasList.stream()
                .map(lemma -> findLemmaByName(lemma, site))
                .flatMap(Collection::stream)
                .toList();
    }

}
