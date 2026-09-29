# -*- coding: utf-8 -*-
"""生成「齿轮啮合的魔法阵」—— 分层动画贴图（每层一张竖向 spritesheet）。

四层（半径从内到外，也是**出现顺序**）：

    层1 center   中心齿轮(20齿) + 轮毂装饰          —— 最先落下
    层2 planets  4 个大的行星齿轮(12齿)             —— 外啮合中心齿轮
    层3 decors   空白处的八角星连线阵 + 节点圆环    —— 填补行星之间的空档
    层4 ring     内齿圈(44齿，齿朝内) + 外圈刻度环   —— 最后落下，内啮合行星

⭐⭐ 运动设计（真·行星齿轮系传动）：
    三组齿轮按真实齿数比联动 —— 行星 = −中心 × 20/12（外啮合反向）；
    齿圈 = 行星 × 12/44 = −中心 × 20/44（内啮合同向）。
    最小公共周期 = 中心齿轮转 90°（中心 5 齿距 = 行星 3 齿距 = 齿圈 11 齿距），
    16 帧走完 90° ⇒ 每层都与第 0 帧逐像素相同 ⇒ 无缝循环。

    齿轮的「自转」烘进贴图帧里（帧动画），「公转 / 呼吸」由渲染层整体旋转提供。
    这样齿永远精确咬合、永不穿模 —— 之前"各层独立转向导致相位漂移撞齿"的问题彻底消除。

用法：python gen_magic_circle.py <输出目录> [预览目录]
"""

import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

# ---------------------------------------------------------------- 画布
SS = 4
SIZE = 512
W = SIZE * SS
C = W / 2.0
# 16 帧：齿轮自转的最小公共周期（中心 90°）拆成 16 帧，逐像素无缝循环。
FRAMES = 16

# ---------------------------------------------------------------- 齿轮系（真啮合）
MODULE = 9.0 * SS
# 真啮合时齿高可回归标准值：齿顶互相插入的深度正比于齿高，
# 1.8m 是"咬合紧实、边界干净"的折中（1.6m 太松、2.25m 啮合区描边交叉成乱麻）。
TOOTH_H = MODULE * 1.8

T_CENTER, R_CENTER = 20, 90.0 * SS
T_PLANET, R_PLANET = 12, 54.0 * SS
PLANET_COUNT = 4
PLANET_ANGLES = [45.0, 135.0, 225.0, 315.0]
# 真啮合：行星轨道 = 中心节圆 + 行星节圆（两齿轮外切）
PLANET_DIST = R_CENTER + R_PLANET
# 真啮合：内齿圈节圆 = 行星轨道 + 行星节圆（行星同时与中心齿轮、内齿圈相切）
T_RING, R_RING = 44, PLANET_DIST + R_PLANET

STEP_CENTER = 360.0 / T_CENTER               # 18°
STEP_PLANET = 360.0 / T_PLANET               # 30°
STEP_RING = 360.0 / T_RING                   # 8.1818...
CYCLE_DEG = 90.0                             # 最小公共周期（中心 5 齿距）

# 八角星连线阵（装饰层）：线条颜色
COL_STAR = (150, 112, 255, 150)

# ---------------------------------------------------------------- 外圈
RING_RIM_MID = R_RING + TOOTH_H * 0.80       # 齿圈轮辋（在齿根外侧 —— 齿才看得出来朝内）
RING_R_OUT = R_RING + 51.5 * SS
RING_R_IN = R_RING + 46.0 * SS
TICK_COUNT = 88

# ---------------------------------------------------------------- 配色
COL_CENTER = (104, 64, 218, 190)
COL_PLANET = (162, 122, 255, 185)
COL_RING = (118, 72, 236, 172)
COL_EDGE = (230, 214, 255, 235)
COL_HUB = (196, 166, 255, 210)
COL_RING_OUT = (120, 76, 238, 170)
COL_TICK = (208, 184, 255, 190)
COL_ARC = (128, 88, 240, 110)
COL_CORE = (214, 194, 255, 130)


