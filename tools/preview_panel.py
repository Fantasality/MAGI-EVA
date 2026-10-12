#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MAGI 面板几何预览 / geometry preview
====================================

把 `ui/MagiPanelView.java` 的布局数学原样搬到 Python 里，渲染成 PNG，
用来在没有设备的情况下肉眼核对三贤人的互锁几何。

为什么需要它：面板的每个区域都由参考实现的 CSS Grid 命名线推导而来
（casper = 第2→第4条线、balthasar = 第3→第6条线、melchior = 第5→第7条线），
这种"线号换算"最是容易差一位，而画错了在代码里看不出来、只有画出来才知道。

依赖：只用标准库（zlib + struct 手写 PNG），不需要 Pillow。

用法:
    python tools/preview_panel.py            # 渲染冒烟测试那一局的真实状态
    python tools/preview_panel.py --idle      # 渲染待机态
"""

import argparse
import math
import os
import struct
import zlib

# ---------------------------------------------------------------- 与 Java 一致
INSET = 20.0                                        # INSET_DP
COL_FRACS = [0.0, 0.33333, 0.41667, 0.58333, 0.66667, 1.0]   # COL_FRACS
ROW_FRACS = [0.0, 0.25, 0.5, 0.625, 1.0]                     # ROW_FRACS

MELCHIOR_POLY = [0.35, 0, 1, 0, 1, 1, 0, 1, 0, 0.44]
BALTHASAR_POLY = [0, 0, 1, 0, 1, 0.8, 0.75, 1, 0.25, 1, 0, 0.8]
CASPER_POLY = [0, 0, 0.65, 0, 1, 0.44, 1, 1, 0, 1]

CONN_H = 10.0          # CONN_HEIGHT_DP
CONN_MARGIN = 0.10     # 各外延 10% 容器宽
RULE_H = 4.0           # RULE_HEIGHT_DP
BORDER = 2.0

ORANGE = (0xFF, 0x8D, 0x00)
STRIPE_DARK = (0x14, 0x0B, 0x02)
HEADER_GREEN = (0x27, 0x75, 0x47)
YES_GREEN = (0x52, 0xE6, 0x91)
NO_RED = (0xA4, 0x14, 0x13)
INFO_BLUE = (0x3C, 0xAE, 0xE0)
COND_GREEN = (0x82, 0xCD, 0x68)
BLACK = (0, 0, 0)

STATUS_COLOR = {
    "yes": YES_GREEN, "no": NO_RED, "info": INFO_BLUE,
    "error": BLACK, "conditional": None,      # conditional 走斜纹
}


# ------------------------------------------------------------------ 光栅化器
class Canvas:
    def __init__(self, w, h, bg=BLACK):
        self.w, self.h = w, h
        self.px = bytearray(bg * (w * h))

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 3
            self.px[i:i + 3] = bytes(c)

    def rect(self, x0, y0, x1, y1, c):
        x0, x1 = int(round(min(x0, x1))), int(round(max(x0, x1)))
        y0, y1 = int(round(min(y0, y1))), int(round(max(y0, y1)))
        for y in range(max(0, y0), min(self.h, y1)):
            i = (y * self.w + max(0, x0)) * 3
            row = bytes(c) * max(0, min(self.w, x1) - max(0, x0))
            self.px[i:i + len(row)] = row

    def stroke_rect(self, x0, y0, x1, y1, c, t=BORDER):
        self.rect(x0, y0, x1, y0 + t, c)
        self.rect(x0, y1 - t, x1, y1, c)
        self.rect(x0, y0, x0 + t, y1, c)
        self.rect(x1 - t, y0, x1, y1, c)

    def poly(self, pts, c):
        """扫描线填充多边形。"""
        self.poly_func(pts, lambda x, y: c)

    def poly_func(self, pts, func):
        """扫描线填充多边形，颜色由 func(x, y) 逐像素决定（用于斜纹裁切）。"""
        if len(pts) < 3:
            return
        ys = [p[1] for p in pts]
        y0, y1 = int(math.floor(min(ys))), int(math.ceil(max(ys)))
        n = len(pts)
        for y in range(max(0, y0), min(self.h, y1)):
            yc = y + 0.5
            xs = []
            for i in range(n):
                ax, ay = pts[i]
                bx, by = pts[(i + 1) % n]
                if (ay <= yc < by) or (by <= yc < ay):
                    t = (yc - ay) / (by - ay)
                    xs.append(ax + t * (bx - ax))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                xa, xb = int(round(xs[i])), int(round(xs[i + 1]))
                for x in range(max(0, xa), min(self.w, xb)):
                    self.set(x, y, func(x + 0.5, y + 0.5))

    def poly_stroke(self, pts, c, t=BORDER):
        n = len(pts)
        for i in range(n):
            ax, ay = pts[i]
            bx, by = pts[(i + 1) % n]
            steps = int(max(abs(bx - ax), abs(by - ay))) + 1
            for s in range(steps + 1):
                k = s / steps
                self.rect(ax + (bx - ax) * k, ay + (by - ay) * k, 
                          ax + (bx - ax) * k + t, ay + (by - ay) * k + t, c)

    def save_png(self, path):
        raw = bytearray()
        for y in range(self.h):
            raw.append(0)
            raw += self.px[y * self.w * 3:(y + 1) * self.w * 3]

        def chunk(tag, data):
            c = struct.pack(">I", len(data)) + tag + data
            return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        png += chunk(b"IEND", b"")
        with open(path, "wb") as f:
            f.write(png)


# --------------------------------------------------------------------- 布局
def layout(W, H):
    X0, X1 = INSET, W - INSET
    Y0, Y1 = INSET, H - INSET
    cw, ch = X1 - X0, Y1 - Y0
    LX = [X0 + f * cw for f in COL_FRACS]
    RY = [Y0 + f * ch for f in ROW_FRACS]
    R = {
        "headerL":   (BORDER, RY[0], LX[1], RY[1]),
        "headerR":   (LX[4], RY[0], W - BORDER, RY[1]),
        "status":    (LX[0], RY[1], LX[1], RY[2]),
        "response":  (LX[4], RY[1], LX[5], RY[2]),
        "title":     (LX[2], RY[3], LX[3], RY[4]),
        "casper":    (LX[0], RY[2], LX[2], RY[4]),
        "balthasar": (LX[1], RY[0], LX[4], RY[3]),
        "melchior":  (LX[3], RY[3], LX[5], RY[4]),
        "conn_cb":   (LX[1], RY[2], LX[2], RY[3]),
        "conn_cm":   (LX[2], RY[3], LX[3], RY[4]),
        "conn_bm":   (LX[3], RY[2], LX[4], RY[3]),
    }
    return R, LX, RY, cw, ch


def poly_pts(rect, frac):
    x0, y0, x1, y1 = rect
    w, h = x1 - x0, y1 - y0
    return [(x0 + frac[i] * w, y0 + frac[i + 1] * h) for i in range(0, len(frac), 2)]


def draw_connection(cv, rect, degrees, cw, color):
    x0, y0, x1, y1 = rect
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    length = (x1 - x0) + 2 * CONN_MARGIN * cw
    rad = math.radians(degrees)
    dx, dy = math.cos(rad), math.sin(rad)
    px, py = -dy, dx
    hl, ht = length / 2, CONN_H / 2
    pts = [(cx + dx * hl + px * ht, cy + dy * hl + py * ht),
           (cx + dx * hl - px * ht, cy + dy * hl - py * ht),
           (cx - dx * hl - px * ht, cy - dy * hl - py * ht),
           (cx - dx * hl + px * ht, cy - dy * hl + py * ht)]
    cv.poly(pts, color)


def stripe_func():
    """56° 双色绿斜纹的逐像素取色函数，对应 CSS
    repeating-linear-gradient(56deg,#52e691 0 30px,#82cd68 30px 60px)。

    做法与 Java 端 drawConditionalStripes 一致：把坐标反向旋转 56°，
    再看落在哪个 30px 条带里。旋转保证条带边界就是 56° 斜线。
    """
    rad = math.radians(56)
    dx, dy = math.cos(rad), math.sin(rad)

    def f(x, y):
        v = x * dx + y * dy          # 沿 56° 方向的投影
        return YES_GREEN if (v % 60.0) < 30.0 else COND_GREEN

    return f


def render(W, H, states, out, idle=False):
    cv = Canvas(W, H, BLACK)
    R, LX, RY, cw, ch = layout(W, H)

    # 外框
    cv.stroke_rect(0, 0, W, H, ORANGE, BORDER)

    # 连接杆（压在贤人块之下）
    draw_connection(cv, R["conn_cb"], -54, cw, ORANGE)
    draw_connection(cv, R["conn_cm"], 0, cw, ORANGE)
    draw_connection(cv, R["conn_bm"], 54, cw, ORANGE)

    # 页眉横线（質 問 / 解 決）
    for key in ("headerL", "headerR"):
        x0, y0, x1, y1 = R[key]
        y = y0 + 2
        for _ in range(2):
            cv.rect(x0, y, x1, y + RULE_H, HEADER_GREEN)
            y += RULE_H + 4
        # 中间留给文字
        y += 26
        for _ in range(2):
            cv.rect(x0, y, x1, y + RULE_H, HEADER_GREEN)
            y += RULE_H + 4

    # 三贤人块：顺序 melchior → balthasar → casper（后者压先者）
    blocks = [("melchior", MELCHIOR_POLY), ("balthasar", BALTHASAR_POLY), ("casper", CASPER_POLY)]
    stripes = stripe_func()
    for slug, frac in blocks:
        st = "info" if idle else states[slug]
        pts = poly_pts(R[slug], frac)
        col = STATUS_COLOR[st]
        if col is None:
            cv.poly_func(pts, stripes)        # conditional：56° 双色绿斜纹，由多边形裁剪
        else:
            cv.poly(pts, col)
        cv.poly_stroke(pts, ORANGE, 2)

    # 结论框（右对齐、垂直居中）
    st = overall_state(states, idle)
    text = {"yes": "合 意", "no": "拒 絶", "conditional": "状 態",
            "info": "情 報", "error": "誤 差"}[st]
    # components/response.js 的 getStatusColor
    col = {"yes": YES_GREEN, "no": NO_RED, "conditional": ORANGE,
           "info": INFO_BLUE, "error": (0x80, 0x80, 0x80)}[st]
    x0, y0, x1, y1 = R["response"]
    bw, bh = 150, 54
    oL, oT = x1 - bw, (y0 + y1) / 2 - bh / 2
    cv.stroke_rect(oL, oT, oL + bw, oT + bh, col, 2)
    cv.stroke_rect(oL + 4, oT + 4, oL + bw - 4, oT + bh - 4, col, 2)

    # 状态读数占位（5 行文字），以及标题 MAGI 的占位块 —— 只验几何，不渲染字形
    sx0, sy0, sx1, sy1 = R["status"]
    line_h = (sy1 - sy0) / 5.0
    for i in range(5):
        w = (sx1 - sx0) * (0.62 if i == 0 else 0.46)
        cv.rect(sx0, sy0 + i * line_h + 2, sx0 + w, sy0 + i * line_h + line_h - 3, ORANGE)

    tx0, ty0, tx1, ty1 = R["title"]
    tw = (tx1 - tx0) * 0.62
    th = (ty1 - ty0) * 0.30
    cv.rect((tx0 + tx1) / 2 - tw / 2, (ty0 + ty1) / 2 - th / 2,
            (tx0 + tx1) / 2 + tw / 2, (ty0 + ty1) / 2 + th / 2, ORANGE)

    cv.save_png(out)
    return text


def overall_state(states, idle):
    if idle:
        return "info"
    ss = list(states.values())
    if "error" in ss:
        return "error"
    if "no" in ss:
        return "no"
    if "conditional" in ss:
        return "conditional"
    if all(s == "yes" for s in ss):
        return "yes"
    return "info"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--idle", action="store_true")
    ap.add_argument("-W", type=int, default=900)
    args = ap.parse_args()
    H = args.W // 2

    # 冒烟测试那一局的真实状态：MELCHIOR 附条件，BALTHASAR / CASPER 否决
    states = {"melchior": "conditional", "balthasar": "no", "casper": "no"}
    if args.idle:
        states = {k: "info" for k in states}

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "preview_idle.png" if args.idle else "preview_magi.png")
    text = render(args.W, H, states, out, idle=args.idle)
    print("已渲染 %dx%d → %s" % (args.W, H, out))
    print("三贤人状态:", states)
    print("合议结论 :", text)


if __name__ == "__main__":
    main()
