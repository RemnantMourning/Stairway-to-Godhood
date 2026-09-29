# -*- coding: utf-8 -*-
"""生成跑道形链环的贴图 —— 紫色半透明水晶。

水晶质感 vs 金属：金属是"拉丝 + 柔和高光"，水晶是"通透 + 多棱面 + 强明暗对比 +
碎裂折射亮纹"。所以这张贴图：
  - 底色半透明（透出后面的法阵/草地）
  - 沿截面宽(v)有强烈的明暗跳变（晶体棱面转折，不是柔和渐变）
  - 沿周长(u)有几条不规则的斜向"折射裂纹"，模拟水晶内部的折射纹路
  - 少数高亮的碎钻反光点（棱面顶点的闪光）

UV 约定（与 MagicCircleRenderer 一致）：u=沿周长，v=跨截面宽。
"""

import math
import os
import sys
from PIL import Image, ImageFilter

S = 256
SS = 2
W = S * SS


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(int(lerp(c1[i], c2[i], t)) for i in range(3))


def main():
    out = sys.argv[1]
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    px = img.load()

    # 纯紫水晶配色：从暗紫到亮紫，最高光仍是紫（不掺粉/白）
    DEEP = (40, 10, 90)
    MID = (88, 40, 168)
    BODY = (150, 86, 228)
    LITE = (196, 138, 255)
    SPEC = (226, 186, 255)

    # 棱面转折点：在 v 上设几个"晶面"，相邻面明暗突变
    # 用分段线性，但转折处用较陡的过渡模拟棱线
    def crystal_shade(v):
        # 返回该 v 处的 (颜色, 明暗系数)
        if v < 0.12:
            return mix(DEEP, MID, v / 0.12), 0.55
        if v < 0.24:
            return mix(MID, BODY, (v - 0.12) / 0.12), 0.80
        if v < 0.38:
            return mix(BODY, LITE, (v - 0.24) / 0.14), 1.10
        if v < 0.50:
            return mix(LITE, SPEC, (v - 0.38) / 0.12), 1.35   # 主高光棱面
        if v < 0.62:
            return mix(SPEC, LITE, (v - 0.50) / 0.12), 1.05
        if v < 0.76:
            return mix(LITE, BODY, (v - 0.62) / 0.14), 0.82
        if v < 0.88:
            return mix(BODY, MID, (v - 0.76) / 0.12), 0.62
        return mix(MID, DEEP, (v - 0.88) / 0.12), 0.48

    rnd = 12345
    # 预生成几条"折射裂纹"：斜向亮纹，u 方向走向
    cracks = []
    for _ in range(5):
        crack_u = rnd % W / W
        crack_slope = ((rnd % 2000) - 1000) / 1000.0 * 0.35
        crack_w = 0.015 + (rnd % 40) / 1000.0
        cracks.append((crack_u, crack_slope, crack_w))
        rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF

    for v_i in range(W):
        v = v_i / float(W - 1)
        base, shade = crystal_shade(v)
        for u_i in range(W):
            u = u_i / float(W - 1)
            # 折射裂纹：亮纹（裂纹处更透更亮）
            crack = 0.0
            for cu, sl, cw in cracks:
                du = abs(u - (cu + v * sl))
                du = min(du, 1.0 - du)          # u 周期性
                if du < cw:
                    crack = max(crack, 1.0 - du / cw)
            # 碎钻反光点
            rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF
            sparkle = 1.0 if (rnd % 2203) < 6 else 0.0

            k = crack * 0.6 + sparkle * 1.0
            # 反光只提亮，不往白色偏：红绿按比例压，蓝保持（保持"紫"色相）
            r = min(255, int(base[0] * shade + (210 - base[0]) * k))
            g = min(255, int(base[1] * shade + (170 - base[1]) * k))
            b = min(255, int(base[2] * shade + (255 - base[2]) * k))
            px[u_i, v_i] = (r, g, b, 255)

    # 轻微模糊柔化棱线，仍保留对比
    img = img.filter(ImageFilter.GaussianBlur(1.0 * SS))
    # 半透明：水晶要更透一点
    a = img.getchannel("A").point(lambda v_: int(v_ * 0.78))
    img.putalpha(a)
    # 外发光
    glow = img.filter(ImageFilter.GaussianBlur(6 * SS))
    glow.putalpha(glow.getchannel("A").point(lambda v_: int(v_ * 0.4)))
    img = Image.alpha_composite(glow, img)

    img = img.resize((S, S), Image.LANCZOS)
    img.save(out)
    print("水晶链贴图已生成:", out, img.size)


main()