def polar(cx, cy, r, ang_rad):
    return (cx + r * math.cos(ang_rad), cy + r * math.sin(ang_rad))


def gear_points(cx, cy, teeth, pitch_r, phase_deg, tooth_h=TOOTH_H, ratio=0.5):
    """外齿轮（对称矩形齿：节圆处齿厚 = 槽宽 = 半个齿距）。"""
    step = 2.0 * math.pi / teeth
    half = step * ratio / 2.0
    r_tip = pitch_r + tooth_h / 2.0
    r_root = pitch_r - tooth_h / 2.0
    pts = []
    for i in range(teeth):
        c = math.radians(phase_deg) + i * step
        pts.append(polar(cx, cy, r_root, c - half))
        pts.append(polar(cx, cy, r_tip, c - half))
        pts.append(polar(cx, cy, r_tip, c + half))
        pts.append(polar(cx, cy, r_root, c + half))
    return pts


def draw_gear(d, cx, cy, pts, fill, edge_w, hub_r=0.0):
    d.polygon(pts, fill=fill)
    d.line(list(pts) + [pts[0]], fill=COL_EDGE, width=edge_w, joint="curve")
    if hub_r > 0.0:
        d.ellipse([cx - hub_r, cy - hub_r, cx + hub_r, cy + hub_r], fill=COL_HUB)
        hole = hub_r * 0.50
        d.ellipse([cx - hole, cy - hole, cx + hole, cy + hole], fill=(0, 0, 0, 0))


def layer_center(t):
    """层 1：中心齿轮 + 轮毂装饰。t ∈ [0,1) 驱动自转 0→CYCLE_DEG(90°)。"""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    spin = CYCLE_DEG * t
    pts = gear_points(C, C, T_CENTER, R_CENTER, spin)
    draw_gear(d, C, C, pts, COL_CENTER, int(1.4 * SS), hub_r=R_CENTER * 0.52)
    core = R_CENTER * 0.30
    d.ellipse([C - core, C - core, C + core, C + core], outline=COL_CORE, width=int(2.2 * SS))
    for k in range(4):
        a = math.radians(90.0 * k + 45.0)
        d.line([polar(C, C, core * 0.30, a), polar(C, C, core * 1.02, a)],
               fill=COL_CORE, width=int(1.8 * SS))
    return glow(img)


def layer_planets(t):
    """层 2：4 个行星齿轮。自转 + 绕中心公转都烘进贴图帧（真实传动）。

    行星自转 = −中心 × 20/12（外啮合反向）：中心转 90° 时，行星自转 −150° = −5 个齿距。
    行星绕中心公转 = 同一时刻它沿轨道走的角度 —— 但因为 16 帧走完的 90° 里
    4 个行星正好各转 3 个齿距回到同构位，公转量 = 0（行星轨道位置不随帧变），
    公转交给渲染层整体旋转即可，贴图只负责自转。
    """
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 行星自转角：中心 90° 时行星转 −(20/12)*90 = −150°
    spin = -(T_CENTER / float(T_PLANET)) * CYCLE_DEG * t
    # 朝中心那侧要是「槽」，这样它才能与中心齿轮的齿咬合
    base = (180.0 - STEP_PLANET / 2.0) % STEP_PLANET
    for i in range(PLANET_COUNT):
        a = math.radians(PLANET_ANGLES[i])
        px, py = polar(C, C, PLANET_DIST, a)
        pts = gear_points(px, py, T_PLANET, R_PLANET, base + spin)
        draw_gear(d, px, py, pts, COL_PLANET, int(1.2 * SS), hub_r=R_PLANET * 0.40)
    return glow(img)


