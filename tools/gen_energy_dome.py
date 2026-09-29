# -*- coding: utf-8 -*-
"""生成能量罩贴图 —— 紫色透明能量膜（罩在法阵边缘）。

渲染时把这张图铺在一圈竖圆柱面上（u = 绕圆周方向，v = 从罩顶到罩底）。

贴图要点：
  - 透明背景（能量罩是半透明的，能透出里面）
  - 上端(v=0，罩顶)淡出到几乎透明；下端(v=1，罩底)较亮 —— 像从地面升起的一层膜
  - 沿 u 有细密的竖向能量流线；另加六边形网格纹（能量场感）
  - 紫色系，整体发光感（渲染端用 FULL_BRIGHT）

用法：python gen_energy_dome.py <输出png>
"""

import math
import os
import sys
from PIL import Image, ImageFilter

S = 512          # 高一些：v 要容纳"底亮顶淡"的渐变
SS = 1           # 已用解析式画渐变，不需要超采样
W = S


def main():
    out = sys.argv[1]
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    px = img.load()

    rnd = 4242
    for y in range(W):
        v = y / float(W - 1)          # 0 = 罩顶, 1 = 罩底
        # 纵向分布：底部最亮（贴地那圈能量最强），往上迅速衰减到几乎透明
        vert = (1.0 - v) ** 1.6         # 1(底) → 0(顶)
        vert = 0.10 + 0.90 * vert
        for x in range(W):
            u = x / float(W - 1)
            # 竖向能量流线：细密条纹（u 方向），频率高
            flow = (math.sin(u * math.tau * 26.0) * 0.5 + 0.5) ** 2.2
            # 六边形网格纹：用两组斜线交织近似
            hex_a = (math.sin((u * 9.0 + v * 5.0) * math.tau) * 0.5 + 0.5) ** 6
            hex_b = (math.sin((u * 9.0 - v * 5.0) * math.tau) * 0.5 + 0.5) ** 6
            grid = max(hex_a, hex_b)
            # 顶部附近的"罩壳边缘"亮线（v 小的地方一条亮带）
            rim = math.exp(-((v - 0.06) ** 2) / (2 * 0.02 ** 2))

            # 亮度合成
            lit = flow * 0.55 + grid * 0.35 + rim * 0.9
            # 颜色：纯紫（不掺白）
            r = int(min(255, 120 + 100 * lit))
            g = int(min(255, 40 + 90 * lit))
            b = int(min(255, 190 + 65 * lit))
            a = int(min(255, 200 * vert * (0.35 + lit * 0.75)))
            px[x, y] = (r, g, b, a)

    # 柔化，让流线不刺眼
    img = img.filter(ImageFilter.GaussianBlur(1.4))
    img.save(out)
    print("能量罩贴图已生成:", out, img.size)


main()
