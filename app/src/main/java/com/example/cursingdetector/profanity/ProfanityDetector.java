package com.example.cursingdetector.profanity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简单双语脏话检测：
 * - 使用正则，保证按完整单词匹配（不会把 hello 当成 hell）
 * - 一些派生词（fucking / fuckin）归并到同一个基础词
 */
public class ProfanityDetector {

    private static class Rule {
        String baseWord;      // 统计时显示的基础词，比如 "fuck"
        Pattern pattern;      // 用来匹配文本的正则

        Rule(String baseWord, String regex) {
            this.baseWord = baseWord;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public ProfanityDetector() {
        // 基础动词类
        rules.add(new Rule("fuck", "\\bfuck(ing|in)?\\b"));               // fuck / fucking / fuckin
        rules.add(new Rule("shit", "\\bshit(ty)?\\b"));                   // shit / shitty
        rules.add(new Rule("hell", "\\bhell\\b"));
        rules.add(new Rule("damn", "\\bdamn(ed)?\\b"));                   // damn / damned
        rules.add(new Rule("ass", "\\bass(hole)?s?\\b"));                 // ass / asshole / assholes
        rules.add(new Rule("bitch", "\\bbitch(es)?\\b"));                 // bitch / bitches
        rules.add(new Rule("bastard", "\\bbastard(s)?\\b"));
        rules.add(new Rule("dick", "\\bdick(s)?\\b"));
        rules.add(new Rule("piss", "\\bpiss(ed|ing)?\\b"));               // piss / pissed / pissing
        rules.add(new Rule("crap", "\\bcrap(ping|py)?\\b"));              // crap / crappy

        // 多词短语（统计时当成一个整体）
        rules.add(new Rule("son of a bitch", "\\bson of a bitch\\b"));
        rules.add(new Rule("motherfucker", "\\bmotherfuck(er|ers|ing)?\\b"));
        rules.add(new Rule("piece of shit", "\\bpiece of shit\\b"));

        // ==== 中文常见脏话 ====
        // 这里用短语，尽量避免单个「操」这种容易误伤普通词
        rules.add(new Rule("卧槽", "卧槽|我[艹操靠槽]|wo cao"));
        rules.add(new Rule("操你妈", "操你妈|草泥马"));
        rules.add(new Rule("妈的", "妈的|他妈的|你妈的"));
        rules.add(new Rule("傻逼", "傻逼|傻B|沙币|煞笔"));
        rules.add(new Rule("垃圾", "垃圾(队友)?"));        // 游戏里常见
        rules.add(new Rule("滚", "滚你妈|给我滚|滚犊子"));        // 只匹配比较重的说法
        rules.add(new Rule("臭", "臭\\s*傻逼|臭\\s*婊子"));
    }

    /**
     * 返回命中的基础脏话列表，比如 ["fuck", "fuck", "hell"]
     * 调用方负责按单词统计次数。
     */
    public List<String> findProfanityWords(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;

        for (Rule rule : rules) {
            Matcher m = rule.pattern.matcher(text);
            while (m.find()) {
                hits.add(rule.baseWord);
            }
        }
        return hits;
    }
}
