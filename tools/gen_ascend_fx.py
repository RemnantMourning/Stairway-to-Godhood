# -*- coding: utf-8 -*-
"""生成「登神」演出的两张炫酷资源（v2：修掉光圈叶片 + 表针感）。

v1 的两个 bug（实机/预览确认出戏）：
  ⛔ flare 星芒直接用 ImageDraw 覆盖像素 —— Pillow 不做 alpha 混合，
    星芒把高斯核心的 alpha **挖低**了，预览里星芒是黑的，
    整张贴图像"相机光圈的快门叶片"。
    ⇒ v2 用两个独立图层 + alpha_composite：星芒在下、核心在上，只加不减。
  ⛔ runes 每个符文是"一根指向圆心的楔形" → 转起来像钟表刻度/仪表盘。
    ⇒ v2 改成卢恩（rune）文字构造：每字形 = 一根径向主轴 + 1~3 根分枝，
      12 个字形各不相同，才是"古代符文"；星点改成少量亮星（小十字）。

产物：
  ① ascend_runes.png  符文环（渲染端旋转）
  ② ascend_flare.png  过曝闪光（黑幕前的白紫爆发）

用法：python gen_ascend_fx.py <输出目录>
"""

import math
import os
import sys
from PIL import Image, ImageDraw

S = 512                     # 贴图边长（方形，正方形绘制不变形）

RND_STATE = 20260929


def rnd():
    global RND_STATE
    RND_STATE = (RND_STATE * 1103515245 + 12345) & 0x7FFFFFFF
    return RND_STATE


def rnd_range(lo, hi):
    return lo + (rnd() % 1000) / 1000.0 * (hi - lo)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    make_runes(os.path.join(out_dir, "ascend_runes.png"))
    make_flare(os.path.join(out_dir, "ascend_flare.png"))


# ---------------------------------------------------------------- ① 符文环


def make_runes(path):
    """符文环 v2：卢恩字形（径向主轴 + 分枝），12 个各不相同。

    卢恩文字的构造规律：一根主轴（这里沿径向），枝从主轴上某点斜出
    （±45° 或切向）。这样排成一圈是"符文环"，而不是表盘刻度。
    """
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2.0

    R_RUNE = S * 0.42        # 符文环半径

    # 底层能量环（细、暗，托住符文）
    d.ellipse([cx - R_RUNE, cy - R_RUNE, cx + R_RUNE, cy + R_RUNE],
              outline=(120, 70, 210, 38), width=2)
    d.ellipse([cx - R_RUNE, cy - R_RUNE, cx + R_RUNE, cy + R_RUNE],
              outline=(185, 145, 250, 66), width=1)

    N = 12
    for i in range(N):
        ang = 2.0 * math.pi * i / N + rnd_range(-0.04, 0.04)
        ux, uy = math.cos(ang), math.sin(ang)          # 径向单位向量
        vx, vy = -math.sin(ang), math.cos(ang)         # 切向单位向量

        def to_global(r_off, t_off):
            """局部坐标（径向偏移 r_off、切向偏移 t_off）→ 全局像素。"""
            return (cx + (R_RUNE + r_off) * ux + t_off * vx,
                    cy + (R_RUNE + r_off) * uy + t_off * vy)

        ink = (152, 104, 242, 165)

        # 主轴：沿径向的一根竖笔（略带切向倾斜，避免"表针"的规整感）
        tilt = rnd_range(-2.5, 2.5)
        p0 = to_global(-14, tilt)
        p1 = to_global(12, tilt + rnd_range(-1.5, 1.5))
        d.line([p0, p1], fill=ink, width=4)

        # 1~3 根分枝：从主轴上随机一点，斜向（径向±切向混合）伸出
        for _ in range(1 + rnd() % 3):
            j0 = rnd_range(-11.0, 9.0)                 # 分枝起点在主轴上的位置
            dr = rnd_range(5.0, 12.0) * (1 if rnd() % 2 else -0.6)
            dt = rnd_range(6.0, 12.0) * (1 if rnd() % 2 else -1)
            a0 = to_global(j0, tilt)
            a1 = to_global(j0 + dr, tilt + dt)
            d.line([a0, a1], fill=ink, width=3)

        # 30% 概率加一颗符文点
        if rnd() % 10 < 3:
            pr = to_global(rnd_range(-8, 8), tilt + rnd_range(-6, 6))
            d.ellipse([pr[0] - 1.5, pr[1] - 1.5, pr[0] + 1.5, pr[1] + 1.5],
                      fill=(205, 165, 255, 200))

    # 少量亮星（小十字 + 核心点），替代 v1 的 60 个噪点
    for _ in range(9):
        a = rnd_range(0, 2.0 * math.pi)
        rr = R_RUNE + rnd_range(-26, 26)
        sx = cx + rr * math.cos(a)
        sy = cy + rr * math.sin(a)
        glow = (222, 192, 255, 190)
        d.line([(sx - 3, sy), (sx + 3, sy)], fill=glow, width=1)
        d.line([(sx, sy - 3), (sx, sy + 3)], fill=glow, width=1)
        d.ellipse([sx - 1, sy - 1, sx + 1, sy + 1], fill=(240, 224, 255, 230))

    img.save(path)
    print("符文环:", path, (S, S))


# ---------------------------------------------------------------- ② 过曝闪光


def make_flare(path):
    """过曝闪光 v2：星芒（下）+ 高斯核心（上）两图层 alpha_composite。

    星芒：6 条衍射芒，沿方向步进画渐缩渐淡的圆点 —— 从核心边缘向外
    宽度 5px→0、alpha 指数衰减，像镜头星芒而不是快门叶片。
    核心：白紫高斯，中心过曝、向外染紫。
    """
    # ---- 图层 A：星芒（先画，在下面）----
    star = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ds = ImageDraw.Draw(star)
    cx = cy = S / 2.0
    R_MAX = S * 0.48
    for k in range(6):
        ang = k * math.pi / 3.0
        dx, dy = math.cos(ang), math.sin(ang)
        t = 8.0
        while t < R_MAX:
            f = 1.0 - t / R_MAX                       # 1（近核心）→ 0（末端）
            w = 0.6 + 4.4 * f * f                     # 渐缩
            a = int(210.0 * (f ** 2.2))               # 指数衰减
            if a > 2:
                xx = cx + dx * t
                yy = cy + dy * t
                ds.ellipse([xx - w, yy - w, xx + w, yy + w],
                           fill=(226, 205, 255, a))
            t += 1.5

    # ---- 图层 B：高斯核心（后画，在上面）----
    core = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = core.load()
    SIGMA = 0.15
    for y in range(S):
        for x in range(S):
            dx = (x + 0.5) / S - 0.5
            dy = (y + 0.5) / S - 0.5
            d2 = (dx * dx + dy * dy) / (2.0 * SIGMA * SIGMA)
            if d2 > 12.0:
                continue
            v = math.exp(-d2)
            # 中心过曝白、向外染紫（白→淡紫→深紫）
            r = int(255 * (1.0 - 0.20 * d2))
            g = int(242 * (1.0 - 0.38 * d2))
            b = int(255 * (1.0 - 0.10 * d2))
            a = int(255 * min(1.0, v * 1.7))
            if a > 0:
                px[x, y] = (max(0, r), max(0, g), max(0, b), a)

    # ---- 合成：核心叠在星芒上（只加不减，星芒不会再挖低核心）----
    img = Image.alpha_composite(star, core)
    img.save(path)
    print("过曝闪光:", path, (S, S))


main()
