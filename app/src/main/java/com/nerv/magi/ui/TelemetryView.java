package com.nerv.magi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.nerv.magi.core.MagiLogic;
import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.MagiModels.Session;
import com.nerv.magi.core.MagiModels.Status;
import com.nerv.magi.core.MagiModels.UnitReading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 遥测面板 —— 把 Jev <b>直接给出的数据</b>摆出来。
 *
 * <p>这是本作与「让语言模型自由作答、再回头分类」那类实现最关键的区别所在：
 * 决策模型一次请求就回报了完整概率分布、confidence、确信度原始分，以及自己这次调用的
 * token 数与美元成本。这些数字没有任何一项是客户端算出来的，全部照实显示。
 *
 * <p>面板自绘在 {@link Canvas} 上：每个贤人一段，先一行汇总，再逐条画出概率条，
 * 最后画确信度条。条长即数值，不做任何平滑或美化。
 */
public class TelemetryView extends View {

    private Session session;
    private String providerLine = "";

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private float pad, lineH, barH, labelW, barW;

    /** 一次布局走查产生的绘制指令，onMeasure 与 onDraw 共用同一份，保证高度算得准。 */
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        static final int TEXT = 0;
        static final int BAR = 1;
        static final int RULE = 2;
        static final int GAP = 3;

