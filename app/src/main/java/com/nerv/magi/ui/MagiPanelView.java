package com.nerv.magi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.nerv.magi.core.MagiModels;

/**
 * MAGI 面板：三贤人合议的全部可视化，整个 App 的中心画面。
 *
 * <p>几何布局逐值照抄参考实现 {@code assets/style.css} 里 {@code .magi} 的 CSS Grid：
 * 把「边框内缩 20px + 2fr/0.5fr/1fr/0.5fr/2fr 网格」翻译成画布坐标。所有比例都相对
 * 「内容框」（内容框 = 整个 View 内缩 20dp，宽 cw 高 ch）：
 *
 * <pre>
 *   列 LX[0..5] = 0, 0.33333, 0.41667, 0.58333, 0.66667, 1     （× cw）
 *   行 RY[0..4] = 0, 0.25,    0.5,     0.625,   1                 （× ch）
 * </pre>
 *
 * <p>三个贤人块是带缺口的互锁多边形（参考实现的 {@code clip-path}），缺口让它们像齿轮
 * 一样咬合。每块都用同一条 {@link Path} 先填充状态色、再描 2dp 橙边——参考实现是靠
 * 「外层橙底 + 内层同形裁剪」的 2px padding 做出这个描边的，这里直接描边等价。
 *
 * <p>状态色与参考实现 {@code components/wise_man.js#getColor} 一一对应：
 * yes→#52e691、no→#a41413、info→#3caee0、error→black、
 * conditional→56° 双色绿斜纹（#52e691 30dp / #82cd68 30dp）。
 *
 * <p>注意：这些区域坐标严格遵循本项目给定的区域表，其中 {@code status / casper /
 * balthasar / melchior} 的边线与参考 CSS 的朴素读法略有出入；{@link #buildRegions()}
 * 是唯一一处定义区域边线的地方，若要改回 CSS 原值只需改这一个方法。
 */
public class MagiPanelView extends View {

    // ------------------------------------------------------------------ 几何常量

    /** 外框内缩：参考 {@code .magi} 网格首末的 20px 行列。 */
    private static final float INSET_DP = 20f;
    /** 列比例（× cw），对应 grid-template-columns 的 2fr / 0.5fr / 1fr / 0.5fr / 2fr。 */
    private static final float[] COL_FRACS = {0f, 0.33333f, 0.41667f, 0.58333f, 0.66667f, 1f};
    /** 行比例（× ch），对应 grid-template-rows 的 2fr / 2fr / 1fr / 3fr。 */
    private static final float[] ROW_FRACS = {0f, 0.25f, 0.5f, 0.625f, 1f};

    /** MELCHIOR 的缺口多边形（相对自身矩形的比例），对应 clip-path:polygon(35% 0,100% 0,100% 100%,0 100%,0 44%)。 */
    private static final float[] MELCHIOR_POLY = {0.35f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 0.44f};
    /** BALTHASAR 的六边形，对应 clip-path:polygon(0 0,100% 0,100% 80%,75% 100%,25% 100%,0 80%)。 */
    private static final float[] BALTHASAR_POLY = {0f, 0f, 1f, 0f, 1f, 0.8f, 0.75f, 1f, 0.25f, 1f, 0f, 0.8f};
    /** CASPER 的缺口多边形，对应 clip-path:polygon(0 0,65% 0,100% 44%,100% 100%,0 100%)。 */
    private static final float[] CASPER_POLY = {0f, 0f, 0.65f, 0f, 1f, 0.44f, 1f, 1f, 0f, 1f};

    /** 连接杆高度。参考 {@code .connection { height:10px }}。 */
    private static final float CONN_HEIGHT_DP = 10f;
    /** 连接杆两端外延：参考 {@code .connection { margin:-10% }}（各 10% 容器宽）。 */
    private static final float CONN_MARGIN = 0.10f;
    /** 连接杆旋转角：casper↔balthasar −54°，balthasar↔melchior +54°。 */
    private static final float CONN_ROT_CB = -54f;
    private static final float CONN_ROT_BM = 54f;

