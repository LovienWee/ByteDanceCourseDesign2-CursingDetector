package com.example.cursingdetector.profanity;

import com.example.cursingdetector.model.ProfanityHit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfanityStats {

    public int totalCount = 0;
    public Map<String, Integer> perWordCount = new HashMap<>();
    public List<ProfanityHit> hits = new ArrayList<>();

    public void addHit(String word, double startSec, double endSec, String text) {
        totalCount++;
        int old = perWordCount.containsKey(word) ? perWordCount.get(word) : 0;
        perWordCount.put(word, old + 1);
        hits.add(new ProfanityHit(word, startSec, endSec, text));
    }

    public String buildSummary() {
        if (totalCount == 0) {
            return "脏话统计：暂无脏话";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("总脏话次数：").append(totalCount).append("\n");
        sb.append("各类脏话统计：\n");
        for (Map.Entry<String, Integer> entry : perWordCount.entrySet()) {
            sb.append(entry.getKey()).append("：").append(entry.getValue()).append(" 次\n");
        }
        return sb.toString();
    }
}
