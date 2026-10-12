#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MAGI-EVA 冒烟测试 / smoke test
==============================

用真实的 Jev 决策接口跑一遍 MAGI 三贤人流程，验证：

  1. 闸门问题（noul）     —— 该问题是否属于 yes/no 类
  2. 三贤人裁决（choice）  —— MELCHIOR-1 / BALTHASAR-2 / CASPER-3 各自给出 yes / no / conditional
  3. 确信度（score）       —— 0-9 的确定性打分
  4. 合议（vote）          —— 誤差 / 情報 / 拒絶 / 状態 / 合意
  5. 遥测（usage）         —— 每次调用的 latency / tokens / cost

请求体形状与 Android 端 `com.nerv.magi.jev.JevQuestions` 完全一致，
所以这个脚本跑通 = Java 实现的设计是对的。

用法:
    python smoke_test.py [问题] [--provider openrouter|bocha|typesafe|vercel|zen]
                         [--key KEY] [--json]
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- 供应商预设
# 与 core/Prefs.java 中的常量一一对应
PROVIDERS = {
    # base url, model, path, 是否需要 OpenRouter 归因头
    "openrouter": ("https://openrouter.ai/api", "typesafe/jev-1.13", "/alpha/decisions"),
    "bocha":      ("https://jev.bocha.cn",       "bocha-jev-v1",     "/v1/systemone"),
    "typesafe":   ("https://api.typesafe.ai",    "jev-latest",       "/v1/systemone"),
    "vercel":     ("https://ai-gateway.vercel.sh/typesafe", "typesafe-ai/jev", "/v1/systemone"),
    "zen":        ("https://opencode.ai/zen",    "jev-1.13",         "/v1/systemone"),
}

# ---------------------------------------------------------------- 三贤人人格
# 与 PersonaActivity 的出厂默认值一致（EVA 正典设定）
DEFAULT_PERSONAS = [
    ("MELCHIOR", 1, "melchior",
     "You are a scientist. Your goal is to further our understanding of the universe "
     "and advance our technological progress. You judge only by evidence, logic and "
     "verifiable fact; you are unmoved by appeals to feeling, loyalty or hope."),
    ("BALTHASAR", 2, "balthasar",
     "You are a mother. Your goal is to protect your children and ensure their well-being. "
     "You judge by what keeps people safe and whole; you accept short-term cost to prevent "
     "lasting harm, and you refuse anything that endangers the people in your care."),
    ("CASPER", 3, "casper",
     "You are a woman. Your goal is to pursue love, dreams and desires. "
     "You judge by what is true to the heart and to the self; you weigh desire, dignity and "
     "the honesty of feeling above cold utility."),
]

# 与 MAGI 参考实现 components/response.js 完全一致的配色
STATUS = {
    "yes":         ("合 意", "#52e691"),
    "no":          ("拒 絶", "#a41413"),
    "conditional": ("状 態", "#ff8d00"),
    "info":        ("情 報", "#3caee0"),
    "error":       ("誤 差", "gray"),
}

BACKGROUND_NOTE = " Facts given in background are provided context, not off-topic."


# ================================================================ 题目构造
# 严格对应 JevQuestions.java 的 noul() / choice() / score()

def _noul(instructions, t, f):
    return {"type": "noul", "instructions": instructions + BACKGROUND_NOTE,
            "criteria": {"true": t, "false": f}}


def _choice(instructions, criteria):
    return {"type": "choice", "instructions": instructions + BACKGROUND_NOTE,
            "criteria": criteria}


def _score(instructions, levels):
    return {"type": "score", "instructions": instructions + BACKGROUND_NOTE,
            "criteria": levels}


def gate_questions():
    """闸门：这句话是不是一个 yes/no 问题。"""
    return {
        "is_yes_no": _noul(
            "Read the user's proposition in the state. Is it linguistically a question that "
            "can be settled with a plain yes or no? Judge the FORM of the proposition, not how "
            "hard it would be to answer. A command, a request, a prediction, a verdict on a "
            "proposal, or a factual claim all count as yes/no. A question asking for an open "
            "description, a list, a number, or an explanation does NOT.",
            "The proposition can be settled with yes or no: a factual claim, a prediction, "
            "a proposal to accept or reject, a command, or a yes/no question.",
            "The proposition demands open-ended content: how, why, what, list, describe, "
            "or a numeric estimate with no yes/no reading."),
        "is_decision": _noul(
            "Is the user's proposition asking the system to reach a decision or a verdict "
            "rather than merely to recall information?",
            "It asks for a decision, judgement, approval, or verdict.",
            "It merely asks to recall or restate something already known."),
    }


