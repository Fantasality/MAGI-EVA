package com.nerv.magi;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nerv.magi.core.MagiModels.WiseMan;
import com.nerv.magi.core.Prefs;
import com.nerv.magi.ui.Ui;

/**
 * 三贤人人格配置页。
 *
 * <p>这是本作对「可以填入三個人格」要求的落点。三位子系统各自持有一段人格提示词，
 * 出厂值就是参考实现 {@code main.py} 里赤木直子那三段移植人格——科学家 / 母亲 / 女性。
 * 用户可以把任意一段改写掉，改写后那段文字会作为 {@code persona} 进入 Jev 的 state，
 * 同时被写进裁决题目的 {@code instructions}，因此它是<b>真正影响裁决</b>的，
 * 不是装饰性的角色扮演设定。
 *
 * <p>清空某一栏即恢复该贤人的出厂人格——与参考实现「默认值来自 main.py」的行为一致。
 */
public class PersonaActivity extends Activity {

    private Prefs prefs;
    private final EditText[] editors = new EditText[WiseMan.ALL.length];
    private final TextView[] badges = new TextView[WiseMan.ALL.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(buildUi());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAll();
    }

    private View buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) Ui.dp(this, 12);
        root.setPadding(p, p, p, p);
        scroller.addView(root);

        TextView head = new TextView(this);
        head.setText("三賢人 / MAGI SYSTEM");
        head.setTextColor(Ui.ORANGE);
        head.setTypeface(Ui.display(this), Typeface.BOLD);
        head.setTextSize(20);
        head.setLetterSpacing(0.12f);
        root.addView(head);

        TextView note = new TextView(this);
        note.setText("MAGI 由三台超級電腦組成，各自移植了赤木直子人格的一個側面，"
                + "以多數決與一票否決制共同裁決。下面三段文字就是三位子系統「看事情的方式」，"
                + "會直接決定它們對同一命題給出 yes / no / conditional 中的哪一個。\n\n"
                + "留空即恢復出廠人格。");
        note.setTextColor(Ui.ORANGE_DIM);
        note.setTypeface(Ui.mono(this));
        note.setTextSize(10);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        note.setPadding(0, (int) Ui.dp(this, 8), 0, (int) Ui.dp(this, 4));
        root.addView(note);

        for (int i = 0; i < WiseMan.ALL.length; i++) {
            root.addView(buildUnitCard(WiseMan.ALL[i], i));
        }

        Button resetAll = new Button(this);
        resetAll.setText("全部恢復出廠人格");
        resetAll.setAllCaps(false);
        resetAll.setTextColor(Ui.ORANGE);
        resetAll.setTypeface(Ui.mono(this));
        resetAll.setTextSize(12);
        resetAll.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        resetAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetPersonas();
                for (int i = 0; i < WiseMan.ALL.length; i++) {
                    editors[i].setText(WiseMan.ALL[i].defaultPersona);
                    refreshBadge(i);
                }
                toast("三位賢人均已恢復出廠人格。");
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) Ui.dp(this, 18);
        root.addView(resetAll, rlp);

        return scroller;
    }

    private View buildUnitCard(final WiseMan w, final int idx) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, (int) Ui.dp(this, 18), 0, 0);

        // 标题行：代号 + 出厂角色
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(w.label);
        name.setTextColor(Ui.ORANGE);
        name.setTypeface(Ui.mono(this), Typeface.BOLD);
        name.setTextSize(14);
        titleRow.addView(name);

        TextView role = new TextView(this);
        role.setText("   " + canonRole(w.slug));
        role.setTextColor(Ui.ORANGE_DIM);
        role.setTypeface(Ui.mono(this));
        role.setTextSize(10);
        titleRow.addView(role);

        TextView badge = new TextView(this);
        badge.setTypeface(Ui.mono(this));
        badge.setTextSize(10);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = (int) Ui.dp(this, 8);
        titleRow.addView(badge, blp);
        badges[idx] = badge;

        card.addView(titleRow);

        TextView hint = new TextView(this);
        hint.setText("出廠（參考實現 ai.py）：" + w.defaultPersona);
        hint.setTextColor(Ui.scale(Ui.ORANGE, 0.62f));
        hint.setTypeface(Ui.mono(this));
        hint.setTextSize(9);
        hint.setLineSpacing(Ui.dp(this, 2), 1f);
        hint.setPadding(0, (int) Ui.dp(this, 5), 0, (int) Ui.dp(this, 5));
        card.addView(hint);

        final EditText e = new EditText(this);
        e.setText(prefs.persona(w));
        e.setTextColor(Ui.ORANGE);
        e.setTypeface(Ui.mono(this));
        e.setTextSize(11);
        e.setGravity(android.view.Gravity.TOP);
        e.setMinLines(4);
        e.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.8f)));
        e.setPadding((int) Ui.dp(this, 8), (int) Ui.dp(this, 8),
                (int) Ui.dp(this, 8), (int) Ui.dp(this, 8));
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable s) {
                prefs.setPersona(w.slug, s.toString());
                refreshBadge(idx);
            }
        });
        card.addView(e);
        editors[idx] = e;

        Button reset = new Button(this);
        reset.setText("恢復 " + w.name + " 出廠人格");
        reset.setAllCaps(false);
        reset.setTextColor(Ui.ORANGE);
        reset.setTypeface(Ui.mono(this));
        reset.setTextSize(10);
        reset.setBackground(MainActivity.evaOutline(Ui.scale(Ui.ORANGE, 0.7f)));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setPersona(w.slug, "");
                e.setText(w.defaultPersona);
                refreshBadge(idx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) Ui.dp(this, 6);
        card.addView(reset, lp);

        refreshBadge(idx);
        return card;
    }

    private void refreshBadge(int idx) {
        WiseMan w = WiseMan.ALL[idx];
        boolean over = prefs.personaOverridden(w.slug);
        TextView b = badges[idx];
        if (b == null) return;
        b.setText(over ? "● 已改寫" : "○ 出廠");
        b.setTextColor(over ? Ui.HAZARD : Ui.HEADER_GREEN);
    }

    private void saveAll() {
        for (int i = 0; i < WiseMan.ALL.length; i++) {
            if (editors[i] == null) continue;
            String text = editors[i].getText().toString().trim();
            // 与出厂文本完全相同也视为未改寫，避免无意义的 override 标记
            if (text.equals(WiseMan.ALL[i].defaultPersona.trim())) text = "";
            prefs.setPersona(WiseMan.ALL[i].slug, text);
        }
    }

    private static String canonRole(String slug) {
        if ("melchior".equals(slug)) return "赤木直子 · 身為科學家的人格";
        if ("balthasar".equals(slug)) return "赤木直子 · 身為母親的人格";
        return "赤木直子 · 身為女性的人格";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
