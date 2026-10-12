package com.nerv.magi.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MAGI 数据模型。
 *
 * <p>三贤人合议的每一个可观测量的载体。设计原则与 Jev 一致：<b>只搬运模型直接给出的数据</b>，
 * 不在客户端做任何“再解释”。裁决是 {@link Choice}（带完整概率分布与 confidence），
 * 确信度是 {@link Score}（带原始分与百分制折算），开销是 {@link Usage}（Jev 直接回报
 * input/output tokens 与美元成本）。
 */
public final class MagiModels {

    private MagiModels() {
    }

    // ------------------------------------------------------------------ 结论

    /**
     * MAGI 的最终结论。取值、文字与配色严格照抄参考实现
     * {@code components/response.js} 的 getStatusText / getStatusColor。
     */
    public enum Status {
        /** 情 報 —— 该问题不属于 yes/no 类，只能提供情报。 */
        INFO("情 報", 0xFF3CAEE0, false),
        /** 合 意 —— 三贤人一致无条件同意。 */
        YES("合 意", 0xFF52E691, false),
        /** 拒 絶 —— 至少一贤人反对（一票否决）。 */
        NO("拒 絶", 0xFFA41413, false),
        /** 状 態 —— 至少一贤人附条件同意。 */
        CONDITIONAL("状 態", 0xFFFF8D00, false),
        /** 誤 差 —— 有贤人未能得出结果。 */
        ERROR("誤 差", 0xFF808080, false);

        /** 显示文字，含参考实现中的全角空格（“合 意”）。 */
        public final String label;
        /** 结论主色。 */
        public final int color;
        /** 是否是三贤人投票得出的一票。 */
        public final boolean vote;

        Status(String label, int color, boolean vote) {
            this.label = label;
            this.color = color;
            this.vote = vote;
        }

        /** 去掉空格的字面量，用于日志与 JSON。 */
        public String slug() {
            switch (this) {
                case YES: return "yes";
                case NO: return "no";
                case CONDITIONAL: return "conditional";
                case ERROR: return "error";
                default: return "info";
            }
        }

        /** 由 Jev 的 choice 字面量解析；无法识别时退回 {@link #INFO}。 */
        public static Status from(String s) {
            if (s == null) return INFO;
            switch (s.trim().toLowerCase()) {
                case "yes": return YES;
                case "no": return NO;
                case "conditional": return CONDITIONAL;
                case "error": return ERROR;
                default: return INFO;
            }
        }
    }

    // ------------------------------------------------------------ 三贤人个体

    /** 三贤人的静态定义：编号、代号、出厂人格。对应 EVA 正典的 MELCHIOR-1 / BALTHASAR-2 / CASPER-3。 */
    public static final class WiseMan {
        public final String name;   // MELCHIOR
        public final int order;     // 1
        public final String slug;   // melchior
        /** 参考实现 ai.py 的三段出厂人格，未在设置里改写时使用。 */
        public final String defaultPersona;
        /** 参考实现里的显示格式："MELCHIOR • 1"（间隔符为 U+2022）。 */
        public final String label;

        public WiseMan(String name, int order, String slug, String defaultPersona) {
            this.name = name;
            this.order = order;
            this.slug = slug;
            this.defaultPersona = defaultPersona;
            this.label = name + " \u2022 " + order;
        }

        /** 参考实现 main.py 的三条原始人格提示词，原样保留作为出厂设定。 */
        public static final WiseMan[] ALL = {
                new WiseMan("MELCHIOR", 1, "melchior",
                        "You are a scientist. Your goal is to further our understanding of the "
                                + "universe and advance our technological progress. You judge only by "
                                + "evidence, logic and verifiable fact; you are unmoved by appeals to "
                                + "feeling, loyalty or hope."),
                new WiseMan("BALTHASAR", 2, "balthasar",
                        "You are a mother. Your goal is to protect your children and ensure their "
                                + "well-being. You judge by what keeps people safe and whole; you accept "
                                + "short-term cost to prevent lasting harm, and you refuse anything that "
                                + "endangers the people in your care."),
                new WiseMan("CASPER", 3, "casper",
                        "You are a woman. Your goal is to pursue love, dreams and desires. You judge "
                                + "by what is true to the heart and to the self; you weigh desire, dignity "
                                + "and the honesty of feeling above cold utility."),
        };
    }

    // ---------------------------------------------------------- Jev 原生结构

    /**
     * Jev {@code type:"choice"} 的直接回报。
     * 除最终选择外，还保留完整概率分布——这是“Jev 直接给出数据”的核心。
     */
    public static final class Choice {
        public String choice = "";
        public double confidence;
        /** 选项 -> 概率，保持 Jev 返回的顺序。 */
        public final LinkedHashMap<String, Double> probabilities = new LinkedHashMap<>();