def unit_questions(persona, question, unit_label):
    """单个贤人的裁决题目：verdict + certainty。"""
    frame = (
        "You are {label}, one of the three subsystems of the MAGI system. "
        "The MAGI system is a cluster of three AI supercomputers that advise on hard "
        "decisions; each subsystem embodies one fragment of its creator's personality. "
        "Your fragment is described below and you MUST judge through it, even when it "
        "conflicts with the other two subsystems.\n"
        "Your personality: {persona}\n"
        "The user's proposition is: \"{question}\"\n"
    ).format(label=unit_label, persona=persona, question=question)

    verdict = _choice(
        frame +
        "Decide, through your personality, whether the proposition should be ACCEPTED. "
        "Answer 'yes' only if you accept it unconditionally. Answer 'no' if you reject it. "
        "Answer 'conditional' if you accept it only under stated conditions or with "
        "reservations - that is the honest answer whenever your fragment can see a real "
        "cost that must be handled first. Do not hedge toward 'conditional' to avoid "
        "commitment; choose it only when a genuine condition exists.",
        {
            "yes": "Accept unconditionally. Nothing your personality values is violated and "
                   "no precondition is needed.",
            "no": "Reject. Your personality finds this unacceptable, false, or harmful.",
            "conditional": "Accept only if specific conditions are met, or accept with "
                           "clearly stated reservations that must be addressed.",
        })

    certainty = _score(
        frame +
        "How certain are you of your own verdict on this proposition? Match the strength "
        "of the evidence and the depth of your conviction as this personality - not how "
        "strongly you feel, but how well-founded your verdict is.",
        [
            "No basis at all; pure guess.",
            "Very weak; almost no support.",
            "Weak; a faint lean only.",
            "Slight; more doubt than conviction.",
            "Even; as much against as for.",
            "Moderate; a real lean with real doubt.",
            "Fairly certain; the lean is well supported.",
            "Certain; clear support, little doubt.",
            "Very certain; strong support, negligible doubt.",
            "Absolute; the verdict could not be otherwise.",
        ])

    return {"verdict": verdict, "certainty": certainty}


def build_state(question, unit_label, persona):
    """Jev state。magi 段是自定义上下文；chat 段留作兼容锚点。"""
    return {
        "magi": {
            "system": "MAGI",
            "subsystem": unit_label,
            "persona": persona,
            "question": question,
        },
        "chat": {
            "relationship": "The user is consulting the MAGI system for a decision.",
            "messages": [{"from": "me", "text": question}],
            "latest_from": "me",
        },
    }


# ================================================================ HTTP
def post(url, key, body, timeout=60, proxy=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Authorization", "Bearer " + key)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if "openrouter.ai" in url:
        req.add_header("HTTP-Referer", "https://magi-eva.local")
        req.add_header("X-Title", "MAGI EVA")
    if proxy:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
        resp = opener.open(req, timeout=timeout)
    else:
        resp = urllib.request.urlopen(req, timeout=timeout)
    return json.loads(resp.read().decode("utf-8"))


def ask(endpoint, key, model, state, questions, proxy=None):
    body = {"model": model, "state": state, "questions": questions}
    t0 = time.time()
    try:
        r = post(endpoint, key, body, proxy=proxy)
        return {
            "ok": True,
            "answers": r.get("answers", {}),
            "model": r.get("model"),
            "usage": r.get("usage", {}),
            "latency_ms": int((time.time() - t0) * 1000),
            "error": None,
        }
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        return {"ok": False, "answers": {}, "usage": {}, "latency_ms": int((time.time() - t0) * 1000),
                "error": "HTTP %s: %s" % (e.code, detail)}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "answers": {}, "usage": {}, "latency_ms": int((time.time() - t0) * 1000),
                "error": str(e)}


# ================================================================ 合议
def vote(unit_statuses, errors):
    """与 MAGI 参考实现 main.py:response_status 完全同序的一票否决制。"""
    if errors:
        return "error"
    if any(s == "no" for s in unit_statuses):
        return "no"
    if any(s == "conditional" for s in unit_statuses):
        return "conditional"
    if unit_statuses and all(s == "yes" for s in unit_statuses):
        return "yes"
    return "info"


def extract_choice(a):
    if not a:
        return None
    return a.get("choice")


def extract_prob(a):
    if not a:
        return None
    return a.get("probabilities")