    /** 页眉横线高度与上下间距。参考 {@code .header>hr { height:4px; margin:2px }}。 */
    private static final float RULE_HEIGHT_DP = 4f;
    private static final float RULE_MARGIN_DP = 2f;
    /** 页眉文字的水平拉伸：参考 {@code transform:scaleX(2)}，这里取 1.6 近似（中文字形更宽）。 */
    private static final float HEADER_SCALE_X = 1.6f;

    /** 闪烁周期。参考 {@code @keyframes flicker-animation 0.25s step-end infinite}。 */
    private static final long FLICKER_PERIOD_MS = 250L;

    // -------------------------------------------------------------------- 状态字段

    private int w;
    private int h;
    private float X0, X1, Y0, Y1, cw, ch;
    private final float[] LX = new float[6];
    private final float[] RY = new float[5];

    /** 各区域矩形（内容框坐标）。 */
    private final RectF headerL = new RectF();
    private final RectF headerR = new RectF();
    private final RectF statusR = new RectF();
    private final RectF responseR = new RectF();
    private final RectF titleR = new RectF();
    private final RectF casperR = new RectF();
    private final RectF balthasarR = new RectF();
    private final RectF melchiorR = new RectF();
    private final RectF connCBR = new RectF();
    private final RectF connCMR = new RectF();
    private final RectF connBMR = new RectF();

    private Path casperPath;
    private Path balthasarPath;
    private Path melchiorPath;
    private Region casperRegion;
    private Region balthasarRegion;
    private Region melchiorRegion;

    /** 当前会话；null 表示待机（三块均为情報蓝）。 */
    private MagiModels.Session session;
    private boolean busy;
    /** 闪烁「灭」帧标记：为 true 时把所有前景（橙）与填充都画成黑。 */
    private boolean flickerOn;

    private OnUnitTapListener tapListener;

