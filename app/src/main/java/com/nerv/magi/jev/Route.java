package com.nerv.magi.jev;

/** 出错的路由名，用于拼出用户看得懂的错误文案。 */
public final class Route {

    private Route() {
    }

    /** 判断路：Jev 决策接口，三贤人裁决与闸门都走这里。 */
    public static final String JUDGE = "判断接口";
    /** 回复路：OpenAI 兼容 chat/completions，用于生成叙述与连通性测试。 */
    public static final String REPLY = "回复接口";
    /** 视觉路：多模态接口，暂未接入主流程。 */
    public static final String VISION = "视觉接口";
}
