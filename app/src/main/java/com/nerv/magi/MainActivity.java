package com.nerv.magi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.Prefs;
import com.nerv.magi.jev.MagiClient;
import com.nerv.magi.ui.MagiPanelView;
import com.nerv.magi.ui.TelemetryView;
import com.nerv.magi.ui.Ui;
import com.nerv.magi.ui.UnitModal;

/**
 * MAGI 主控台。
 *
 * <p>一屏三件东西，自上而下：
 * <ol>
 *   <li><b>三賢人面板</b>——参考实现的 MAGI 控制台，三个互锁的贤人块 + 页眉 + 状态读数 + 结论框；</li>
 *   <li><b>遥測面板</b>——Jev 直接回报的概率分布、把握度、确信度、tokens 与费用；</li>
 *   <li><b>詢問輸入</b>——提交命題的入口。</li>
 * </ol>
 *
 * <p>点任意贤人块可展开它的完整读数（概率条逐项 + 推理由来）。
 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private MagiClient client;

    private MagiPanelView panel;
    private TelemetryView telemetry;
    private EditText input;
    private TextView hint;
    private Button execute;

    private MagiModels.Session session;
    private volatile boolean running;

    /** 几个开箱可试的命题，省得第一次打开不知道该问什么。 */
    private static final String[] PRESETS = {
            "我們應該啟動人類補完計劃嗎？",
            "是否同意把第三新東京市的地下都市擴建為要塞？",
            "要不要现在辞职去做自己真正想做的事？",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        client = new MagiClient(prefs);

        setContentView(buildUi());
        refreshProviderLine();
        panel.bind(null);
        telemetry.bind(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProviderLine();
    }

    // ---------------------------------------------------------------- 界面构建

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BLACK);

        // ---- 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) Ui.dp(this, 10);
        top.setPadding(p, p, p, (int) Ui.dp(this, 4));

        TextView brand = new TextView(this);
        brand.setText("MAGI SYSTEM");
        brand.setTextColor(Ui.ORANGE);
        brand.setTypeface(Ui.display(this), Typeface.BOLD);
        brand.setTextSize(17);
        brand.setLetterSpacing(0.18f);
        top.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(navButton("三賢人", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PersonaActivity.class));
            }
        }));
        top.addView(navButton("接口", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        root.addView(top);

        // ---- 三贤人面板（固有 2:1 比例）
        panel = new MagiPanelView(this);
        panel.setOnUnitTapListener(new MagiPanelView.OnUnitTapListener() {
            @Override
            public void onUnitTap(String slug) {
                if (session == null) {
                    toast("尚未執行合議。");
                    return;
                }
                MagiModels.UnitReading u = session.unit(slug);
                if (u == null) {
                    toast("該子系統尚無讀數。");
                    return;
                }
                new UnitModal(MainActivity.this).show(session, u);
            }
        });

        FrameLayout panelHolder = new FrameLayout(this);
        int hp = (int) Ui.dp(this, 8);
        panelHolder.setPadding(hp, 0, hp, 0);
        panelHolder.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(panelHolder);

        // ---- 遥测（可滚动）
        telemetry = new TelemetryView(this);
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BLACK);
        scroller.addView(telemetry, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = (int) Ui.dp(this, 6);
        root.addView(scroller, slp);

        // ---- 询问输入
        root.addView(buildQueryBar());

        return root;
    }

    private View buildQueryBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = (int) Ui.dp(this, 8);
        wrap.setPadding(p, p, p, p);
        wrap.setBackgroundColor(Ui.STRIPE_DARK);

        // 预设命题
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRESETS.length; i++) {
            final String q = PRESETS[i];
            TextView chip = new TextView(this);
            chip.setText("命題" + (i + 1));
            chip.setTextColor(Ui.ORANGE_DIM);
            chip.setTypeface(Ui.mono(this));
            chip.setTextSize(11);
            chip.setPadding((int) Ui.dp(this, 8), (int) Ui.dp(this, 4),
                    (int) Ui.dp(this, 8), (int) Ui.dp(this, 4));
            chip.setBackground(evaOutline(Ui.scale(Ui.ORANGE, 0.7f)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(q);
                    input.setSelection(q.length());
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = (int) Ui.dp(this, 6);
            chips.addView(chip, clp);
        }
        wrap.addView(chips);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(this, 6);

        input = new EditText(this);
        input.setHint("輸入命題 / 詢問 …");
        input.setHintTextColor(Ui.scale(Ui.ORANGE, 0.55f));
        input.setTextColor(Ui.ORANGE);
        input.setTypeface(Ui.mono(this));
        input.setTextSize(14);
        input.setBackground(evaOutline(Ui.ORANGE));
        input.setPadding((int) Ui.dp(this, 10), (int) Ui.dp(this, 9),
                (int) Ui.dp(this, 10), (int) Ui.dp(this, 9));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(3);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    execute();
                    return true;
                }
                return false;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        execute = evaButton("執 行");
        execute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                execute();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = (int) Ui.dp(this, 8);
        row.addView(execute, blp);

        wrap.addView(row, rlp);

        hint = new TextView(this);
        hint.setTextColor(Ui.ORANGE_DIM);
        hint.setTypeface(Ui.mono(this));
        hint.setTextSize(10);
        hint.setPadding(0, (int) Ui.dp(this, 6), 0, 0);
        wrap.addView(hint);

        return wrap;
    }

    // ---------------------------------------------------------------- 执行流程

    private void execute() {
        if (running) {
            toast("合議進行中 …");
            return;
        }
        final String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            toast("請先輸入命題。");
            return;
        }
        input.setText("");

        running = true;
        execute.setEnabled(false);
        session = null;
        panel.bind(null);
        panel.setBusy(prefs.flicker());
        telemetry.bind(null);
        hint.setText("MAGI 運算中 …  三賢人並行發出");

        new Thread(new Runnable() {
            @Override
            public void run() {
                client.conduct(q, new MagiClient.Progress() {
                    @Override
                    public void onGate(final MagiModels.Session live, final MagiModels.Gate gate) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // 闸门一回来就接管面板：EXTENTION 立刻从 ???? 变成 7312/3023
                                session = live;
                                panel.bind(live);
                                telemetry.bind(live);
                                if (gate.failed()) {
                                    hint.setText("閘門失敗：" + gate.call.error);
                                } else {
                                    hint.setText("閘門 " + (gate.isYesNo ? "7312" : "3023")
                                            + "   可否決事項 = " + gate.isYesNo
                                            + "   •   三賢人裁決中 …");
                                }
                            }
                        });
                    }

                    @Override
                    public void onUnit(final MagiModels.Session live, final MagiModels.UnitReading unit) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // 每回来一位就点亮它，其余仍在闪
                                session = live;
                                panel.bind(live);
                                telemetry.bind(live);
                            }
                        });
                    }

                    @Override
                    public void onDone(final MagiModels.Session done) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                session = done;
                                running = false;
                                execute.setEnabled(true);
                                panel.setBusy(false);
                                panel.bind(done);
                                telemetry.bind(done);
                                String msg = done.overall.label + "   " + done.elapsedMs() + "ms";
                                if (done.error != null && !done.error.isEmpty()) {
                                    msg += "   " + done.error;
                                }
                                hint.setText(msg);
                            }
                        });
                    }
                });
            }
        }, "magi-conduct").start();
    }

    // ------------------------------------------------------------------ 小件

    private void refreshProviderLine() {
        if (telemetry == null) return;
        String line = "JUDGE " + prefs.judgeProviderLabel()
                + "  ·  " + prefs.judgeModel()
                + "  ·  " + prefs.judgeEndpoint();
        telemetry.setProviderLine(line);
        if (hint != null && !running && session == null) {
            if (prefs.hasKey()) {
                hint.setText("判斷接口已配置 · 三賢人人格可在「三賢人」頁改寫");
            } else {
                hint.setText("尚未配置密鑰 — 請進入「接口」填入 Jev 判斷接口的 API Key");
            }
        }
    }

    /** EVA 风格描边按钮。 */
    private Button evaButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.ORANGE);
        b.setTypeface(Ui.display(this), Typeface.BOLD);
        b.setTextSize(14);
        b.setBackground(evaOutline(Ui.ORANGE));
        b.setPadding((int) Ui.dp(this, 16), (int) Ui.dp(this, 8),
                (int) Ui.dp(this, 16), (int) Ui.dp(this, 8));
        return b;
    }

    /** 顶栏小按钮。 */
    private Button navButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.ORANGE);
        b.setTypeface(Ui.mono(this));
        b.setTextSize(12);
        b.setBackground(evaOutline(Ui.scale(Ui.ORANGE, 0.75f)));
        b.setPadding((int) Ui.dp(this, 10), (int) Ui.dp(this, 4),
                (int) Ui.dp(this, 10), (int) Ui.dp(this, 4));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) Ui.dp(this, 6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 2dp 橙色描边、黑色填充的方框，用在按钮/输入框上。 */
    static android.graphics.drawable.Drawable evaOutline(int color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(Ui.BLACK);
        d.setStroke(2, color);      // 与参考实现的面板边框同为 2px
        return d;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
