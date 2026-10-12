package com.nerv.magi.core;

import com.nerv.magi.core.MagiModels.Session;
import com.nerv.magi.core.MagiModels.Status;
import com.nerv.magi.core.MagiModels.UnitReading;

import java.util.List;

/**
 * MAGI 合议逻辑：一票否决制。
 *
 * <p>判定顺序与参考实现 {@code main.py:response_status} 逐条对应，不可调换——
 * 顺序本身就是 EVA 里 MAGI 的行为契约：
 *
 * <pre>
 *   誤差 error       —— 有贤人出错            （最高优先）
 *   情報 info        —— 该问题不是 yes/no 类
 *   拒絶 no          —— 至少一贤人反对        （一票否决）
 *   状態 conditional —— 至少一贤人附条件同意
 *   合意 yes         —— 三贤人一致无条件同意
 * </pre>
 *
 * <p>注意 info 与 error 的相对次序：参考实现把 error 放在最前，所以一个不是 yes/no
 * 的问题若同时有贤人报错，结论仍是「誤差」而不是「情報」。这里保持同样行为。
 */
public final class MagiLogic {

    private MagiLogic() {
    }

    /**
     * 由三贤人读数与闸门判决得出最终结论。
     *
     * @param units 三贤人读数（顺序无关）
     * @param isYesNo 闸门判决：该询问是否属于 yes/no 类
     */
    public static Status vote(List<UnitReading> units, boolean isYesNo) {
        boolean anyError = false;
        boolean anyNo = false;
        boolean anyConditional = false;
        boolean allYes = true;
        int n = 0;

        for (UnitReading u : units) {
            n++;
            if (u.status == Status.ERROR) anyError = true;
            if (u.status == Status.NO) anyNo = true;
            if (u.status == Status.CONDITIONAL) anyConditional = true;
            if (u.status != Status.YES) allYes = false;
        }

        if (anyError) return Status.ERROR;          // 誤 差
        if (!isYesNo) return Status.INFO;           // 情 報
        if (anyNo) return Status.NO;                // 拒 絶（一票否决）
        if (anyConditional) return Status.CONDITIONAL; // 状 態
        if (n > 0 && allYes) return Status.YES;     // 合 意
        return Status.INFO;
    }

    /** 便捷重载：直接吃一个会话。 */
    public static Status vote(Session session) {
        return vote(session.units, session.gate.isYesNo);
    }

    /**
     * 把一贤人的 Jev 裁决映射为投票状态。
     *
     * <p>不是 yes/no 类问题时，参考实现（main.py）根本不去分类贤人的答复，
     * 一律记为 info；这里同样只在闸门通过时才采信裁决。
     */
    public static Status classify(UnitReading u, boolean isYesNo) {
        if (!u.ok()) return Status.ERROR;
        if (!isYesNo) return Status.INFO;
        if (u.verdict == null) return Status.INFO;
        Status s = Status.from(u.verdict.choice);
        return s == Status.ERROR ? Status.INFO : s;
    }

    /** 结论的一句话释义，用于遥测面板与弹窗。 */
    public static String explain(Status s) {
        switch (s) {
            case YES: return "三贤人一致无条件同意。";
            case NO: return "至少一位贤人行使了否决权。";
            case CONDITIONAL: return "至少一位贤人附条件同意，条件须先行满足。";
            case INFO: return "该询问不属于可否决事项，MAGI 仅提供情报。";
            case ERROR: return "有贤人未能给出裁决，结论不可采信。";
            default: return "";
        }
    }
}
