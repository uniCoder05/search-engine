package searchengine.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.WrongCharaterException;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import searchengine.services.LemmaService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LemmaServiceImpl implements LemmaService {
    private static final Set<String> FUNCTIONAL_POS = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ");

    private final LuceneMorphology luceneMorphology;

    public LemmaServiceImpl() {
        try {
            this.luceneMorphology = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException("Не удалось инициализировать RussianLuceneMorphology", e);
        }
    }

    @Override
    public Map<String, Integer> getLemmasFromText(String html) {
        String rawText = Jsoup.parse(html).text();

        return Arrays.stream(getPreparedWordsArray(rawText))
                .filter(this::isValidWord)
                .map(this::getLemmaByWord)
                .filter(lemma -> !lemma.isEmpty())
                .collect(Collectors.toMap(
                        lemma -> lemma, //key
                        lemma -> 1, //value
                        Integer::sum //if value already exists
                ));
    }

    @Override
    public String getLemmaByWord(String word) {
        log.debug("Обрабатывается слово: '{}'", word);
        if (isWrongWord(word)) {
            log.debug("Слово отклонено по критериям валидности");
            return "";
        }
        try {
            List<String> normalWordForms = luceneMorphology.getNormalForms(word);
            if (normalWordForms == null || normalWordForms.isEmpty()) {
                return "";
            }

            String wordInfo = luceneMorphology.getMorphInfo(word).toString();
            if (isFunctionalPartOfSpeech(wordInfo)) {
                return "";
            }
            return normalWordForms.get(0);
        } catch (WrongCharaterException ex) {
            log.debug("Ошибка анализа слова '{}': {}", word, ex.getMessage());
            return "";
        }
    }

    private String[] getPreparedWordsArray(String rawText) {
        String rgx = "[^a-яё\\-']+"; //Для разбиения текста на слова по всему, что не является буквами
        String cleaned = rawText.toLowerCase().replaceAll(rgx, " ");
        return cleaned.trim().split("\\s+");
    }

    private boolean isWrongWord(String word) {
        if(word == null || word.isEmpty()) {
            return true;
        }
        // Проверяем, что слово содержит только кириллические буквы и длину ≥ 2
        return  !word.matches("^[а-яА-ЯёЁ][а-яА-ЯёЁ\\-']*$") || word.length() < 2;
    }

    private boolean isValidWord(String word) {
        return !isWrongWord(word);
    }

    private boolean isFunctionalPartOfSpeech(String wordInfo) {

        return FUNCTIONAL_POS.stream()
                .anyMatch(pos -> wordInfo.toUpperCase().contains(pos));
    }
}