def layer_decors(t):
    """层 3：空白处的装饰 —— 八角星连线阵 + 节点圆环 + 同心细弧。

    八角星线条 8 重对称，但节点圆环只画在一个正方形的顶点上 ⇒ 真实对称 4 重。
    16 帧必须转 90° 才无缝（与齿轮公共周期 90° 一致）。
    """
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    spin = 90.0 * t

    r_star = PLANET_DIST                 # 八角星的外接半径 = 行星轨道
    sq_a = [polar(C, C, r_star, math.radians(45.0 + 90.0 * k + spin)) for k in range(4)]
    sq_b = [polar(C, C, r_star, math.radians(90.0 * k + spin)) for k in range(4)]
    for sq in (sq_a, sq_b):
        d.polygon(sq, outline=COL_STAR, width=int(1.3 * SS))

    # 节点圆环：放在正方形 B 的顶点（= 行星齿轮之间的方向），不与齿轮重叠
    for x, y in sq_b:
        r = 11.0 * SS
        d.ellipse([x - r, y - r, x + r, y + r], outline=COL_EDGE, width=int(1.3 * SS))
        r2 = r * 0.45
        d.ellipse([x - r2, y - r2, x + r2, y + r2], fill=COL_HUB)

    # 同心细弧：填满中心齿轮与行星轨道之间的环带
    for r in (R_CENTER + TOOTH_H + 6.0 * SS, R_CENTER + TOOTH_H + 14.0 * SS):
        d.ellipse([C - r, C - r, C + r, C + r], outline=COL_ARC, width=int(1.2 * SS))
    # 四段短弧（在行星之间的空档，半径更大）
    for k in range(4):
        a0 = math.radians(90.0 * k + 22.5 - 14.0 + spin)
        a1 = math.radians(90.0 * k + 22.5 + 14.0 + spin)
        steps = 8
        pts = [polar(C, C, R_CENTER + TOOTH_H + 26.0 * SS, a0 + (a1 - a0) * i / steps)
               for i in range(steps + 1)]
        d.line(pts, fill=COL_STAR, width=int(1.5 * SS), joint="curve")
    return glow(img)


def layer_ring(t):
    """层 4：内齿圈（齿朝内）+ 外圈刻度环。自转 = −中心 × 20/44（内啮合同向）。"""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 齿圈自转：中心 90° 时齿圈转 −(20/44)*90 = −40.909...° = −5 个齿距
    spin = -(T_CENTER / float(T_RING)) * CYCLE_DEG * t

    # 轮辋在齿根外侧 —— 齿块从轮辋向圆心伸出来，才叫"齿朝内"
    d.ellipse([C - RING_RIM_MID, C - RING_RIM_MID, C + RING_RIM_MID, C + RING_RIM_MID],
              outline=COL_RING, width=int(TOOTH_H * 0.55))
    r_root = R_RING + TOOTH_H * 0.56
    r_tip = R_RING - TOOTH_H * 0.55
    for i in range(T_RING):
        a = math.radians(360.0 * i / T_RING + spin)
        half_a = math.radians(STEP_RING) * 0.22
        d.polygon([polar(C, C, r_root, a - half_a), polar(C, C, r_root, a + half_a),
                   polar(C, C, r_tip, a + half_a * 0.62), polar(C, C, r_tip, a - half_a * 0.62)],
                  fill=COL_RING)

    # 外圈刻度环（不动，或极慢 —— 刻度环是静帧装饰，随 t 微旋可与齿圈同速）
    d.ellipse([C - RING_R_OUT, C - RING_R_OUT, C + RING_R_OUT, C + RING_R_OUT],
              outline=COL_RING_OUT, width=int(2.6 * SS))
    d.ellipse([C - RING_R_IN, C - RING_R_IN, C + RING_R_IN, C + RING_R_IN],
              outline=COL_RING_OUT, width=int(1.3 * SS))
    for i in range(TICK_COUNT):
        a = math.radians(360.0 * i / TICK_COUNT - spin)
        major = (i % 6 == 0)
        d.line([polar(C, C, RING_R_IN - (6 if major else 3) * SS, a),
                polar(C, C, RING_R_OUT + (2 if major else 1.5) * SS, a)],
               fill=COL_TICK, width=int((2.6 if major else 1.1) * SS))
    return glow(img)


