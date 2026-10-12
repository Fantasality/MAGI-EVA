package com.nerv.magi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.nerv.magi.core.Prefs;
import com.nerv.magi.jev.MagiClient;
import com.nerv.magi.ui.Ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 接口配置页。
 *
 * <p>与参考实现 jev-chat-jarvis 的 SettingsActivity 同构：<b>判断 / 回复 / 视觉三路分卡</b>，
 * 每张卡各有地址、密钥、模型和一键连通测试；密钥留空时按 视觉 → 回复 → 判断 逐级回退，
 * 因此只填判断接口一把密钥就能用。
 *
 * <p>多出来的两件事：一是「從文件導入」——直接读一个文本文件的第一行当密钥，
 * 省得在手机上长按粘贴；二是行为开关（遥测 / 闪烁 / 判词），都只影响显示，不影响裁决。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PICK_KEY = 1001;

    private Prefs prefs;
    private MagiClient client;

    private EditText judgeBase, judgeKey, judgeModel;
    private EditText replyBase, replyKey, replyModel;
    private EditText visionBase, visionKey, visionModel;
    private Spinner provider;
    private TextView testResult;

    /** 正在接收「导入」的那一栏。 */
    private EditText pendingImport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        client = new MagiClient(prefs);
        setContentView(buildUi());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAll();
    }

    // ---------------------------------------------------------------- 界面构建

    private View buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) Ui.dp(this, 12);
        root.setPadding(p, p, p, p);
        scroller.addView(root);

        root.addView(head("接口 / INTERFACE"));
        root.addView(note("三路分離，與參考實現一致。只填「判斷接口」即可運行；"
                + "回復與視覺留空會自動繼承判斷接口的密鑰。密鑰只存在本機 app 私有空間。"));

        // ---------------- 判断路
        root.addView(card("判斷接口  ·  JEV DECISION", Ui.ORANGE));
        root.addView(label("供應商預設"));
        provider = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, providerNames());
        provider.setAdapter(ad);
        provider.setSelection(indexOfProvider(prefs.judgeProvider()));
        provider.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String pid = Prefs.PROVIDERS[position][0];
                if (pid.equals(prefs.judgeProvider())) return;   // 初次回填不覆盖
                prefs.applyProviderDefaults(pid);
                loadJudge();
                toast("已切換到 " + Prefs.PROVIDERS[position][1] + " 預設");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(provider);

        judgeBase = field("服務地址", prefs.judgeBaseUrl(), false);
        judgeKey = field("API Key", prefs.judgeKey(), true);
        judgeModel = field("模型", prefs.judgeModel(), false);
        root.addView((View) judgeBase.getParent());
        root.addView((View) judgeKey.getParent());
        root.addView((View) judgeModel.getParent());
        root.addView(importRow(judgeKey));
        root.addView(testRow("判斷接口", new Runnable() {
            public void run() {
                runTest("判斷接口", new Testable() {
                    public String run() {
                        return client.testJudge();
                    }
                });
            }
        }));

        // ---------------- 回复路
        root.addView(card("回復接口  ·  可選", Ui.ORANGE));
        root.addView(note("OpenAI 兼容的 /chat/completions。只用來生成三賢人的一句判詞，"
                + "留空即繼承判斷接口；它掛了不影響任何裁決。"));
        replyBase = field("服務地址", prefs.replyBaseUrl(), false);
        replyKey = field("API Key（留空繼承）", prefs.replyKey(), true);
        replyModel = field("模型", prefs.replyModel(), false);
        root.addView((View) replyBase.getParent());
        root.addView((View) replyKey.getParent());
        root.addView((View) replyModel.getParent());
        root.addView(testRow("回復接口", new Runnable() {
            public void run() {
                runTest("回復接口", new Testable() {
                    public String run() {
                        return client.testReply();
                    }
                });
            }
        }));

        // ---------------- 视觉路
        root.addView(card("視覺接口  ·  可選", Ui.ORANGE));
        root.addView(note("多模態接口。當前合議流程不使用，保留完整三路結構以便日後"
                + "擴展「對著屏幕詢問 MAGI」。"));
        visionBase = field("服務地址", prefs.visionBaseUrl(), false);
        visionKey = field("API Key（留空繼承）", prefs.visionKey(), true);
        visionModel = field("模型", prefs.visionModel(), false);
        root.addView((View) visionBase.getParent());
        root.addView((View) visionKey.getParent());
        root.addView((View) visionModel.getParent());
        root.addView(testRow("視覺接口", new Runnable() {
            public void run() {
                runTest("視覺接口", new Testable() {
                    public String run() {
                        return client.testVision();
                    }
                });
            }
        }));

        // ---------------- 行为
        root.addView(card("行為 / BEHAVIOUR", Ui.ORANGE));
        root.addView(toggle("顯示遙測（機率分佈 / tokens / 費用）", prefs.telemetry(),
                new Toggle() {
                    public void set(boolean v) {
                        prefs.setTelemetry(v);
                    }
                }));
        root.addView(toggle("運算期間面板閃爍", prefs.flicker(), new Toggle() {
            public void set(boolean v) {
                prefs.setFlicker(v);
            }
        }));
        root.addView(toggle("為三賢人生成判詞（走回復接口，會增加耗時與費用）",
                prefs.narration(), new Toggle() {
                    public void set(boolean v) {
                        prefs.setNarration(v);
                    }
                }));

        testResult = new TextView(this);
        testResult.setTypeface(Ui.mono(this));
        testResult.setTextSize(11);
        testResult.setTextColor(Ui.ORANGE);
        testResult.setPadding((int) Ui.dp(this, 4), (int) Ui.dp(this, 14), 0, 0);
        root.addView(testResult);

        root.addView(note("\n判斷接口位址規則（與參考實現逐分支一致）：\n"
                + "  OpenRouter        → {base}/alpha/decisions\n"
                + "  博查 Jev / TypeSafe / Vercel / Zen → {base}/v1/systemone\n"
                + "  自訂              → 你填的完整 URL\n\n"
                + "出廠預設：OpenRouter · typesafe/jev-1.13"));

        root.addView(aboutRow());

        return scroller;
    }

    // ------------------------------------------------------------------ 關於

    /**
     * 「關於 / 授權」。
     *
     * <p>不只是礼貌：上游 jev-chat-jarvis 的 NOTICE 明确要求分发时
     * 「在产品「关于」页、说明文档或发布页中注明出处」，所以这一页是许可合规的一部分，
     * 不是可选项。完整的致谢与许可全文见仓库根目录的 NOTICE 与 LICENSE。
     */
    private View aboutRow() {
        Button b = new Button(this);
        b.setText("關於 / 授權 / 致謝");
        b.setAllCaps(false);
        b.setTextColor(Ui.ORANGE);
        b.setTypeface(Ui.mono(this));
        b.setTextSize(12);
        b.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, 22);
        holder.addView(b, lp);
        holder.addView(note("本作是二次開發，介面與後端結構均取自上游開源專案，"
                + "按 MIT 協議要求在此註明出處。"));
        return holder;
    }

    private void showAbout() {
        String text =
                "MAGI-EVA   v1.0.0\n"
                        + "Jev 決策模型驅動的 EVA 風格 MAGI 三賢人決策系統\n"
                        + "────────────────────────────────\n\n"
                        + "■ 介面與合議邏輯參考\n"
                        + "  TomaszRewak/MAGI（MIT）\n"
                        + "  github.com/TomaszRewak/MAGI\n"
                        + "  三個裁形多邊形、連接桿角度、配色表、閃爍動效、\n"
                        + "  五個結論的判定順序與三段出廠人格，均取自該專案。\n\n"
                        + "■ 後端結構參考\n"
                        + "  基於 Jev 聊天助手二次開發（MIT）\n"
                        + "  github.com/jev-chat/jev-chat-jarvis\n"
                        + "  三路接口結構、供應商預設與端點規則、退避與錯誤\n"
                        + "  歸一化、密鑰逐級回退，均與該專案一致。\n"
                        + "  本作與原作者無從屬或背書關係。\n\n"
                        + "■ 決策模型\n"
                        + "  Jev · typesafe/jev-1.13\n\n"
                        + "■ 字體\n"
                        + "  FOT-Matisse Pro ExtraBold\n"
                        + "  版權歸 Fontworks Inc. 所有；商用請自行取得授權。\n"
                        + "  移除 assets/fonts/MatisseEB.otf 即回退系統字體。\n\n"
                        + "■ 免責\n"
                        + "  《新世紀福音戰士》及其全部設定、名稱、術語的版權\n"
                        + "  歸 khara 及權利人所有。本專案為非官方同人作品，\n"
                        + "  與權利人無任何關聯，不含任何原作素材。\n\n"
                        + "MIT License · Copyright (c) 2026 Fantasality\n"
                        + "完整條款見倉庫根目錄 LICENSE 與 NOTICE。";

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Ui.ORANGE);
        tv.setTypeface(Ui.mono(this));
        tv.setTextSize(11);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        int p = (int) Ui.dp(this, 14);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BLACK);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("關於 MAGI-EVA")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------------------------------------------------------- 保存/测试

    private void saveAll() {
        prefs.setJudgeBaseUrl(judgeBase.getText().toString());
        prefs.setJudgeKey(judgeKey.getText().toString());
        prefs.setJudgeModel(judgeModel.getText().toString());
        prefs.setReplyBaseUrl(replyBase.getText().toString());
        prefs.setReplyKey(replyKey.getText().toString());
        prefs.setReplyModel(replyModel.getText().toString());
        prefs.setVisionBaseUrl(visionBase.getText().toString());
        prefs.setVisionKey(visionKey.getText().toString());
        prefs.setVisionModel(visionModel.getText().toString());
    }

    private void loadJudge() {
        judgeBase.setText(prefs.judgeBaseUrl());
        judgeModel.setText(prefs.judgeModel());
    }

    private interface Testable {
        String run() throws Exception;
    }

    /** 测试跑在工作线程，结果回主线程。 */
    private void runTest(final String which, final Testable t) {
        testResult.setTextColor(Ui.ORANGE);
        testResult.setText(which + " 測試中 …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveAll();
                try {
                    final String r = t.run();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            testResult.setTextColor(Ui.YES_GREEN);
                            testResult.setText(r);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            testResult.setTextColor(Ui.NO_RED);
                            testResult.setText("✕ " + which + " 失敗：" + e.getMessage());
                        }
                    });
                }
            }
        }, "magi-test").start();
    }

    // ------------------------------------------------------------ 密钥文件导入

    private void pickKeyFile(EditText target) {
        pendingImport = target;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        try {
            startActivityForResult(i, REQ_PICK_KEY);
        } catch (Exception e) {
            toast("無法打開文件選擇器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_KEY || res != RESULT_OK || data == null || pendingImport == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            String key = "";
            String url = "";
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    if (url.isEmpty()) url = s;
                    continue;
                }
                if (key.isEmpty()) key = s;
            }
            r.close();
            if (key.isEmpty()) {
                toast("文件裡沒讀到密鑰。");
                return;
            }
            pendingImport.setText(key);
            if (!url.isEmpty()) {
                judgeBase.setText(url);
            }
            prefs.setJudgeKey(key);
            if (!url.isEmpty()) prefs.setJudgeBaseUrl(url);
            new AlertDialog.Builder(this)
                    .setTitle("已導入")
                    .setMessage("密鑰長度 " + key.length()
                            + (url.isEmpty() ? "" : "\n地址 " + url))
                    .setPositiveButton("好", null)
                    .show();
        } catch (Exception e) {
            toast("讀取失敗：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 小件

    private String[] providerNames() {
        String[] out = new String[Prefs.PROVIDERS.length];
        for (int i = 0; i < out.length; i++) out[i] = Prefs.PROVIDERS[i][1];
        return out;
    }

    private int indexOfProvider(String pid) {
        for (int i = 0; i < Prefs.PROVIDERS.length; i++) {
            if (Prefs.PROVIDERS[i][0].equals(pid)) return i;
        }
        return 0;
    }

    private TextView head(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.ORANGE);
        t.setTypeface(Ui.display(this), Typeface.BOLD);
        t.setTextSize(20);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, (int) Ui.dp(this, 6));
        return t;
    }

    private TextView card(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Ui.mono(this), Typeface.BOLD);
        t.setTextSize(13);
        t.setPadding(0, (int) Ui.dp(this, 16), 0, (int) Ui.dp(this, 6));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.ORANGE_DIM);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(10);
        t.setLineSpacing(Ui.dp(this, 2), 1f);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.ORANGE_DIM);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(10);
        t.setPadding(0, (int) Ui.dp(this, 6), 0, 0);
        return t;
    }

    /**
     * 一行「标签 + 输入框」，返回输入框本身（与其父容器同进同出）。
     */
    private EditText field(String name, String value, boolean secret) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) Ui.dp(this, 6), 0, 0);
        row.addView(label(name));

        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextColor(Ui.ORANGE);
        e.setTypeface(Ui.mono(this));
        e.setTextSize(12);
        e.setSingleLine(true);
        e.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        e.setPadding((int) Ui.dp(this, 8), (int) Ui.dp(this, 7),
                (int) Ui.dp(this, 8), (int) Ui.dp(this, 7));
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        row.addView(e);
        return e;
    }

    private View importRow(final EditText target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, 8);

        Button b = new Button(this);
        b.setText("從文件導入密鑰");
        b.setAllCaps(false);
        b.setTextColor(Ui.ORANGE);
        b.setTypeface(Ui.mono(this));
        b.setTextSize(11);
        b.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickKeyFile(target);
            }
        });
        row.addView(b);

        TextView t = new TextView(this);
        t.setText("  讀取文件第一行作密鑰；若文件裡有 http(s) 開頭的行，"
                + "會一併當作服務地址。");
        t.setTextColor(Ui.ORANGE_DIM);
        t.setTypeface(Ui.mono(this));
        t.setTextSize(10);
        row.addView(t);

        row.setTag(lp);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(row, lp);
        return holder;
    }

    private View testRow(String name, final Runnable action) {
        Button b = new Button(this);
        b.setText("測試 " + name);
        b.setAllCaps(false);
        b.setTextColor(Ui.ORANGE);
        b.setTypeface(Ui.mono(this));
        b.setTextSize(11);
        b.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, 8);
        holder.addView(b, lp);
        return holder;
    }

    private interface Toggle {
        void set(boolean v);
    }

    private View toggle(String name, boolean value, final Toggle t) {
        CheckBox c = new CheckBox(this);
        c.setText(name);
        c.setChecked(value);
        c.setTextColor(Ui.ORANGE);
        c.setTypeface(Ui.mono(this));
        c.setTextSize(11);
        c.setPadding(0, (int) Ui.dp(this, 6), 0, 0);
        c.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                t.set(v);
            }
        });
        return c;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