    // 画布资源：全部复用，避免 onDraw 里分配对象
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 闪烁定时器。用 View 自带的 postDelayed/removeCallbacks，随 View 生命周期回收，不会泄漏。 */
    private final Runnable flickerTask = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            flickerOn = !flickerOn;
            invalidate();
            postDelayed(this, FLICKER_PERIOD_MS);
        }
    };

    // ------------------------------------------------------------------ 对外接口

    /** 贤人块点击回调，slug 为 "melchior" / "balthasar" / "casper"。 */
    public interface OnUnitTapListener {
        void onUnitTap(String slug);
    }

    public MagiPanelView(Context c) {
        super(c);
        init();
    }

    public MagiPanelView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        p.setStyle(Paint.Style.FILL);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnUnitTapListener(OnUnitTapListener l) {
        this.tapListener = l;
    }

    /** 绑定一次合议结果；传 null 回到待机态。 */
    public void bind(MagiModels.Session session) {
        this.session = session;
        invalidate();
    }

    /** 本地重绘（供外部在字体/尺寸变化后调用）。 */
    public void refresh() {
        invalidate();
    }

    public boolean isBusy() {
        return busy;
    }

    /**
     * 合议进行中：整块面板按参考实现的 flicker 关键帧闪黑。
     *
     * @param busy true 启动闪烁定时器，false 停止并重绘正常帧
     */
    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        removeCallbacks(flickerTask);
        flickerOn = false;
        if (busy) {
            postDelayed(flickerTask, FLICKER_PERIOD_MS);
        }
        invalidate();
    }

    /**
     * 命中测试：返回被点中的贤人 slug，都没中返回 null。
     * 三块互相咬合，命中按「后画的在上」的顺序（casper 最上，melchior 最下）。
     */
    public String hitTestUnit(float x, float y) {
        int ix = (int) x;
        int iy = (int) y;
        if (casperRegion != null && casperRegion.contains(ix, iy)) return "casper";
        if (balthasarRegion != null && balthasarRegion.contains(ix, iy)) return "balthasar";
        if (melchiorRegion != null && melchiorRegion.contains(ix, iy)) return "melchior";
        return null;
    }

    // -------------------------------------------------------------------- 测量布局

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int wMode = MeasureSpec.getMode(wSpec);
        int hMode = MeasureSpec.getMode(hSpec);
        int wSize = MeasureSpec.getSize(wSpec);
        int hSize = MeasureSpec.getSize(hSpec);

        int mw;
        int mh;
        if (wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY) {
            // 双精确：完全服从
            mw = wSize;
            mh = hSize;
        } else if (wMode == MeasureSpec.EXACTLY) {
            mw = wSize;
            mh = resolveSize(Math.round(mw / 2f), hSpec);
        } else if (hMode == MeasureSpec.EXACTLY) {
            mh = hSize;
            mw = resolveSize(Math.round(mh * 2f), wSpec);
        } else {
            // 无精确约束：给一个合理初始宽度（320dp），再按 2:1 推高度
            int base = wSize > 0 ? wSize : Math.round(Ui.dp(getContext(), 320f));
            mw = resolveSize(base, wSpec);
            mh = resolveSize(Math.round(mw / 2f), hSpec);
        }
        setMeasuredDimension(mw, mh);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        computeGeometry(nw, nh);
    }

    /** 由 View 尺寸重算内容框、列行分割线与各区域矩形。 */
    private void computeGeometry(int nw, int nh) {
        this.w = nw;
        this.h = nh;
        float inset = Ui.dp(getContext(), INSET_DP);
        X0 = inset;
        X1 = nw - inset;
        Y0 = inset;
        Y1 = nh - inset;
        cw = Math.max(0f, X1 - X0);
        ch = Math.max(0f, Y1 - Y0);

        for (int i = 0; i < LX.length; i++) LX[i] = X0 + COL_FRACS[i] * cw;
        for (int i = 0; i < RY.length; i++) RY[i] = Y0 + ROW_FRACS[i] * ch;

        buildRegions();
        buildPaths();
    }

    /**
     * 区域表。唯一一处定义区域边线的地方——全部以 LX/RY 索引表达，方便核对与调整。
     *
     * <p>网格线索引对应关系（CSS 命名线 → 本类索引）：
     * <pre>
     *   casper-start / status             = LX[0]   （fr 区起点，即 20px 装订边之后）
     *   left-header-end / balthasar-start = LX[1]
     *   casper-end / title                = LX[2]
     *   melchior-start                    = LX[3]
     *   balthasar-end / response          = LX[4]
     *   melchior-end                      = LX[5]
     *
     *   header / balthasar-start          = RY[0]
     *   status / response                 = RY[1]
     *   casper-start / melchior-start     = RY[2]
     *   balthasar-end / title             = RY[3]
     *   casper-end / melchior-end         = RY[4]
     * </pre>
     *
     * <p>注意 {@code header.left} 的起点在<b>容器左内缘</b>（含 20dp 装订边本身），
     * 因为 {@code left-header-start} 是本网格的第 1 条线；右页眉同理止于右内缘。
     * {@code status} 的名字同时出现在第 2 条列线与第 3 条行线上，按 CSS 规则
     * 「起止同名线＝跨一条轨道」，它落在<b>左侧那根 2fr 宽列</b>上——不是窄格。
     */
    private void buildRegions() {
        float border = Ui.dp(getContext(), 2f);        // .magi 的 2px 边框
        headerL.set(border, RY[0], LX[1], RY[1]);      // 質 問（含左装订边）
        headerR.set(LX[4], RY[0], w - border, RY[1]);  // 解 決（含右装订边）
        statusR.set(LX[0], RY[1], LX[1], RY[2]);       // 系统状态读数（左侧 2fr 列）
        responseR.set(LX[4], RY[1], LX[5], RY[2]);     // 最终结论框（右对齐）
        titleR.set(LX[2], RY[3], LX[3], RY[4]);        // MAGI
        casperR.set(LX[0], RY[2], LX[2], RY[4]);       // CASPER-3   line2→line4
        balthasarR.set(LX[1], RY[0], LX[4], RY[3]);    // BALTHASAR-2 line3→line6
        melchiorR.set(LX[3], RY[3], LX[5], RY[4]);     // MELCHIOR-1  line5→line7
        connCBR.set(LX[1], RY[2], LX[2], RY[3]);       // casper ↔ balthasar  rotate −54°
        connCMR.set(LX[2], RY[3], LX[3], RY[4]);       // casper ↔ melchior   不旋转
        connBMR.set(LX[3], RY[2], LX[4], RY[3]);       // balthasar ↔ melchior rotate +54°
    }

    /** 把三贤人矩形转成缺口多边形，并建立命中测试用的 Region。 */
    private void buildPaths() {
        casperPath = polygon(casperR, CASPER_POLY);
        balthasarPath = polygon(balthasarR, BALTHASAR_POLY);
        melchiorPath = polygon(melchiorR, MELCHIOR_POLY);

        Region clip = new Region(0, 0, Math.max(w, 1), Math.max(h, 1));
        casperRegion = new Region();
        balthasarRegion = new Region();
        melchiorRegion = new Region();
        casperRegion.setPath(casperPath, clip);
        balthasarRegion.setPath(balthasarPath, clip);
        melchiorRegion.setPath(melchiorPath, clip);
    }

    /** 用「相对矩形比例」的顶点表构造多边形路径。 */
    private static Path polygon(RectF r, float[] pts) {
        Path path = new Path();
        if (pts.length < 6) return path;
        float bw = r.width();
        float bh = r.height();
        path.moveTo(r.left + pts[0] * bw, r.top + pts[1] * bh);
        for (int i = 2; i < pts.length; i += 2) {
            path.lineTo(r.left + pts[i] * bw, r.top + pts[i + 1] * bh);
        }
        path.close();
        return path;
    }

    // ----------------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (w <= 0 || h <= 0 || casperPath == null) {
            computeGeometry(getWidth(), getHeight());
        }

        // 1) 底色
        canvas.drawColor(Ui.BLACK);

        // 2) MAGI 外框（2dp 橙）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 2f));
        p.setColor(fore(Ui.ORANGE));
        float b = p.getStrokeWidth() / 2f;
        canvas.drawRect(b, b, w - b, h - b, p);

        // 3) 连接杆（参考实现最先画，压在贤人块之下）
        drawConnection(canvas, connCBR, CONN_ROT_CB);
        drawConnection(canvas, connCMR, 0f);
        drawConnection(canvas, connBMR, CONN_ROT_BM);

        // 4) 页眉
        drawHeader(canvas, headerL, "質 問");
        drawHeader(canvas, headerR, "解 決");

        // 5) 三贤人块，顺序与参考 DOM 一致：melchior → balthasar → casper（后者压在先者上）
        boolean idle = session == null;
        drawUnit(canvas, melchiorPath, melchiorR, "melchior", labelOf("melchior", idle), idle);
        drawUnit(canvas, balthasarPath, balthasarR, "balthasar", labelOf("balthasar", idle), idle);
        drawUnit(canvas, casperPath, casperR, "casper", labelOf("casper", idle), idle);

        // 6) 状态读数。它所在的单元格很窄、文字会溢出到右侧，所以放在贤人块之后画，
        //    否则溢出的部分会被 balthasar 盖住。
        drawStatus(canvas);

        // 7) 结论框（右对齐）
        drawResponse(canvas);

        // 8) 标题 MAGI（参考实现里最后渲染，压在最上）
        drawTitle(canvas);
    }

    /** 闪烁「灭」帧：所有前景色统一变黑。 */
    private int fore(int color) {
        return flickerOn ? Ui.BLACK : color;
    }

    /** 页眉：两条横线 + 文字 + 两条横线，文字与横线的间距处处相等。 */
    private void drawHeader(Canvas canvas, RectF r, String title) {
        final Context ctx = getContext();
        int save = canvas.save();
        canvas.clipRect(r.left, r.top, r.right, r.bottom);   // 参考 .header{overflow:hidden}

        float margin = Ui.dp(ctx, RULE_MARGIN_DP);
        float ruleH = Ui.dp(ctx, RULE_HEIGHT_DP);
        float pitch = ruleH + margin * 2f;                   // 相邻横线的轴距

        float y1 = r.top + margin;
        float y2 = y1 + pitch;

        // 参考实现用 10cqh —— cqh 是「容器高度的 1%」，容器就是 .magi 面板本身，
        // 所以基准是面板高度 h，而不是页眉区自身的高度。
        float size = clamp(0.10f * h, Ui.sp(ctx, 10f), Ui.sp(ctx, 26f));
        tp.setTypeface(Ui.display(ctx));
        tp.setFakeBoldText(true);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(fore(Ui.ORANGE));
        Paint.FontMetrics fm = tp.getFontMetrics();
        float textH = fm.descent - fm.ascent;

        float textTop = y2 + ruleH + margin;
        float textCy = textTop + textH / 2f;
        float y3 = textTop + textH + margin;
        float y4 = y3 + pitch;

        p.setStyle(Paint.Style.FILL);
        p.setColor(fore(Ui.HEADER_GREEN));
        drawRule(canvas, r, y1, ruleH);
        drawRule(canvas, r, y2, ruleH);
        drawRule(canvas, r, y3, ruleH);
        drawRule(canvas, r, y4, ruleH);

        // 参考用 scaleX(2) 把中文字拉宽；围绕中心缩放可保持居中
        int s2 = canvas.save();
        canvas.scale(HEADER_SCALE_X, 1f, r.centerX(), textCy);
        drawCentered(canvas, title, r.centerX(), textCy);
        canvas.restoreToCount(s2);

        canvas.restoreToCount(save);
    }

    private void drawRule(Canvas canvas, RectF r, float y, float h) {
        canvas.drawRect(r.left, y, r.right, y + h, p);
    }

    /** 单个贤人块：状态色填充 + 同路径 2dp 橙描边 + 居中标签。 */
    private void drawUnit(Canvas canvas, Path path, RectF rect, String slug, String label, boolean idle) {
        final Context ctx = getContext();
        MagiModels.Status st = idle ? MagiModels.Status.INFO : statusOf(slug);

        if (flickerOn) {
            // 灭帧：整块变黑
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.BLACK);
            canvas.drawPath(path, p);
        } else {
            switch (st) {
                case YES:
                    fill(canvas, path, Ui.YES_GREEN);
                    break;
                case NO:
                    fill(canvas, path, Ui.NO_RED);
                    break;
                case ERROR:
                    fill(canvas, path, Ui.BLACK);
                    break;
                case CONDITIONAL:
                    drawConditionalStripes(canvas, path, rect);
                    break;
                default: // INFO
                    fill(canvas, path, Ui.INFO_BLUE);
                    break;
            }
        }

        // 与填充同一路径描边，互锁的接缝处因此保持橙色
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(ctx, 2f));
        p.setColor(fore(Ui.ORANGE));
        canvas.drawPath(path, p);

        // 参考 {@code .magi>.wise-man>.inner{font-size:8cqh}}，基准同为面板高度 h
        float size = clamp(0.08f * h, Ui.sp(ctx, 11f), Ui.sp(ctx, 30f));
        tp.setTypeface(Ui.display(ctx));
        tp.setFakeBoldText(true);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(Ui.BLACK);                                // 参考实现：块内黑字

        int save = canvas.save();
        canvas.clipPath(path);                                // 参考 .inner 同样被 clip-path 裁切
        drawCentered(canvas, label, rect.centerX(), rect.centerY());
        canvas.restoreToCount(save);
    }

    private void fill(Canvas canvas, Path path, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        canvas.drawPath(path, p);
    }

    /**
     * 状態（conditional）的 56° 双色绿斜纹。
     *
     * <p>参考实现是 {@code repeating-linear-gradient(56deg,#52e691 0 30px,#82cd68 30px 60px)}。
     * 这里先把画布绕块中心旋转 56°，再画水平条带——旋转后的水平条带边界正好是 56° 斜线，
     * 且不依赖 BitmapShader，边缘由多边形裁剪路径保证。
     */
    private void drawConditionalStripes(Canvas canvas, Path path, RectF rect) {
        final Context ctx = getContext();
        int save = canvas.save();
        canvas.clipPath(path);
        float cx = rect.centerX();
        float cy = rect.centerY();
        canvas.rotate(56f, cx, cy);

        float period = Ui.dp(ctx, 60f);   // 30dp 荧光绿 + 30dp 浅绿
        float half = period / 2f;
        float reach = (rect.width() + rect.height()) * 1.6f + period;   // 覆盖旋转后的包围盒

        p.setStyle(Paint.Style.FILL);
        int band = 0;
        for (float y = cy - reach; y < cy + reach; y += half) {
            p.setColor(band % 2 == 0 ? Ui.YES_GREEN : Ui.COND_GREEN);
            canvas.drawRect(cx - reach, y, cx + reach, y + half, p);
            band++;
        }
        canvas.restoreToCount(save);
    }

    /**
     * 连接杆。参考 {@code .connection{height:10px;margin:-10%}}：高度 10dp，
     * 在所在单元格基础上左右各外延 10% 容器宽，再按需绕中心旋转。
     */
    private void drawConnection(Canvas canvas, RectF r, float degrees) {
        final Context ctx = getContext();
        float cx = r.centerX();
        float cy = r.centerY();
        float len = r.width() + 2f * CONN_MARGIN * cw;
        float th = Ui.dp(ctx, CONN_HEIGHT_DP);

        p.setStyle(Paint.Style.FILL);
        p.setColor(fore(Ui.ORANGE));
        int save = canvas.save();
        if (degrees != 0f) canvas.rotate(degrees, cx, cy);
        canvas.drawRect(cx - len / 2f, cy - th / 2f, cx + len / 2f, cy + th / 2f, p);
        canvas.restoreToCount(save);
    }

    /**
     * 系统状态读数。参考 {@code components/status.js} + {@code .system-status}：
     * 第一行 {@code CODE:473} 用 6/8 块高，其余四行 3/8 块高；除首行外左缩进 4dp。
     * 单元格很窄，文字允许多溢出不裁切——这是参考画面里就有的观感。
     */
    private void drawStatus(Canvas canvas) {
        final Context ctx = getContext();

        // EXTENTION 的取值逻辑照抄 main.py#extention：
        // 闸门未跑完（待机）→ ????；判定为 yes/no 类 → 7312；否则 → 3023
        String ext;
        if (session == null) {
            ext = "????";
        } else {
            ext = session.gate.isYesNo ? "7312" : "3023";
        }
        String[] lines = {
                "CODE:473",
                "FILE:MAGI_SYS",
                "EXTENTION:" + ext,
                "EX_MODE:OFF",
                "PRIORITY:AAA",
        };

        // 参考 {@code .system-status>div{font-size:3cqh}}、首行 6cqh；
        // 基准是面板高度 h。手机上 3cqh 会小到读不出来，所以给一个可读下限，
        // 这是本作对参考实现唯一的自觉偏离（已记录在 docs/DESIGN.md）。
        float bigSize = Math.max(0.06f * h, Ui.dp(ctx, 10f));    // 6cqh
        float smallSize = Math.max(0.03f * h, Ui.dp(ctx, 7f));   // 3cqh

        tp.setTypeface(Ui.mono(ctx));
        tp.setFakeBoldText(false);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(fore(Ui.ORANGE));

        float x0 = statusR.left;
        float indent = Ui.dp(ctx, 4f);
        float y = statusR.top;
        for (int i = 0; i < lines.length; i++) {
            tp.setTextSize(i == 0 ? bigSize : smallSize);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float baseline = y - fm.ascent;              // 文本顶对齐到 y
            float x = i == 0 ? x0 : x0 + indent;
            canvas.drawText(lines[i], x, baseline, tp);
            y += (fm.descent - fm.ascent);               // 行高按字体行距堆叠
        }
    }

    /** 结论框：双层 2dp 边框（外 2dp 内边距 + 内 2dp 竖距/10dp 横距），右对齐到 LX5，垂直居中。 */
    private void drawResponse(Canvas canvas) {
        final Context ctx = getContext();
        MagiModels.Status st = (session == null || session.overall == null)
                ? MagiModels.Status.INFO : session.overall;

        float size = clamp(0.30f * responseR.height(), Ui.sp(ctx, 12f), Ui.sp(ctx, 34f));
        tp.setTypeface(Ui.display(ctx));
        tp.setFakeBoldText(true);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.LEFT);

        String text = st.label;
        float textW = tp.measureText(text);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float textH = fm.descent - fm.ascent;

        float hPad = Ui.dp(ctx, 10f);
        float vPad = Ui.dp(ctx, 2f);
        float innerBorder = Ui.dp(ctx, 2f);
        float outerPad = Ui.dp(ctx, 2f);
        float outerBorder = Ui.dp(ctx, 2f);

        float innerW = textW + 2f * hPad + 2f * innerBorder;
        float innerH = textH + 2f * vPad + 2f * innerBorder;
        float outerW = innerW + 2f * outerPad + 2f * outerBorder;
        float outerH = innerH + 2f * outerPad + 2f * outerBorder;

        float oL = responseR.right - outerW;
        float oT = responseR.centerY() - outerH / 2f;
        float iL = oL + outerBorder + outerPad;
        float iT = oT + outerBorder + outerPad;

        int color = fore(st.color);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(outerBorder);
        canvas.drawRect(oL + outerBorder / 2f, oT + outerBorder / 2f,
                oL + outerW - outerBorder / 2f, oT + outerH - outerBorder / 2f, p);
        p.setStrokeWidth(innerBorder);
        canvas.drawRect(iL + innerBorder / 2f, iT + innerBorder / 2f,
                iL + innerW - innerBorder / 2f, iT + innerH - innerBorder / 2f, p);

        tp.setColor(color);
        float tcx = iT + innerH / 2f;
        float baseline = tcx - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, iL + innerBorder + hPad, baseline, tp);
    }

    /** 标题 MAGI。 */
    private void drawTitle(Canvas canvas) {
        final Context ctx = getContext();
        // 参考 {@code .magi>.title{font-size:9cqh}}，基准为面板高度 h
        float size = clamp(0.09f * h, Ui.sp(ctx, 14f), Ui.sp(ctx, 46f));
        tp.setTypeface(Ui.display(ctx));
        tp.setFakeBoldText(true);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(fore(Ui.ORANGE));
        drawCentered(canvas, "MAGI", titleR.centerX(), titleR.centerY());
    }

    /** 以 (cx,cy) 为几何中心绘制文本（按字体度量做垂直居中）。 */
    private void drawCentered(Canvas canvas, String text, float cx, float cy) {
        Paint.FontMetrics fm = tp.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx, baseline, tp);
    }

    // ---------------------------------------------------------------- 触摸与回收

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                String slug = hitTestUnit(e.getX(), e.getY());
                if (slug != null && tapListener != null) {
                    tapListener.onUnitTap(slug);
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 停掉闪烁定时器，避免 View 被移除后仍持有回调
        removeCallbacks(flickerTask);
        flickerOn = false;
    }

    // -------------------------------------------------------------------- 小工具

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 取某贤人的状态；无会话或缺读时按 情報 处理。 */
    private MagiModels.Status statusOf(String slug) {
        MagiModels.UnitReading u = session == null ? null : session.unit(slug);
        if (u == null || u.status == null) return MagiModels.Status.INFO;
        return u.status;
    }

    /**
     * 块上的标签：绑定会话时用贤人读数里的 label（如 {@code MELCHIOR • 1}）；
     * 待机时用出厂标签（名称 + 编号），与参考实现的显示格式一致。
     */
    private String labelOf(String slug, boolean idle) {
        if (!idle) {
            MagiModels.UnitReading u = session.unit(slug);
            if (u != null && u.label != null && u.label.length() > 0) return u.label;
        }
        for (MagiModels.WiseMan m : MagiModels.WiseMan.ALL) {
            if (m.slug.equals(slug)) return m.label;
        }
        return slug.toUpperCase();
    }
}
