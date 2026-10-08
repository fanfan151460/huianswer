package com.lhsg.HuiAnswer.rag.knowledge.keyWordUtil;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.common.Term;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ToKeyWord {

    /**
     * 除中文、字母、数字以外的一切：标点、空白、全角符号、emoji、零宽字符、控制符
     */
    private static final Pattern NOISE = Pattern.compile("[^\\p{IsHan}a-zA-Z0-9]+");

    /**
     * 检索无意义的高频词，按业务继续增删
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "与", "及", "或", "在", "是", "有", "为", "对",
            "从", "到", "把", "被", "让", "给", "着", "过", "吗", "呢", "吧",
            "可以", "如果", "这个", "那个", "哪些", "什么", "怎么", "如何", "为什么",
            "一个", "我们", "你们", "他们", "请问", "需要", "想要"
    );

    private ToKeyWord() {
    }

    /**
     * 索引侧：空格分隔，交给 to_tsvector('simple', ...) 切词
     */
    public static String toKeyWord(String text) {
        return String.join(" ", clean(text));
    }

    /**
     * 查询侧：| 分隔，交给 to_tsquery('simple', ...) 当 OR 解析
     */
    public static String toKeyQuestion(String question) {
        return String.join("|", clean(question));
    }

    /**
     * 三层清洗：字符归一化与噪声替换 -> HanLP 分词并按词性过滤 -> 停用词/小写/去重
     */
    private static List<String> clean(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 1. NFKC：全角转半角，兼容字符归一化（ＡＢＣ１２３ -> ABC123）
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // 2. 噪声替换为空格而不是删除，保留词的边界，避免把相邻的词粘成一个
        String stripped = NOISE.matcher(normalized).replaceAll(" ");

        // 3. HanLP 分词后按词性过滤
        Set<String> words = new LinkedHashSet<>();
        for (Term term : HanLP.segment(stripped)) {
            String word = term.word;
            if (word == null || word.isBlank() || isNoiseNature(term.nature)) {
                continue;
            }
            if (STOP_WORDS.contains(word)) {
                continue;
            }
            words.add(word.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(words);
    }

    /**
     * 靠 startsWith 和常量比较
     */
    private static boolean isNoiseNature(Nature nature) {
        if (nature == null) {
            return false;
        }
        return nature.startsWith("w")   // 全部标点子类：wkz wky wyz wyy wj ww wt wd wf wn wm ws wp wb wh
                || nature == Nature.x   // 非语素字
                || nature == Nature.e   // 叹词
                || nature == Nature.y   // 语气词
                || nature == Nature.o   // 拟声词
                || nature == Nature.u;  // 助词：的/了/着/过/之……
    }
}