def glow(img):
    g = img.filter(ImageFilter.GaussianBlur(4.0 * SS))
    return Image.alpha_composite(g, img)


def build(layer_fn, name, out_dir, preview_dir, frames=FRAMES):
    frames_img = []
    for f in range(frames):
        big = layer_fn(f / float(frames))
        frames_img.append(big.resize((SIZE, SIZE), Image.LANCZOS))
    sheet = Image.new("RGBA", (SIZE, SIZE * frames), (0, 0, 0, 0))
    for i, fr in enumerate(frames_img):
        sheet.paste(fr, (0, i * SIZE))
    path = os.path.join(out_dir, name)
    sheet.save(path)
    print("  %s  (%dx%d)" % (path, sheet.size[0], sheet.size[1]))
    if preview_dir:
        z = frames_img[0].resize((SIZE * 2, SIZE * 2), Image.NEAREST)
        bg = Image.new("RGB", z.size, (18, 16, 30))
        bg.paste(z, (0, 0), z)
        bg.save(os.path.join(preview_dir, name.replace(".png", "_zoom.png")))
    return frames_img


def main():
    out_dir = sys.argv[1]
    preview_dir = sys.argv[2] if len(sys.argv) > 2 else None
    os.makedirs(out_dir, exist_ok=True)

    print("生成 4 层贴图（%d 帧真啮合动画）：" % FRAMES)
    center_f = build(layer_center, "mc_center.png", out_dir, preview_dir)
    planet_f = build(layer_planets, "mc_planets.png", out_dir, preview_dir)
    decor_f = build(layer_decors, "mc_decors.png", out_dir, preview_dir)
    ring_f = build(layer_ring, "mc_ring.png", out_dir, preview_dir)

    if preview_dir:
        comp = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        for fr in (ring_f[0], decor_f[0], planet_f[0], center_f[0]):
            comp = Image.alpha_composite(comp, fr)
        z = comp.resize((SIZE * 2, SIZE * 2), Image.NEAREST)
        bg = Image.new("RGB", z.size, (18, 16, 30))
        bg.paste(z, (0, 0), z)
        bg.save(os.path.join(preview_dir, "preview_magic_circle.png"))
        print("合成预览已输出")


main()

# ==================================================================== 啮合相位推导（真啮合）
#
# ① 中心齿轮 ↔ 行星齿轮（外啮合，反向）：
#    行星方向 φ = 45,135,225,315。中心齿轮在这些方向要是「齿」：
#      phase_center ≡ φ (mod 18°)。φ 相差 90° = 5 个齿距 ⇒ 同余，phase_center = 9° 即可。
#    行星朝中心那侧（φ+180）要是「槽」：
#      phase_planet ≡ (φ+180) − 30/2 (mod 30°)。φ 相差 90° = 3 个齿距 ⇒ 同余。
#
# ② 行星齿轮 ↔ 内齿圈（内啮合，同向）：
#      phase_ring ≡ φ − (360/44)/2 (mod 8.18°)。φ 相差 90° = 11 个齿距 ⇒ 同余。
#
# ③ 转速（真实传动比）：
#      行星 = −中心 × 20/12；齿圈 = −中心 × 20/44。
#      中心 90° 时：行星 −150°（−5 齿距）、齿圈 −40.91°（−5 齿距）⇒ 都回到同构位。
#
# ④ 无缝循环：中心 5 齿距 = 行星 3 齿距 = 齿圈 11 齿距 = 90° ⇒ 16 帧走完 90°。
#
# ⑤ 几何自洽：行星轨道 = 90+54 = 144；内齿圈节圆 = 144+54 = 198。
#    行星同时与中心齿轮（外切）和内齿圈（内切）相切，三条传动链真实成立。
