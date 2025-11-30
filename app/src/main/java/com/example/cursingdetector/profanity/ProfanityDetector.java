package com.example.cursingdetector.profanity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ProfanityDetector {

    // 简单词库，可以后续扩展
    private static final List<String> PROFANITY_WORDS = Arrays.asList(
            "fuck",
            "shit",
            "bitch",
            "asshole",
            "bastard",
            "damn",
            "hell"
    );

    public List<String> findProfanityWords(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        String lower = text.toLowerCase(Locale.US);

        for (String word : PROFANITY_WORDS) {
            if (lower.contains(word)) {
                result.add(word);
            }
        }
        return result;
    }

    public boolean hasProfanity(String text) {
        return !findProfanityWords(text).isEmpty();
    }
}
