# -*- coding: utf-8 -*-
"""生成「登神聚焦」遮罩贴图 —— 中心透明、四周紫黑，像一道锥形隧道把周围画面挡住。

用途：第三阶段「登神长阶」时铺满全屏，只留中间的宝珠可见。

设计要点：
  - **中心完全透明**（alpha=0）且范围足够大 —— 宝珠就悬在屏幕正中，
    透明区太小会把宝珠也糊掉；这里取到半径 0.34 都全透明。
  - 0.34 → 0.82 之间平滑（smoothstep）过渡到紫黑，**不留硬边**。
  - 边缘是**紫黑**（深紫偏黑），不是纯黑 —— 和整体紫色调一致，更像"能量"而不是"黑幕"。
  - 过渡带加一点微弱噪声，避免大面积渐变的"塑料感"。

用法：python gen_ascend_vignette.py <输出png>
"""

import math
import os
import sys
from PIL import Image

S = 512


def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def main():
    out = sys.argv[1]
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()

    INNER = 0.30          # 这个半径以内完全透明（宝珠可见）
    OUTER = 0.74          # 到这个半径已经完全不透明（比旧版 0.34/0.82 范围更大）
    EDGE = (26, 6, 52)    # 边缘的紫黑色

    rnd = 987654321
    for y in range(S):
        for x in range(S):
            # 到中心的归一化距离（0 = 中心, 1 = 边角）
            dx = (x + 0.5) / S * 2.0 - 1.0
            dy = (y + 0.5) / S * 2.0 - 1.0
            d = math.hypot(dx, dy) / math.sqrt(2.0)

            t = smoothstep((d - INNER) / (OUTER - INNER))
            rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF
            noise = ((rnd % 1000) / 1000.0 - 0.5) * 0.045
            t = max(0.0, min(1.0, t + noise * t))

            a = int(255 * t)
            # 越靠边越"紫"一点再转黑：让过渡带尾部偏紫、最外圈压到近黑
            k = t
            r = int(EDGE[0] * (0.75 + 0.25 * k) + 46 * (1.0 - k) * k)
            g = int(EDGE[1] * (0.75 + 0.25 * k))
            b = int(EDGE[2] * (0.80 + 0.20 * k) + 40 * (1.0 - k) * k)
            px[x, y] = (r, g, b, a)

    img.save(out)
    print("登神遮罩贴图已生成:", out, img.size)


main()