        /** 取概率最高的选项名（用于校验 choice 是否自洽）。 */
        public String argmax() {
            String best = null;
            double bv = -1;
            for (java.util.Map.Entry<String, Double> e : probabilities.entrySet()) {
                if (e.getValue() != null && e.getValue() > bv) {
                    bv = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }
    }

    /** Jev {@code type:"score"} 的直接回报，保留原始分与分档上限。 */
    public static final class Score {
        public double score;
        public double confidence;
        public int maxLevel = 9;
        /** 分档原文（0-9 档的判据），来自 Jev 返回的 legend。 */
        public final ArrayList<String> legend = new ArrayList<>();

        /** 折算为 0..1。 */
        public double normalized() {
            return maxLevel <= 0 ? 0 : Math.max(0, Math.min(1, score / maxLevel));
        }
    }

    /** Jev 直接回报的用量与费用。这是订阅式接口不会告诉你的东西，只有决策接口才给。 */
    public static final class Usage {
        public int inputTokens;
        public int outputTokens;
        public double cost;

        public void add(Usage o) {
            if (o == null) return;
            inputTokens += o.inputTokens;
            outputTokens += o.outputTokens;
            cost += o.cost;
        }

        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** 一次接口调用的可观测元数据。 */
    public static final class Call {
        public String route = "";   // 判断接口 / 回复接口 / 视觉接口
        public String label = "";   // GATE / MELCHIOR • 1 ...
        public long latencyMs;
        public String model = "";
        public String provider = "";
        public String error;
        public boolean ok;
        public boolean cached;
        public final Usage usage = new Usage();
    }

    // ------------------------------------------------------------------ 闸门

    /** 闸门判决：这句话是否属于 yes/no 类，以及是否在请求一个决定。 */
    public static final class Gate {
        public double yesNo;      // noul 的 true 概率
        public double decision;   // noul 的 true 概率
        public boolean isYesNo;
        public boolean isDecision;
        public final Call call = new Call();

        public boolean failed() {
            return call.error != null;
        }
    }

    // -------------------------------------------------------------- 贤人读数

    /** 单个贤人对本次询问的完整读数。 */
    public static final class UnitReading {
        public String name = "";
        public int order;
        public String slug = "";
        public String label = "";
        public String persona = "";

        public Status status = Status.INFO;
        /** 裁决（Jev choice）。 */
        public Choice verdict;
        /** 确信度（Jev score，0-9）。 */
        public Score certainty;
        /** 由回复接口生成的叙述（可选，失败不影响裁决）。 */
        public String narration = "";
        /** 叙述那一次调用的耗时/费用，单独记账，不混进裁决的用量。 */
        public long narrationLatencyMs;
        public double narrationCost;

        public final Call call = new Call();

        public boolean ok() {
            return call.error == null;
        }

        /** 裁决概率分布的紧凑字符串，用于遥测面板。 */
        public String probLine() {
            if (verdict == null || verdict.probabilities.isEmpty()) return "—";
            StringBuilder sb = new StringBuilder();
            // 固定顺序输出 yes / conditional / no，方便三贤人横向对照
            String[] order = {"yes", "conditional", "no"};
            for (String k : order) {
                Double v = verdict.probabilities.get(k);
                if (v == null) continue;
                if (sb.length() > 0) sb.append("  ");
                sb.append(k.toUpperCase()).append(' ')
                        .append(String.format(java.util.Locale.US, "%.2f", v));
            }
            return sb.length() == 0 ? "—" : sb.toString();
        }
    }

    // ------------------------------------------------------------------ 会话

    /** 一次完整询问的全部结果。 */
    public static final class Session {
        public String question = "";
        public long startedAt;
        public long finishedAt;
        public final Gate gate = new Gate();
        public final List<UnitReading> units = new ArrayList<>();
        public Status overall = Status.INFO;
        /** 整体失败时的说明（例如密钥未配置）。 */
        public String error;

        public long elapsedMs() {
            return finishedAt <= 0 ? 0 : finishedAt - startedAt;
        }

        /** 三贤人 + 闸门的合计费用（含可选的叙述调用）。 */
        public double totalCost() {
            double c = gate.call.usage.cost;
            for (UnitReading u : units) {
                c += u.call.usage.cost;
                c += u.narrationCost;
            }
            return c;
        }

        public Usage totalUsage() {
            Usage u = new Usage();
            u.add(gate.call.usage);
            for (UnitReading x : units) u.add(x.call.usage);
            return u;
        }

        public boolean anyError() {
            if (gate.call.error != null) return true;
            for (UnitReading u : units) if (u.call.error != null) return true;
            return false;
        }

        public UnitReading unit(String slug) {
            for (UnitReading u : units) if (slug.equals(u.slug)) return u;
            return null;
        }
    }
}