# ================================================================ main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("question", nargs="?", default="我们应该启动人类补完计划吗？")
    ap.add_argument("--provider", default="openrouter", choices=list(PROVIDERS))
    ap.add_argument("--key", default=None)
    ap.add_argument("--proxy", default=os.environ.get("MAGI_PROXY", ""))
    ap.add_argument("--json", action="store_true", help="只输出 JSON 结果")
    args = ap.parse_args()

    key = args.key or os.environ.get("JEV_KEY")
    if not key:
        kf = os.path.expanduser("~/Desktop/key.txt")
        if os.path.exists(kf):
            with open(kf, encoding="utf-8") as fh:
                key = fh.read().split("\n")[0].strip()
    if not key:
        print("no key: pass --key or set JEV_KEY", file=sys.stderr)
        return 2

    base, model, path = PROVIDERS[args.provider]
    endpoint = base.rstrip("/") + path
    proxy = args.proxy or None

    log = (lambda *a: None) if args.json else print
    log("=" * 74)
    log(" MAGI-EVA 冒烟测试")
    log("=" * 74)
    log(" provider : %s" % args.provider)
    log(" endpoint : %s" % endpoint)
    log(" model    : %s" % model)
    log(" question : %s" % args.question)
    log("")

    report = {"question": args.question, "provider": args.provider, "endpoint": endpoint,
              "model": model, "units": [], "usage_total": {"input_tokens": 0, "output_tokens": 0, "cost": 0.0}}

    # ---- 1. 闸门
    log("[1/4] 闸门 GATE ...")
    g = ask(endpoint, key, model, build_state(args.question, "MAGI-CORE", ""),
            gate_questions(), proxy)
    if not g["ok"]:
        print("GATE FAILED: %s" % g["error"], file=sys.stderr)
        return 1
    is_yes_no = (g["answers"].get("is_yes_no", {}).get("noul") or 0) >= 0.5
    is_decision = (g["answers"].get("is_decision", {}).get("noul") or 0) >= 0.5
    log("      is_yes_no  = %s (%.2f)" % (is_yes_no, g["answers"].get("is_yes_no", {}).get("noul", 0)))
    log("      is_decision= %s (%.2f)   %dms" % (is_decision, g["answers"].get("is_decision", {}).get("noul", 0), g["latency_ms"]))
    report["gate"] = {"is_yes_no": is_yes_no, "is_decision": is_decision,
                      "latency_ms": g["latency_ms"], "usage": g["usage"]}

    # ---- 2/3. 三贤人
    units = []
    total = {"input_tokens": 0, "output_tokens": 0, "cost": 0.0}
    for tok, num, slug, persona in DEFAULT_PERSONAS:
        label = "%s • %d" % (tok, num)
        log("")
        log("[2/4] %s 裁决中 ..." % label)
        r = ask(endpoint, key, model,
                build_state(args.question, label, persona),
                unit_questions(persona, args.question, label), proxy)
        u = {"name": label, "slug": slug, "order": num, "ok": r["ok"],
             "latency_ms": r["latency_ms"], "usage": r["usage"], "error": r["error"]}
        if r["ok"]:
            v = r["answers"].get("verdict", {})
            c = r["answers"].get("certainty", {})
            u["verdict"] = v.get("choice")
            u["confidence"] = v.get("confidence")
            u["probabilities"] = v.get("probabilities")
            u["certainty"] = c.get("score")
            u["certainty_confidence"] = c.get("confidence")
            u["raw"] = r["answers"]
            st = u["verdict"] if u["verdict"] in STATUS else "info"
            log("      verdict=%s  conf=%.2f  certainty=%s  %dms" %
                (u["verdict"], u["confidence"] or 0, u["certainty"], r["latency_ms"]))
            log("      probs=%s" % json.dumps(u["probabilities"], ensure_ascii=False))
            u["status"] = st
            for k in total:
                total[k] += (r["usage"] or {}).get(k, 0) or 0
        else:
            u["status"] = "error"
            log("      FAILED: %s" % r["error"])
        units.append(u)

    # ---- 4. 合议
    statuses = [x["status"] for x in units]
    errors = [x for x in units if x["status"] == "error"]
    if not is_yes_no and not errors:
        overall = "info"
    else:
        overall = vote(statuses, errors)
    text, color = STATUS[overall]

    log("")
    log("[3/4] 合议 VOTE")
    log("      %s | %s | %s" % (statuses[0], statuses[1], statuses[2]))
    log("")
    log("[4/4] 结论")
    log("      >>> %s   (%s)  <<<" % (text.replace(" ", ""), overall.upper()))
    total_cost = sum((x.get("usage") or {}).get("cost", 0) or 0 for x in units) + \
        (g["usage"] or {}).get("cost", 0)
    log("      total cost $%.6f | total latency %dms" %
        (total_cost, g["latency_ms"] + sum(x["latency_ms"] for x in units)))
    log("=" * 74)

    report["units"] = units
    report["overall"] = overall
    report["overall_text"] = text
    report["overall_color"] = color
    report["total_cost_usd"] = total_cost
    report["total_latency_ms"] = g["latency_ms"] + sum(x["latency_ms"] for x in units)

    if args.json:
        out = os.path.join(HERE, "smoke_result.json")
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
        print(json.dumps({"overall": overall, "units": [u.get("verdict") for u in units],
                          "cost": total_cost, "latency_ms": report["total_latency_ms"]},
                         ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