        int kind;
        String a = "";       // 左侧文字 / 标题
        String b = "";       // 右侧文字
        double value;        // 条形比例
        int color;
        float h;
    }

    public TelemetryView(Context c) {
        super(c);
        init();
    }

    private void init() {
        pad = Ui.dp(getContext(), 10);
        lineH = Ui.dp(getContext(), 17);
        barH = Ui.dp(getContext(), 7);
        setBackgroundColor(Ui.BLACK);

        text.setTypeface(Ui.mono(getContext()));
        text.setTextSize(Ui.dp(getContext(), 11));
        bar.setStyle(Paint.Style.FILL);
    }

    /** 绑定一次会话；{@code null} 表示待机。 */
    public void bind(Session s) {
        this.session = s;
        rebuild();
        requestLayout();
        invalidate();
    }

    /** 顶部一行：供应商 / 模型 / 地址。 */
    public void setProviderLine(String s) {
        this.providerLine = s == null ? "" : s;
        rebuild();
        requestLayout();
        invalidate();
    }

    // ------------------------------------------------------------ 行模型构建

    private void rebuild() {
        rows.clear();
        Context ctx = getContext();
        Typeface mono = Ui.mono(ctx);
        text.setTypeface(mono);

        add(Row.RULE, "", "", 0, Ui.ORANGE, Ui.dp(ctx, 9));

        if (providerLine != null && !providerLine.isEmpty()) {
            add(Row.TEXT, providerLine, "", 0, Ui.ORANGE_DIM, lineH);
            add(Row.RULE, "", "", 0, Ui.scale(Ui.ORANGE, 0.45f), Ui.dp(ctx, 7));
        }

        if (session == null) {
            add(Row.TEXT, "等待詢問 …", "", 0, Ui.ORANGE_DIM, lineH);
            add(Row.TEXT, "在上方輸入命題，按「執 行」提交三賢人合議。", "", 0, Ui.ORANGE_DIM, lineH);
            return;
        }

        // ---- 闸门
        MagiModels.Gate g = session.gate;
        add(Row.TEXT, "GATE  " + (g.failed() ? "失敗" : "PASS"),
                "YES/NO " + f2(g.yesNo) + "   DECISION " + f2(g.decision)
                        + "   " + g.call.latencyMs + "ms",
                0, g.failed() ? Ui.NO_RED : Ui.ORANGE, lineH);
        if (g.failed()) {
            add(Row.TEXT, "      " + g.call.error, "", 0, Ui.NO_RED, lineH);
        }
        add(Row.RULE, "", "", 0, Ui.scale(Ui.ORANGE, 0.45f), Ui.dp(ctx, 8));

        // ---- 每个贤人
        for (UnitReading u : session.units) {
            Status st = u.status;
            String left = u.label + "  " + st.label;
            String right = u.call.latencyMs + "ms"
                    + "   " + u.call.usage.totalTokens() + "tok"
                    + "   $" + f6(u.call.usage.cost);
            add(Row.TEXT, left, right, 0, st.color, lineH);

            if (!u.ok()) {
                add(Row.TEXT, "  ✕ " + u.call.error, "", 0, Ui.NO_RED, lineH);
                add(Row.GAP, "", "", 0, 0, Ui.dp(ctx, 6));
                continue;
            }

            if (u.verdict != null) {
                add(Row.TEXT, "  裁決 " + u.verdict.choice.toUpperCase()
                                + "   把握度 " + pct(u.verdict.confidence),
                        "", 0, Ui.ORANGE_DIM, lineH);
                LinkedHashMap<String, Double> probs = u.verdict.probabilities;
                if (!probs.isEmpty()) {
                    // 固定 yes / conditional / no 的顺序，方便三贤人横向对照
                    String[] order = {"yes", "conditional", "no"};
                    for (String k : order) {
                        Double v = probs.get(k);
                        if (v == null) continue;
                        add(Row.BAR, k.toUpperCase(), f2(v), v, colorForVerdict(k), barH);
                    }
                    // 供应商若返回了别的选项，也照实画出来
                    for (Map.Entry<String, Double> e : probs.entrySet()) {
                        if ("yes".equals(e.getKey()) || "no".equals(e.getKey())
                                || "conditional".equals(e.getKey())) continue;
                        add(Row.BAR, e.getKey().toUpperCase(), f2(e.getValue()),
                                e.getValue() == null ? 0 : e.getValue(), Ui.ORANGE_DIM, barH);
                    }
                }
            }

            if (u.certainty != null) {
                add(Row.BAR, "確信度", String.format(Locale.US, "%.2f", u.certainty.score)
                                + "/" + u.certainty.maxLevel,
                        u.certainty.normalized(), Ui.HEADER_GREEN, barH);
            }

            if (u.narration != null && !u.narration.isEmpty()) {
                add(Row.TEXT, "  判詞：" + u.narration, "", 0, Ui.ORANGE_DIM, lineH);
            }

            add(Row.GAP, "", "", 0, 0, Ui.dp(ctx, 8));
        }

        // ---- 结论
        add(Row.RULE, "", "", 0, Ui.ORANGE, Ui.dp(ctx, 9));
        add(Row.TEXT, "合議 " + session.overall.label,
                MagiLogic.explain(session.overall), 0, session.overall.color, lineH * 1.5f);
        add(Row.TEXT, "總計",
                session.elapsedMs() + "ms   $" + f6(session.totalCost())
                        + "   " + session.totalUsage().totalTokens() + " tokens",
                0, Ui.ORANGE, lineH);
        add(Row.TEXT, "      三賢人並行發出，故總耗時 ≈ 最慢的一路，而非三者相加。",
                "", 0, Ui.ORANGE_DIM, lineH);

        if (session.error != null && !session.error.isEmpty()) {
            add(Row.RULE, "", "", 0, Ui.NO_RED, Ui.dp(ctx, 7));
            add(Row.TEXT, "錯誤 " + session.error, "", 0, Ui.NO_RED, lineH);
        }
    }

    private void add(int kind, String a, String b, double value, int color, float h) {
        Row row = new Row();
        row.kind = kind;
        row.a = a == null ? "" : a;
        row.b = b == null ? "" : b;
        row.value = value;
        row.color = color;
        row.h = h;
        rows.add(row);
    }

    private static int colorForVerdict(String key) {
        if ("yes".equals(key)) return Ui.YES_GREEN;
        if ("no".equals(key)) return Ui.NO_RED;
        if ("conditional".equals(key)) return Ui.ORANGE;
        return Ui.INFO_BLUE;
    }

    // ---------------------------------------------------------------- 测量/绘制

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float total = pad * 2;
        for (Row row : rows) {
            total += row.h;
            if (row.kind == Row.BAR) total += Ui.dp(getContext(), 4);
        }
        int h = Math.max((int) total, (int) Ui.dp(getContext(), 60));
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            h = MeasureSpec.getSize(heightSpec);
        }
        setMeasuredDimension(Math.max(w, 1), h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        labelW = Math.min(w * 0.34f, Ui.dp(getContext(), 132));
        barW = w - pad * 2 - labelW - Ui.dp(getContext(), 58);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float y = pad;
        float w = getWidth();
        if (barW <= 0) barW = Math.max(w - pad * 2 - labelW - Ui.dp(getContext(), 58), 10);

        float valW = Ui.dp(getContext(), 52);

        for (Row row : rows) {
            switch (row.kind) {
                case Row.RULE:
                    bar.setColor(row.color);
                    canvas.drawRect(pad, y, w - pad, y + Ui.dp(getContext(), 1), bar);
                    y += row.h;
                    break;

                case Row.GAP:
                    y += row.h;
                    break;

                case Row.BAR: {
                    text.setColor(Ui.ORANGE);
                    canvas.drawText(clip(row.a, labelW), pad, y + lineH * 0.78f, text);

                    float bx = pad + labelW;
                    // 底槽
                    bar.setColor(Ui.scale(Ui.ORANGE, 0.22f));
                    r.set(bx, y + lineH * 0.28f, bx + barW, y + lineH * 0.28f + row.h);
                    canvas.drawRect(r, bar);
                    // 实条：长度即数值
                    bar.setColor(row.color);
                    r.set(bx, y + lineH * 0.28f, bx + (float) (barW * clamp01(row.value)),
                            y + lineH * 0.28f + row.h);
                    canvas.drawRect(r, bar);

                    text.setColor(Ui.ORANGE);
                    canvas.drawText(clip(row.b, valW), w - pad - valW, y + lineH * 0.78f, text);
                    y += row.h + Ui.dp(getContext(), 4);
                    break;
                }

                case Row.TEXT:
                default: {
                    text.setColor(row.color);
                    canvas.drawText(clip(row.a, w - pad * 2 - Ui.dp(getContext(), 4)),
                            pad, y + lineH * 0.78f, text);
                    if (!row.b.isEmpty()) {
                        float bw = text.measureText(row.b);
                        float bx = w - pad - bw;
                        float ax = pad + text.measureText(row.a) + Ui.dp(getContext(), 10);
                        text.setColor(Ui.alpha(row.color, 0.75f));
                        canvas.drawText(row.b, Math.max(bx, ax), y + lineH * 0.78f, text);
                    }
                    y += row.h;
                    break;
                }
            }
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 超宽截断，避免文字压出面板。 */
    private String clip(String s, float maxW) {
        if (s == null) return "";
        if (text.measureText(s) <= maxW) return s;
        String ell = "…";
        int n = s.length();
        while (n > 0 && text.measureText(s.substring(0, n) + ell) > maxW) n--;
        return s.substring(0, n) + ell;
    }

    private static String f2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String f6(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%.1f%%", v * 100);
    }
}
