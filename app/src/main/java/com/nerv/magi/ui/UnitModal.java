package com.nerv.magi.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.nerv.magi.core.MagiModels;

import java.util.Locale;
import java.util.Map;

/**
 * 贤人读数弹窗：点中某个贤人块后，展开它的完整读数。
 *
 * <p>版式照抄参考实现 {@code .modal} / {@code .modal-header}（{@code components/modal.js}
 * + {@code assets/style.css}）：黑底、2dp 橙框，页眉是 45° 双色斜纹（{@code #000000} /
 * {@code #211200}，各 10px）加一条橙竖线隔出关闭格。
 *
 * <p>正文里的「概率」与「确信度」用条形图呈现——这是本 App 的看点：把 Jev 直接回报的
 * 概率分布与 0-9 分档确信度原样画出来，不做任何再解释。
 *
 * <p>全部使用 Android 原生控件（LinearLayout / ScrollView / TextView）与自绘 View，
 * 不依赖 AndroidX 或任何第三方库。
 */
public class UnitModal extends Dialog {

    /** 弹窗宽度占屏幕的比例。 */
    private static final float WIDTH_RATIO = 0.90f;
    /** 弹窗最大高度占屏幕的比例，超出则正文滚动。 */
    private static final float MAX_HEIGHT_RATIO = 0.80f;
    /** 概率/确信度条的宽度上限。 */
    private static final float BAR_MAX_W_DP = 120f;
    private static final float BAR_H_DP = 14f;
    /** 页眉高度。 */
    private static final float HEADER_H_DP = 42f;

    private final Activity activity;
    private MagiModels.Session session;
    private MagiModels.UnitReading unit;

    private LinearLayout root;
    private LinearLayout body;
    private int headerH;

    public UnitModal(Activity activity) {
        super(activity);
        this.activity = activity;
        setCanceledOnTouchOutside(true);
    }

    /**
     * 展示某个贤人的读数。
     *
     * @param session 当前会话（可为 null，届时只显示贤人自身的静态信息）
     * @param unit    要展示的贤人读数
     */
    public void show(MagiModels.Session session, MagiModels.UnitReading unit) {
        this.session = session;
        this.unit = unit != null ? unit : new MagiModels.UnitReading();
        build();
        if (isShowing()) dismiss();
        super.show();
        applyLayout();
    }

    // ------------------------------------------------------------------ 构建视图

    private void build() {
        final Context ctx = activity;
        headerH = Math.round(Ui.dp(ctx, HEADER_H_DP));

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Ui.BLACK);
        bg.setStroke(Math.round(Ui.dp(ctx, 2f)), Ui.ORANGE);   // 2dp 橙框
        root.setBackground(bg);

