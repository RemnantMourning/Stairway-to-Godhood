# -*- coding: utf-8 -*-
"""生成占位界面中心的那颗「星辰」贴图 —— 蓝紫色光晕 + 十字星芒。

设计要点（沿用本项目遮罩贴图的教训）：
  ⛔ 透明背景上做高斯模糊 = 黑边（Pillow 不做 alpha 加权）→ 全程不用模糊。
  ⛔ alpha 不能有恒定平台区 → 曲线从中心连续向外衰减。
  ⛔ 图层叠加必须 alpha_composite —— ImageDraw 是"替换像素"，
    后画的低 alpha 笔画会把高 alpha 区域挖低（上一版闪光翻车的原因）。
  ⭐ 贴图在屏幕上会被放大数倍 ⇒ 描边要细。

产物：no_content_star.png（512²）
  ① 蓝紫光晕（逐像素 exp 衰减，外紫内蓝）
  ② 白蓝核心（过曝感）
  ③ 十字星芒（4 长 + 4 短，渐缩渐淡）—— 单独图层，最后合成到光晕之上

用法：python gen_no_content_star.py <输出目录>
"""

import math
import os
import sys
from PIL import Image, ImageDraw

S = 512


def make_glow():
    """蓝紫光晕 + 白蓝核心：逐像素 exp 衰减，中心连续、无平台。"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    # 两层高斯：内层小而亮（蓝白），外层大而淡（蓝紫）
    SIG_IN = 0.055      # 内晕（相对半宽）
    SIG_OUT = 0.20      # 外晕
    for y in range(S):
        for x in range(S):
            dx = (x + 0.5) / S - 0.5
            dy = (y + 0.5) / S - 0.5
            r2 = dx * dx + dy * dy
            v_in = math.exp(-r2 / (2.0 * SIG_IN * SIG_IN))
            v_out = math.exp(-r2 / (2.0 * SIG_OUT * SIG_OUT))
            if v_in + v_out < 0.004:
                continue
            # 内层偏白蓝、外层偏蓝紫
            r = int(150 * v_in + 96 * v_out)
            g = int(190 * v_in + 70 * v_out)
            b = int(255 * v_in + 240 * v_out)
            a = int(255 * min(1.0, v_in * 1.2 + v_out * 1.05))
            px[x, y] = (min(255, r), min(255, g), min(255, b), max(0, min(255, a)))
    return img


def make_spikes():
    """十字星芒：4 条长芒（水平/竖直）+ 4 条短斜芒，渐缩渐淡。"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2.0

    def spike(angle_deg, length_ratio, width_max):
        ang = math.radians(angle_deg)
        dx, dy = math.cos(ang), math.sin(ang)
        r_max = S * 0.5 * length_ratio
        t = 2.0
        while t < r_max:
            f = 1.0 - t / r_max                 # 1（近核心）→ 0（末端）
            w = 0.5 + width_max * f * f         # 渐缩
            a = int(200.0 * (f ** 2.4))         # 指数衰减
            if a > 2:
                x = cx + dx * t
                y = cy + dy * t
                d.ellipse([x - w, y - w, x + w, y + w], fill=(200, 215, 255, a))
            t += 1.2

    for k in range(4):                          # 4 条长芒
        spike(k * 90.0, 0.46, 2.6)
    for k in range(4):                          # 4 条短斜芒
        spike(45.0 + k * 90.0, 0.24, 1.8)
    return img


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    glow = make_glow()
    spikes = make_spikes()
    # 星芒在下、光晕在上，合成时只加不减（核心不会被星芒挖低）
    star = Image.alpha_composite(spikes, glow)

    path = os.path.join(out_dir, "no_content_star.png")
    star.save(path)
    print("星辰贴图:", path, (S, S))


main()
