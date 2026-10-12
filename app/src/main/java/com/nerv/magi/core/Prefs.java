package com.nerv.magi.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.nerv.magi.core.MagiModels.WiseMan;

/**
 * 应用私有配置存储。
 *
 * <p>结构照搬参考实现 jev-chat-jarvis 的 {@code core/Prefs.kt}：同样是
 * <b>判断 / 回复 / 视觉三路分离</b>，每路各有 base / key / model，密钥留空时按
 * {@code 视觉 → 回复 → 判断} 顺序逐级回退，因此只填一把密钥也能跑。
 *
 * <p>本作在此之上增加两项 MAGI 专属配置：
 * <ul>
 *   <li><b>三个人格</b>——三贤人的提示词可分别改写，出厂值为 EVA 正典设定；</li>
 *   <li><b>遥测开关</b>——是否显示 Jev 回报的 tokens / 费用 / 概率分布。</li>
 * </ul>
 *
 * <p>密钥处理与参考实现一致：只存进 app 私有 SharedPreferences（非 world-readable、
 * 不进日志、不进版本库），日志里只允许出现密钥<b>长度</b>。
 */
public class Prefs {

    /** 真正的那一份配置。任何其它名字的实例都是临时件（连通性测试等）。 */
    public static final String PREFS_MAIN = "magi_eva";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this(ctx, PREFS_MAIN);
    }

    public Prefs(Context ctx, String name) {
        this.sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 判断路

    /** "openrouter" | "bocha" | "typesafe" | "vercel" | "zen" | "custom"。 */
    public String judgeProvider() {
        return str(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER);
    }

    public void setJudgeProvider(String v) {
        put(K_JUDGE_PROVIDER, v == null ? "" : v.trim());
    }

    public String judgeBaseUrl() {
        return str(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER);
    }

    public void setJudgeBaseUrl(String v) {
        put(K_JUDGE_BASE, v == null ? "" : v.trim());
    }

    public String judgeKey() {
        return str(K_JUDGE_KEY, "");
    }

    public void setJudgeKey(String v) {
        put(K_JUDGE_KEY, v == null ? "" : v.trim());
    }

    public String judgeModel() {
        return str(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER);
    }

    public void setJudgeModel(String v) {
        put(K_JUDGE_MODEL, v == null ? "" : v.trim());
    }

    // ---------------------------------------------------------------- 回复路

    /** OpenAI 兼容 base，写到并包含 `/v1`。 */
    public String replyBaseUrl() {
        return str(K_REPLY_BASE, DEFAULT_REPLY_BASE);
    }

    public void setReplyBaseUrl(String v) {
        put(K_REPLY_BASE, v == null ? "" : v.trim());
    }

    public String replyKey() {
        return str(K_REPLY_KEY, "");
    }

    public void setReplyKey(String v) {
        put(K_REPLY_KEY, v == null ? "" : v.trim());
    }

    public String replyModel() {
        return str(K_REPLY_MODEL, DEFAULT_REPLY_MODEL);
    }

    public void setReplyModel(String v) {
        put(K_REPLY_MODEL, v == null ? "" : v.trim());
    }

    // ---------------------------------------------------------------- 视觉路

    public String visionBaseUrl() {
        return str(K_VISION_BASE, DEFAULT_VISION_BASE);
    }

    public void setVisionBaseUrl(String v) {
        put(K_VISION_BASE, v == null ? "" : v.trim());
    }

    public String visionKey() {
        return str(K_VISION_KEY, "");
    }

    public void setVisionKey(String v) {
        put(K_VISION_KEY, v == null ? "" : v.trim());
    }

    public String visionModel() {
        return str(K_VISION_MODEL, DEFAULT_VISION_MODEL);
    }

    public void setVisionModel(String v) {
        put(K_VISION_MODEL, v == null ? "" : v.trim());
    }

    // -------------------------------------------------------------- 三个人格

    /** 取三贤人的有效人格：未改写则回退出厂设定。 */
    public String persona(WiseMan w) {
        String s = str("persona_" + w.slug, "");
        return s.trim().isEmpty() ? w.defaultPersona : s;
    }

    public void setPersona(String slug, String text) {
        put("persona_" + slug, text == null ? "" : text.trim());
    }

    /** 是否已把该贤人改成过自定义人格。 */
    public boolean personaOverridden(String slug) {
        return !str("persona_" + slug, "").trim().isEmpty();
    }

    /** 把三个人格全部恢复出厂。 */
    public void resetPersonas() {
        SharedPreferences.Editor e = sp.edit();
        for (WiseMan w : WiseMan.ALL) e.remove("persona_" + w.slug);
        e.apply();
    }

    // ------------------------------------------------------------------ 行为

    /** 是否显示遥测（tokens / 费用 / 概率分布）。 */
    public boolean telemetry() {
        return sp.getBoolean(K_TELEMETRY, true);
    }

    public void setTelemetry(boolean v) {
        sp.edit().putBoolean(K_TELEMETRY, v).apply();
    }

    /** 是否在等待期间让面板闪烁。 */
    public boolean flicker() {
        return sp.getBoolean(K_FLICKER, true);
    }

    public void setFlicker(boolean v) {
        sp.edit().putBoolean(K_FLICKER, v).apply();
    }

    /**
     * 是否让三贤人各自额外生成一段「叙述」。
     * 默认关闭：叙述走生成路，会把一次询问的耗时与费用翻倍，
     * 而裁决本身并不需要它。开启后失败也不影响裁决。
     */
    public boolean narration() {
        return sp.getBoolean(K_NARRATION, false);
    }

    public void setNarration(boolean v) {
        sp.edit().putBoolean(K_NARRATION, v).apply();
    }

    // ------------------------------------------------------------------ 端点

    /** 密钥是否就绪。判断路是唯一必须配置的一路。 */
    public boolean hasKey() {
        return !judgeKey().isEmpty();
    }

    /** 回复路有效密钥：留空回退判断路。 */
    public String effectiveReplyKey() {
        String k = replyKey();
        return k.isEmpty() ? judgeKey() : k;
    }

    /** 视觉路有效密钥：留空回退回复路，再回退判断路。 */
    public String effectiveVisionKey() {
        String k = visionKey();
        return k.isEmpty() ? effectiveReplyKey() : k;
    }

    /**
     * 判断路的完整 POST 地址。
     *
     * <p>与参考实现 {@code Prefs.judgeEndpoint()} 逐分支一致：TypeSafe 协议的几家
     * （Bocha / TypeSafe / Vercel / Zen）都挂在 {@code /v1/systemone}；OpenRouter
     * 走 {@code /alpha/decisions}；custom 由用户自己填完整 URL。
     */
    public String judgeEndpoint() {
        String base = judgeBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String p = judgeProvider();
        if (PROVIDER_CUSTOM.equals(p)) return judgeBaseUrl().trim();
        if (PROVIDER_BOCHA.equals(p) || PROVIDER_TYPESAFE.equals(p)
                || PROVIDER_VERCEL.equals(p) || PROVIDER_ZEN.equals(p)) {
            return base + "/v1/systemone";
        }
        return base + "/alpha/decisions";
    }

    /** 回复路的完整 POST 地址（OpenAI 兼容）。 */
    public String replyEndpoint() {
        return stripSlash(replyBaseUrl()) + "/chat/completions";
    }

    /** 视觉路的完整 POST 地址。 */
    public String visionEndpoint() {
        String b = visionBaseUrl().trim();
        if (b.isEmpty()) b = DEFAULT_VISION_BASE;
        return stripSlash(b) + "/chat/completions";
    }

    /** 判断路的显示名，用于遥测面板。 */
    public String judgeProviderLabel() {
        String p = judgeProvider();
        if (PROVIDER_BOCHA.equals(p)) return "博查 Jev";
        if (PROVIDER_TYPESAFE.equals(p)) return "TypeSafe";
        if (PROVIDER_VERCEL.equals(p)) return "Vercel AI Gateway";
        if (PROVIDER_ZEN.equals(p)) return "OpenCode Zen";
        if (PROVIDER_CUSTOM.equals(p)) return "自定义";
        return "OpenRouter";
    }

    /** 换供应商时把 base / model 一并改成该家的默认值。 */
    public void applyProviderDefaults(String provider) {
        setJudgeProvider(provider);
        if (PROVIDER_BOCHA.equals(provider)) {
            setJudgeBaseUrl(DEFAULT_JUDGE_BASE_BOCHA);
            setJudgeModel(DEFAULT_JUDGE_MODEL_BOCHA);
        } else if (PROVIDER_TYPESAFE.equals(provider)) {
            setJudgeBaseUrl(DEFAULT_JUDGE_BASE_TYPESAFE);
            setJudgeModel(DEFAULT_JUDGE_MODEL_TYPESAFE);
        } else if (PROVIDER_VERCEL.equals(provider)) {
            setJudgeBaseUrl(DEFAULT_JUDGE_BASE_VERCEL);
            setJudgeModel(DEFAULT_JUDGE_MODEL_VERCEL);
        } else if (PROVIDER_ZEN.equals(provider)) {
            setJudgeBaseUrl(DEFAULT_JUDGE_BASE_ZEN);
            setJudgeModel(DEFAULT_JUDGE_MODEL_ZEN);
        } else if (PROVIDER_OPENROUTER.equals(provider)) {
            setJudgeBaseUrl(DEFAULT_JUDGE_BASE_OPENROUTER);
            setJudgeModel(DEFAULT_JUDGE_MODEL_OPENROUTER);
        }
        // custom 不动 base / model，交给用户填
    }

    // ---------------------------------------------------------------- 内部件

    private static String stripSlash(String s) {
        String b = s == null ? "" : s.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private String str(String k, String def) {
        String v = sp.getString(k, def);
        return v == null ? def : v;
    }

    private void put(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    // ---------------------------------------------------------------- 常量表

    private static final String K_JUDGE_PROVIDER = "judge_provider";
    private static final String K_JUDGE_BASE = "judge_base_url";
    private static final String K_JUDGE_KEY = "judge_key";
    private static final String K_JUDGE_MODEL = "judge_model";
    private static final String K_REPLY_BASE = "reply_base_url";
    private static final String K_REPLY_KEY = "reply_key";
    private static final String K_REPLY_MODEL = "reply_model";
    private static final String K_VISION_BASE = "vision_base_url";
    private static final String K_VISION_KEY = "vision_key";
    private static final String K_VISION_MODEL = "vision_model";
    private static final String K_TELEMETRY = "telemetry";
    private static final String K_FLICKER = "flicker";
    private static final String K_NARRATION = "narration";

    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_BOCHA = "bocha";
    public static final String PROVIDER_TYPESAFE = "typesafe";
    public static final String PROVIDER_VERCEL = "vercel";
    public static final String PROVIDER_ZEN = "zen";
    public static final String PROVIDER_CUSTOM = "custom";

    /** 供应商显示名，顺序即设置页里的顺序（OpenRouter 在首位，与参考实现一致）。 */
    public static final String[][] PROVIDERS = {
            {PROVIDER_OPENROUTER, "OpenRouter"},
            {PROVIDER_BOCHA, "博查 Jev"},
            {PROVIDER_TYPESAFE, "TypeSafe 直连"},
            {PROVIDER_VERCEL, "Vercel AI Gateway"},
            {PROVIDER_ZEN, "OpenCode Zen"},
            {PROVIDER_CUSTOM, "自定义"},
    };

    // 判断路预设——与参考实现 core/Prefs.kt 同值
    public static final String DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api";
    public static final String DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13";
    public static final String DEFAULT_JUDGE_BASE_BOCHA = "https://jev.bocha.cn";
    public static final String DEFAULT_JUDGE_MODEL_BOCHA = "bocha-jev-v1";
    public static final String DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai";
    public static final String DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest";
    public static final String DEFAULT_JUDGE_BASE_VERCEL = "https://ai-gateway.vercel.sh/typesafe";
    public static final String DEFAULT_JUDGE_MODEL_VERCEL = "typesafe-ai/jev";
    public static final String DEFAULT_JUDGE_BASE_ZEN = "https://opencode.ai/zen";
    public static final String DEFAULT_JUDGE_MODEL_ZEN = "jev-1.13";

    // 回复路预设
    public static final String DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1";

    // 视觉路预设
    public static final String DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct";
}