        // ---- 页眉条：斜纹底 + 橙色边框 + 标题/状态 + 「×」关闭格
        HeaderStrip strip = new HeaderStrip(ctx);
        strip.setContent(safe(unit.label), safe(unit.status == null ? null : unit.status.label),
                unit.status == null ? Ui.ORANGE : unit.status.color);
        strip.setOnClose(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });
        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerH));

        // ---- 正文滚动区
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(Ui.BLACK);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(Ui.dp(ctx, 14f));
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        fillBody();
    }

    /** 逐块填充正文内容。 */
    private void fillBody() {
        // 對話 / QUESTION
        addBlock(body, "對話 / QUESTION",
                monoText(session == null ? "—" : safe(session.question), 13f, Ui.ORANGE));

        // 人格 / PERSONA
        addBlock(body, "人格 / PERSONA", monoText(safe(unit.persona), 13f, Ui.ORANGE));

        // 裁决 / VERDICT
        String verdictText;
        if (unit.verdict == null) {
            verdictText = "—";
        } else {
            verdictText = String.format(Locale.US, "%s   %.1f%%",
                    safe(unit.verdict.choice), unit.verdict.confidence * 100.0);
        }
        addBlock(body, "裁决 / VERDICT", monoText(verdictText, 13f, Ui.ORANGE));

        // 概率 / PROBABILITIES —— 一条一行，右侧配比例条
        LinearLayout probBox = new LinearLayout(activity);
        probBox.setOrientation(LinearLayout.VERTICAL);
        boolean anyProb = false;
        if (unit.verdict != null && !unit.verdict.probabilities.isEmpty()) {
            for (Map.Entry<String, Double> e : unit.verdict.probabilities.entrySet()) {
                double v = e.getValue() == null ? 0.0 : e.getValue();
                double shown = v > 1.0 ? 1.0 : v;   // 防御：值域按 0..1 处理
                probBox.addView(barRow(safe(e.getKey()), String.format(Locale.US, "%.2f", v),
                        (float) shown));
                anyProb = true;
            }
        }
        if (!anyProb) {
            TextView none = monoText("—", 13f, Ui.ORANGE);
            probBox.addView(none);
        }
        addBlock(body, "概率 / PROBABILITIES", probBox);

        // 确信度 / CERTAINTY
        LinearLayout certBox = new LinearLayout(activity);
        certBox.setOrientation(LinearLayout.VERTICAL);
        if (unit.certainty != null) {
            double norm = unit.certainty.normalized();
            String head = String.format(Locale.US, "%.1f / %d   (%.1f%%)",
                    unit.certainty.score, unit.certainty.maxLevel, norm * 100.0);
            certBox.addView(monoText(head, 13f, Ui.ORANGE));
            certBox.addView(barRow(null, null, (float) norm));
        } else {
            certBox.addView(monoText("—", 13f, Ui.ORANGE));
        }
        addBlock(body, "确信度 / CERTAINTY", certBox);

        // 叙述 / NARRATION —— 为空则整块跳过
        if (unit.narration != null && unit.narration.trim().length() > 0) {
            addBlock(body, "叙述 / NARRATION", monoText(unit.narration, 13f, Ui.ORANGE));
        }

        // 遥测 / TELEMETRY
        final MagiModels.Call call = unit.call;
        StringBuilder t = new StringBuilder();
        t.append("route  : ").append(safe(call.route)).append('\n');
        t.append("model  : ").append(safe(call.model)).append('\n');
        t.append("latency: ").append(call.latencyMs).append(" ms");
        if (call.cached) t.append("  (cached)");
        t.append('\n');
        t.append("tokens : ").append(call.usage.inputTokens).append(" in / ")
                .append(call.usage.outputTokens).append(" out").append('\n');
        t.append(String.format(Locale.US, "cost   : $%.6f", call.usage.cost));
        addBlock(body, "遥测 / TELEMETRY", monoText(t.toString(), 13f, Ui.ORANGE));

        // 錯誤 / ERROR —— 仅在有错时出现
        if (call.error != null && call.error.length() > 0) {
            addBlock(body, "錯誤 / ERROR", monoText(call.error, 13f, Ui.NO_RED));
        }
    }

    /**
     * 构造「标签 + 比例条」一行。{@code label} 与 {@code value} 任一为 null 时左侧留空。
     *
     * @param fraction 0..1 的填充比例
     */
    private View barRow(String label, String value, float fraction) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String left = label == null ? "" : (value == null ? label : label + "   " + value);
        TextView tv = monoText(left, 13f, Ui.ORANGE);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        BarView bar = new BarView(activity);
        bar.setFraction(fraction);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Math.round(Ui.dp(activity, BAR_MAX_W_DP)), Math.round(Ui.dp(activity, BAR_H_DP)));
        blp.leftMargin = Math.round(Ui.dp(activity, 8f));
        row.addView(bar, blp);
        return row;
    }

    private void addBlock(LinearLayout parent, String caption, View value) {
        TextView cap = monoText(caption, 11f, Ui.ORANGE_DIM);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Math.round(Ui.dp(activity, 10f));
        parent.addView(cap, clp);

        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Math.round(Ui.dp(activity, 3f));
        parent.addView(value, vlp);
    }

    private TextView monoText(String s, float sizeSp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(Ui.mono(activity));
        t.setLineSpacing(Ui.dp(activity, 2f), 1f);
        return t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- 窗口尺寸

    /** 宽度取屏幕 90%；高度按内容自适应，但不超过屏幕 80%，超出交给 ScrollView。 */
    private void applyLayout() {
        Window win = getWindow();
        if (win == null) return;

        win.setBackgroundDrawable(new ColorDrawable(0xCC000000));   // 半透明黑遮罩
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams wlp = win.getAttributes();
        wlp.dimAmount = 0.6f;
        win.setAttributes(wlp);

        WindowManager wm = win.getWindowManager();
        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);

        int targetW = Math.round(size.x * WIDTH_RATIO);
        int maxH = Math.round(size.y * MAX_HEIGHT_RATIO);

        // 先量一次正文的自然高度，再决定弹窗高度（只测量，不改变已布局的视图）
        int pad = Math.round(Ui.dp(activity, 14f));
        int bodyW = Math.max(targetW - pad * 2, 1);
        body.measure(
                View.MeasureSpec.makeMeasureSpec(bodyW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int desired = headerH + body.getMeasuredHeight();
        int targetH = Math.max(headerH, Math.min(desired, maxH));

        win.setLayout(targetW, targetH);
    }

    // ------------------------------------------------------------------ 内部视图

    /**
     * 页眉条：45° 双色斜纹底 + 2dp 橙框 + 标题/状态 + 右侧「×」关闭格。
     * 参考 {@code .modal-header} / {@code .modal-title} / {@code .close}。
     */
    private static class HeaderStrip extends View {

        /** 斜纹两色：#000000 与 #211200，各 10dp（周期 20dp）。 */
        private static final int STRIPE_A = 0xFF000000;
        private static final int STRIPE_B = 0xFF211200;
        private static final float STRIPE_DP = 10f;

        private final Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

        private String title = "";
        private String statusText = "";
        private int statusColor = Ui.ORANGE;
        private Runnable onClose;

        HeaderStrip(Context c) {
            super(c);
        }

        void setContent(String title, String status, int color) {
            this.title = title == null ? "" : title;
            this.statusText = status == null ? "" : status;
            this.statusColor = color;
            invalidate();
        }

        void setOnClose(Runnable r) {
            this.onClose = r;
        }

        private float closeCellW() {
            return Ui.dp(getContext(), 44f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final Context ctx = getContext();
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 45° 双色斜纹
            int save = canvas.save();
            canvas.clipRect(0, 0, w, h);
            canvas.rotate(45f, w / 2f, h / 2f);
            float period = Ui.dp(ctx, STRIPE_DP * 2f);
            float half = period / 2f;
            float reach = (w + h) * 1.5f + period;
            fp.setStyle(Paint.Style.FILL);
            int band = 0;
            for (float y = h / 2f - reach; y < h / 2f + reach; y += half) {
                fp.setColor(band % 2 == 0 ? STRIPE_A : STRIPE_B);
                canvas.drawRect(w / 2f - reach, y, w / 2f + reach, y + half, fp);
                band++;
            }
            canvas.restoreToCount(save);

            // 2dp 橙框
            fp.setStyle(Paint.Style.STROKE);
            fp.setStrokeWidth(Ui.dp(ctx, 2f));
            fp.setColor(Ui.ORANGE);
            float inset = fp.getStrokeWidth() / 2f;
            canvas.drawRect(inset, inset, w - inset, h - inset, fp);

            // 关闭格竖线
            float closeLeft = w - closeCellW();
            canvas.drawLine(closeLeft, 0f, closeLeft, h, fp);

            float pad = Ui.dp(ctx, 8f);
            float midY = h / 2f;

            // 「×」
            tp.setTypeface(Ui.display(ctx));
            tp.setFakeBoldText(true);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(Ui.sp(ctx, 20f));
            tp.setColor(Ui.ORANGE);
            Paint.FontMetrics fm = tp.getFontMetrics();
            canvas.drawText("×", (closeLeft + w) / 2f, midY - (fm.ascent + fm.descent) / 2f, tp);

            // 标题（左对齐，display 粗体橙 20sp）
            tp.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(title, pad, midY - (fm.ascent + fm.descent) / 2f, tp);

            // 状态（右对齐，紧贴竖线，用状态色）
            if (statusText.length() > 0) {
                tp.setTextSize(Ui.sp(ctx, 14f));
                tp.setTextAlign(Paint.Align.RIGHT);
                tp.setColor(statusColor);
                Paint.FontMetrics fm2 = tp.getFontMetrics();
                canvas.drawText(statusText, closeLeft - pad,
                        midY - (fm2.ascent + fm2.descent) / 2f, tp);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_UP:
                    if (e.getX() >= getWidth() - closeCellW() && onClose != null) {
                        onClose.run();
                    }
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }

    /**
     * 比例条：暗橙轨道 + 橙色实心填充。轨道宽 120dp，填充宽度与数值成正比。
     */
    private static class BarView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;

        BarView(Context c) {
            super(c);
        }

        void setFraction(float f) {
            this.fraction = f < 0f ? 0f : (f > 1f ? 1f : f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 轨道
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 1f));
            p.setColor(Ui.alpha(Ui.ORANGE, 0.45f));
            canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, p);

            // 填充
            float fillW = (w - 2f) * fraction;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.ORANGE);
            canvas.drawRect(1f, 1f, 1f + fillW, h - 1f, p);
        }
    }
}
