package com.nerv.magi.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

import java.util.HashMap;

/**
 * 视觉常量与字体装载。
 *
 * <p>配色<b>逐值照抄</b>参考实现 {@code assets/style.css} 与
 * {@code components/wise_man.js} / {@code response.js}，不做主观调整：
 *
 * <ul>
 *   <li>{@code #ff8d00} 主橙 —— 面板边框、贤人块底色、标题、状態</li>
 *   <li>{@code #140b02} 斜纹暗橙 —— 背景 45° 重复斜纹的第二色</li>
 *   <li>{@code #277547} 暗绿 —— 頁眉横线</li>
 *   <li>{@code #52e691} 荧光绿 —— 合意</li>
 *   <li>{@code #a41413} 血红 —— 拒絶</li>
 *   <li>{@code #3caee0} 情报蓝 —— 情報</li>
 *   <li>{@code #82cd68} 浅绿 —— 状態斜纹的第二色</li>
 * </ul>
 */
public final class Ui {

    private Ui() {
    }

    // -------------------------------------------------------------- 参考配色
    public static final int ORANGE = 0xFFFF8D00;
    public static final int STRIPE_DARK = 0xFF140B02;
    public static final int HEADER_GREEN = 0xFF277547;
    public static final int YES_GREEN = 0xFF52E691;
    public static final int NO_RED = 0xFFA41413;
    public static final int INFO_BLUE = 0xFF3CAEE0;
    public static final int COND_GREEN = 0xFF82CD68;
    public static final int BLACK = 0xFF000000;
    public static final int GRAY = 0xFF808080;

    /** 危险标识黄（工业警示斜纹），用于 EVA 风格的警示条。 */
    public static final int HAZARD = 0xFFE8C000;
    /** 次要文字：暗一档的橙，用于拥挤的遥测行。 */
    public static final int ORANGE_DIM = 0xFFB36300;

    // ------------------------------------------------------------------ 字体
    private static final HashMap<String, Typeface> FONT_CACHE = new HashMap<>();

    /**
     * 显示字体：FOT-Matisse Pro ExtraBold —— EVA 标题与 MAGI 面板使用的正体。
     * {@code assets/fonts/MatisseEB.otf} 约 9.8MB，装载一次后全程复用。
     */
    public static Typeface display(Context ctx) {
        return load(ctx, "MatisseEB", "fonts/MatisseEB.otf", Typeface.SERIF);
    }

    /**
     * 终端字体：参考实现用 {@code Lucida Console}；Android 上以系统 MONOSPACE 对齐
     * （Roboto Mono / Droid Sans Mono），字形气质一致。
     */
    public static Typeface mono(Context ctx) {
        return load(ctx, "#mono", null, Typeface.MONOSPACE);
    }

    private static synchronized Typeface load(Context ctx, String key, String asset, Typeface fallback) {
        Typeface cached = FONT_CACHE.get(key);
        if (cached != null) return cached;
        if (asset != null) {
            try {
                cached = Typeface.createFromAsset(ctx.getAssets(), asset);
            } catch (Throwable t) {
                cached = null;   // 字体缺失绝不能让整块面板画不出来
            }
        }
        if (cached == null) cached = fallback;
        FONT_CACHE.put(key, cached);
        return cached;
    }

    // ------------------------------------------------------------------ 尺寸
    public static float dp(Context ctx, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    public static float sp(Context ctx, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** 把不透明色按比例压暗，用于底色/分隔线的层次。 */
    public static int scale(int color, float f) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 带透明度的颜色。 */
    public static int alpha(int color, float f) {
        int a = (int) (((color >>> 24) & 0xFF) * f);
        return (clamp(a) << 24) | (color & 0x00FFFFFF);
    }
}
