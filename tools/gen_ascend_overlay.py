# -*- coding: utf-8 -*-
"""生成「登神」遮罩用的贴图（第三版：中心留孔 + 宽渐变黑幕 + 细环）。

历史教训（别再踩）：
  ⛔ 透明背景上高斯模糊 = 黑边（Pillow 不做 alpha 加权）→ 全程不用模糊。
  ⛔ 径向距离按对角线归一化 + 渐变带起点太靠内 = 矩形黑幕 → 按半宽归一化。
  ⛔ alpha 有"全不透明平台区" = 屏幕中央一块硬黑圆盘 → 曲线必须全程连续变化。
  ⛔ 纯色 fill 铺满 = 宝珠被盖死、无径向层次 → 紫幕改成"中心留孔"的径向贴图。

产物：
  ① ascend_ring.png    单帧「环 + 闪电刺」，描边极细 —— 渲染端会把贴图放大数倍，
                       描边粗了在屏幕上就是几十像素的粗管子（实机截图确认）。
  ② ascend_purple.png  紫幕：中心 0.26 半径完全透明（宝珠可见），
                       0.26→0.62 smoothstep 过渡到饱和紫，颜色沿半径从亮紫渐到深紫。
  ③ ascend_black.png   黑幕主体：中心黑、向外**极宽缓**地衰减（alpha=(1-d)^0.8），
                       无平台区。渲染时从小到大连续放大 = 黑从中心涌出向外扩散，
                       最后由代码叠加一层全屏 fill 兜底到纯黑。

用法：python gen_ascend_overlay.py <输出目录>
"""

import math
import os
import sys
from PIL import Image, ImageDraw

S = 256                      # 单帧边长
R_RATIO = 0.42               # 环的外接半径 / 贴图半宽（渲染端按它换算尺寸）


def rnd_next(seed):
    return (seed * 1103515245 + 12345) & 0x7FFFFFFF


def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def draw_ring(d, cx, cy, r):
    """一圈发光环 —— 三层描边模拟光晕（不用模糊）。

    ⭐ 描边必须极细：渲染端把这张贴图放大 5~7 倍铺到屏幕上，
    贴图里 1px 在屏幕上就是 5~7px。上一版 9px 的外光晕放大后成了
    几十像素的粗紫管子，糊满整个屏幕（实机截图确认）。
    """
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(116, 68, 206, 26), width=5)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(152, 108, 240, 52), width=3)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(182, 146, 250, 84), width=1)


def make_ring():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2.0
    r = S * 0.5 * R_RATIO
    draw_ring(d, cx, cy, r)

    # 环上的分叉闪电（同样细描边）
    seed = 20260929
    for b in range(10):
        seed = rnd_next(seed)
        a = 2.0 * math.pi * (seed % 1000) / 1000.0
        seed = rnd_next(seed)
        length = 7.0 + (seed % 130) / 8.0
        seed = rnd_next(seed)
        sign = 1.0 if (seed % 2) else -1.0
        px = cx + r * math.cos(a)
        py = cy + r * math.sin(a)
        pts = [(px, py)]
        ang = a + math.pi / 2.0 * sign
        for seg in range(3):
            ang += ((seed >> (seg * 3)) % 7 - 3) * 0.20
            px += math.cos(ang) * length / 3.0
            py += math.sin(ang) * length / 3.0
            pts.append((px, py))
        d.line(pts, fill=(140, 96, 232, 38), width=3)       # 外光晕
        d.line(pts, fill=(176, 140, 248, 66), width=1)      # 核心
        d.ellipse([pts[-1][0] - 1, pts[-1][1] - 1, pts[-1][0] + 1, pts[-1][1] + 1],
                  fill=(190, 158, 250, 80))
    return img


def make_purple():
    """紫幕：**中心保留一个透明孔**（宝珠可见），紫色从四周向内包拢。

    ⭐ 为什么必须留孔：GUI 遮罩永远画在第一人称宝珠之上，
    之前的纯色 fill 铺满把宝珠完全盖死（实机截图确认）。
    留孔之后宝珠始终悬在孔中，黑幕再从孔里涌出来吞掉它 —— 演出自然衔接。

    与 vignette 的分工（交替时两层都从 0 渐入渐出，中心亮度连续）：
      vignette —— focus 阶段，孔大（0.34），紫黑、暗；
      purple   —— 紫幕阶段，孔稍小（0.26），饱和紫、亮。
    """
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    INNER = 0.24          # 这个半径以内完全透明（宝珠可见；旧版 0.26，范围略扩大）
    OUTER = 0.58          # 到这个半径已经完全不透明
    rnd = 246813579
    for y in range(S):
        for x in range(S):
            dx = (x + 0.5) / S * 2.0 - 1.0
            dy = (y + 0.5) / S * 2.0 - 1.0
            d = math.hypot(dx, dy) / math.sqrt(2.0)

            t = smoothstep((d - INNER) / (OUTER - INNER))
            rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF
            noise = ((rnd % 1000) / 1000.0 - 0.5) * 0.03
            t = max(0.0, min(1.0, t + noise * t))
            a = int(255 * t)

            # 颜色沿半径从亮紫渐到深紫：孔边亮（能量感）→ 屏幕角深（层次）
            k = t
            r = int(112 * (1.0 - k) + 54 * k)
            g = int(62 * (1.0 - k) + 22 * k)
            b = int(208 * (1.0 - k) + 128 * k)
            px[x, y] = (r, g, b, a)
    return img


def make_black():
    """黑幕主体：中心黑、向外**极宽缓**地连续衰减 —— alpha = (1-d)^0.8。

    ⭐⭐ 渐变带必须极宽：这条曲线在 d=0.5 处仍有 57%、d=0.8 处 24%、
    d=1.0 才降到 0 —— 屏幕上任何时刻都不存在"黑与不黑的分界线"，
    只有"中心更黑、四周渐浅"的连续过渡。这是"扩散但无边界"的数学保证。

    渲染端把它从 1.1×屏幕 放大到 2.6×屏幕，同时整体 alpha 从 0 涨到 1：
      - 早期：中心微暗（整体 alpha 低）—— 黑从中心"冒头"
      - 中期：中心已黑、暗区向外推 —— 方向感
      - 后期：代码再叠一层全屏 fill 兜底，保证四角也到纯黑
    """
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(S):
        for x in range(S):
            dx = (x + 0.5) / S * 2.0 - 1.0
            dy = (y + 0.5) / S * 2.0 - 1.0
            d = min(1.0, math.hypot(dx, dy))      # 按半宽归一化（边长中点 = 1.0）
            a = int(255.0 * (1.0 - d) ** 0.8)     # 极宽缓、全程连续、无平台
            px[x, y] = (18, 8, 38, a)             # 深紫黑
    return img


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    p1 = os.path.join(out_dir, "ascend_ring.png")
    make_ring().save(p1)
    print("环贴图:", p1, (S, S))

    p2 = os.path.join(out_dir, "ascend_purple.png")
    make_purple().save(p2)
    print("紫幕:", p2, (S, S))

    p3 = os.path.join(out_dir, "ascend_black.png")
    make_black().save(p3)
    print("黑幕:", p3, (S, S))

    old = os.path.join(out_dir, "ascend_ripple.png")
    if os.path.exists(old):
        os.remove(old)
        print("已删除旧的帧动画波纹:", old)


main()
