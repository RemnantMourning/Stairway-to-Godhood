# -*- coding: utf-8 -*-
"""
「登神长阶」(ascension_orb) 动画贴图生成器。

输出：SS 倍分辨率、N 帧的竖向动画贴图（默认 64 x 2048）。
      帧数 = 高度 / 宽度，播放速度由 .png.mcmeta 的 frametime 控制。

构图（由底到顶，后面的盖前面的）：
  1. 黑洞球体 —— 纯黑，固定不动
  2. 引力波   —— 3 道环状波前，每道由「压缩相(亮环) + 稀疏相(暗环)」成对构成，
                 由球缘缓慢向光晕外缘推进（这是引力波区别于普通涟漪的签名特征）
  3. 裂缝     —— N_CRACKS 条，位置与形状【固定不变】，只有亮度做呼吸式闪动
  4. 闪电     —— 每条有独立生命期，从裂缝处沿径向【向球外】射出；
                 双层结构：外层淡蓝辉光 + 内层白色细芯

用法：
    python gen_ascension_orb.py [输出png] [预览目录]
"""

import math
import os
import random
import sys

from PIL import Image

# ---------------------------------------------------------------- 画布参数
SS = 2                       # 分辨率倍率：1 = 32px/帧，2 = 64px/帧。
                             # 提高它能让放大后不再糊、并给 mipmap 每一级留足细节。
BASE = 32                    # 基准单帧边长（下面所有像素常量都按这个尺度书写）
W = BASE * SS                # 单帧边长
N = 32                       # 帧数（总高 = W * N）
CX = CY = (W - 1) / 2.0      # 球心

R_ORB = 9.6 * SS             # 球体半径
R_HALO = 14.6 * SS           # 外圈光晕外缘

N_CRACKS = 5                 # 外圈裂缝条数（位置固定）

# 裂缝尺寸（surge 模式会放大 —— 见 apply_mode）
CRACK_R_IN = (0.7, 1.7)      # 向球内延伸的长度范围（格）
CRACK_R_OUT = (2.6, 3.8)     # 向球外延伸的长度范围（格）
CRACK_W0 = (0.62, 0.86)      # 根部宽度范围

SEED = 20260928

ORB_RGB = (5, 3, 9)          # 球体黑（微微偏蓝）
HALO_RGB_IN = (40, 32, 74)   # 光晕内侧色（暗蓝紫）
HALO_RGB_OUT = (74, 82, 126)  # 光晕外侧色（偏蓝）
HALO_ALPHA = 122.0           # 光晕最内缘的 alpha

CRACK_DARK = (74, 22, 68)    # 裂缝最暗时的颜色
CRACK_LIT = (230, 98, 180)   # 裂缝最亮时的颜色

# --- 引力波 ---
WAVE_COUNT = 2               # 同时存在的波数（1 道波 = 亮环 + 暗环 两个环）
WAVE_R0 = R_ORB - 1.2 * SS   # 波前起始半径（略入球内）
WAVE_R1 = R_HALO + 1.8 * SS  # 波前结束半径（越过光晕外缘）
WAVE_SIGMA = 0.80 * SS       # 波包宽度（高斯 sigma，越小环越锐）
WAVE_GAP = 1.25              # 压缩相与稀疏相的间距（单位：sigma）——太小两环会互相抵消
WAVE_LIT = 0.72              # 压缩相提亮幅度
WAVE_DIM = 0.50              # 稀疏相压暗幅度
WAVE_ALPHA = 76.0            # 压缩相自带的额外不透明度（否则走到光晕外缘就看不见了）
WAVE_TINT = (168, 196, 255)  # 压缩相朝这个颜色提亮（冷蓝白）

# 各道波的相位错开量。**刻意不用 k/WAVE_COUNT 这种等分值**：
# 两道速度相同、只错开半个周期的波，跑 N/WAVE_COUNT 帧后只是【互换了身份】，
# 图案看起来就提前重复了 —— 16 帧改 32 帧时动画周期仍停在 16 帧，正是这个原因。
# 用黄金比错开可保证整段动画在 N 帧内不出现重复。
WAVE_OFFSETS = [(k * 0.6180339887498949) % 1.0 for k in range(WAVE_COUNT)]

# --- 闪电 ---
BOLT_GLOW = (105, 165, 255)   # 辉光色（冷蓝）
BOLT_CORE = (240, 249, 255)   # 核心色（近白微蓝）
BOLT_GLOW_W = 1.5             # 辉光宽度倍数
BOLT_GLOW_PAD = 0.20 * SS     # 辉光额外加宽（px）
BOLT_GLOW_A = 0.20            # 辉光强度
BOLT_CORE_W = 0.52 * SS       # 核心基准宽度（必须明显小于段长，否则会糊成"香肠"）
BOLT_CORE_LIT = 1.55          # 核心强度
BOLT_MIN, BOLT_MAX = 2, 3     # 任意时刻活跃的闪电条数区间
BOLT_DUR = (3.0, 5.0)         # 单条闪电持续帧数


def mix(c0, c1, t):
    return tuple(c0[i] + (c1[i] - c0[i]) * t for i in range(3))


def halo_at(r):
    """球外某半径处的基础 (alpha, rgb)，向外渐隐。"""
    t = (r - R_ORB) / (R_HALO - R_ORB)
    if t >= 1.0:
        return 0.0, (0.0, 0.0, 0.0)
    a = HALO_ALPHA * (1.0 - t) ** 1.15
    return a, mix(HALO_RGB_IN, HALO_RGB_OUT, t)


def wave_profile(r, f):
    """引力波在半径 r、第 f 帧处的 (压缩相强度, 稀疏相强度)，均在 0..1。"""
    lit = 0.0
    dim = 0.0
    span = WAVE_R1 - WAVE_R0
    for k in range(WAVE_COUNT):
        ph = (f / N + WAVE_OFFSETS[k]) % 1.0         # 各道波错开相位（见 WAVE_OFFSETS）
        r_w = WAVE_R0 + ph * span                    # 该道波的波前半径
        # 两端淡入淡出，避免波凭空出现 / 消失
        env = min(1.0, ph / 0.10, (1.0 - ph) / 0.14)
        if env <= 0.0:
            continue
        d = (r - r_w) / WAVE_SIGMA
        lit = max(lit, env * math.exp(-((d + WAVE_GAP) ** 2)))
        dim = max(dim, env * math.exp(-((d - WAVE_GAP) ** 2)))
    return lit, dim


def add_segment(acc, alp, p0, p1, w0, w1, color, strength,
                alpha_floor=0.0, power=0.7):
    """把一条带宽度渐变的线段以"加色"方式叠进缓冲。

    power 控制横向衰减：<1 中间调更实（适合细芯），
    >1 边缘更快淡出（适合柔和辉光）。
    """
    x0, y0 = p0
    x1, y1 = p1
    wmax = max(w0, w1)
    minx = max(0, int(math.floor(min(x0, x1) - wmax - 1)))
    maxx = min(W - 1, int(math.ceil(max(x0, x1) + wmax + 1)))
    miny = max(0, int(math.floor(min(y0, y1) - wmax - 1)))
    maxy = min(W - 1, int(math.ceil(max(y0, y1) + wmax + 1)))
    dx, dy = x1 - x0, y1 - y0
    l2 = dx * dx + dy * dy
    for y in range(miny, maxy + 1):
        for x in range(minx, maxx + 1):
            if l2 <= 1e-9:
                t = 0.0
            else:
                t = ((x - x0) * dx + (y - y0) * dy) / l2
                t = max(0.0, min(1.0, t))
            px, py = x0 + t * dx, y0 + t * dy
            d = math.hypot(x - px, y - py)
            w = w0 + (w1 - w0) * t
            if w <= 0.0:
                continue
            v = 1.0 - d / w
            if v <= 0.0:
                continue
            v = v ** power
            v *= strength
            cell = acc[y][x]
            for i in range(3):
                cell[i] += color[i] * v
            if alpha_floor > 0.0:
                alp[y][x] = max(alp[y][x], alpha_floor * min(1.0, v))
            else:
                alp[y][x] = max(alp[y][x], 255.0 * min(1.0, v))


# ---------------------------------------------------------------- 裂缝
def make_cracks(rng):
    """预生成裂缝。角度、长度、形状都固定，只有亮度会随时间变。"""
    cracks = []
    for i in range(N_CRACKS):
        base = 2.0 * math.pi * i / N_CRACKS
        n_nodes = 4
        nod = [0.0] + [rng.uniform(-0.14, 0.14) for _ in range(n_nodes - 2)] + [0.0]
        cracks.append({
            "ang": base + rng.uniform(-0.20, 0.20),      # 固定角度
            "r_in": R_ORB - rng.uniform(*CRACK_R_IN) * SS,   # 向球内延伸到的半径
            "r_out": R_ORB + rng.uniform(*CRACK_R_OUT) * SS, # 向球外延伸到的半径
            "bend": rng.uniform(-0.40, 0.40),            # 整体角度漂移（所以是斜的）
            "nod": nod,                                  # 各节点的固定折角
            "w0": rng.uniform(*CRACK_W0) * SS,               # 根部宽度
            "phase": rng.uniform(0.0, 2.0 * math.pi),    # 闪动相位
            "freq": rng.uniform(0.9, 1.9),               # 闪动频率（周期数 / 整段动画）
        })
    return cracks


def crack_points(c):
    pts = []
    n = len(c["nod"]) - 1
    for k in range(len(c["nod"])):
        t = k / n
        r = c["r_in"] + (c["r_out"] - c["r_in"]) * t
        a = c["ang"] + c["bend"] * t * t + c["nod"][k]
        pts.append((CX + r * math.cos(a), CY + r * math.sin(a)))
    return pts


def crack_brightness(c, frame, jitter):
    """0..1 的亮度。用 sin 做呼吸式闪动，平方让"亮"的时间短、"暗"的时间长。"""
    ph = 2.0 * math.pi * c["freq"] * frame / N + c["phase"]
    s = 0.5 + 0.5 * math.sin(ph)
    return max(0.0, min(1.0, (0.15 + 0.85 * s * s) * jitter))


# ---------------------------------------------------------------- 闪电
def make_bolt(rng, ang0):
    """生成一条从球缘沿径向【向球外】射出的折线（含少量短分叉）。

    角度用"带衰减记忆的偏移"围绕径向角摆动 —— 始终保持向外的方向感，
    不会像纯累积随机游走那样绕成钩子（那正是"像蛆"的根源）。
    """
    r0 = R_ORB - 0.35 * SS
    r_end = R_HALO + rng.uniform(0.0, 0.9) * SS  # 允许冲出光晕一点，增强"射出去"的方向感
    nseg = rng.randint(2, 3)                    # 段数少 → 段长长，线条才不像"香肠"
    step = (r_end - r0) / nseg

    off = rng.uniform(-0.09, 0.09)
    r = r0
    path = [(CX + r * math.cos(ang0 + off), CY + r * math.sin(ang0 + off))]
    for _ in range(nseg):
        off = max(-0.34, min(0.34, off * 0.40 + rng.uniform(-0.22, 0.22)))
        r = min(r + step * rng.uniform(0.86, 1.12), r_end)
        a = ang0 + off
        path.append((CX + r * math.cos(a), CY + r * math.sin(a)))
        if r >= r_end - 1e-6:
            break

    segs = []
    n = max(1, len(path) - 1)
    for k in range(n):
        t0, t1 = k / n, (k + 1) / n
        segs.append((path[k], path[k + 1],
                     BOLT_CORE_W * (1.0 - 0.58 * t0),
                     BOLT_CORE_W * (1.0 - 0.58 * t1),
                     1.0))

    # 分叉：短、细、尖，偏向"继续向外"
    if len(path) >= 3 and rng.random() < 0.55:
        i = rng.randint(1, len(path) - 2)
        bx, by = path[i]
        t = i / n
        ba = ang0 + rng.uniform(-0.55, 0.55)
        blen = (1.0 - 0.55 * t) * rng.uniform(1.2, 2.4) * SS
        segs.append(((bx, by), (bx + blen * math.cos(ba), by + blen * math.sin(ba)),
                     0.34, 0.14, 0.60))
    return segs


def plan_bolts(rng, cracks):
    """逐帧维护一个活跃闪电集合，保证任意时刻有 BOLT_MIN~BOLT_MAX 条在闪。

    每条闪电有生命期（BOLT_DUR 帧），期间形状固定、亮度走包络；
    结束后由下一帧从"当前空闲的裂缝"里补新的 ——
    所以闪电总是从不同裂缝同时向外溢出，且成员会不断轮换。
    """
    events = []
    active = []                       # [{"end": 结束帧, "ci": 裂缝索引}]
    for f in range(N):
        active = [e for e in active if e["end"] > f]
        want = rng.randint(BOLT_MIN, BOLT_MAX) if f % 4 == 0 else len(active)
        want = max(BOLT_MIN, min(BOLT_MAX, want))
        busy = {e["ci"] for e in active}
        free = [i for i in range(len(cracks)) if i not in busy]
        rng.shuffle(free)
        while len(active) < want and free:
            ci = free.pop()
            dur = rng.uniform(*BOLT_DUR)
            a0 = cracks[ci]["ang"] + rng.uniform(-0.20, 0.20)
            events.append({
                "ci": ci,
                "start": f,
                "dur": dur,
                "peak": rng.uniform(0.85, 1.0),
                "segs": make_bolt(rng, a0),
            })
            active.append({"end": min(N, f + dur), "ci": ci})
    return events


# ---------------------------------------------------------------- 组装
def render_frames():
    rng = random.Random(SEED)
    cracks = make_cracks(rng)
    bolts = plan_bolts(rng, cracks)
    frames = []

    for f in range(N):
        acc = [[[0.0, 0.0, 0.0] for _ in range(W)] for _ in range(W)]
        alp = [[0.0] * W for _ in range(W)]

        # 1) 球体 + 光晕（含引力波调制）
        for y in range(W):
            for x in range(W):
                r = math.hypot(x - CX, y - CY)
                if r <= R_ORB:
                    acc[y][x] = [float(v) for v in ORB_RGB]
                    alp[y][x] = 255.0
                elif r <= R_HALO:
                    a, col = halo_at(r)
                    lit, dim = wave_profile(r, f)
                    if lit > 0.001 or dim > 0.001:
                        col = mix(col, WAVE_TINT, WAVE_LIT * lit)          # 压缩相提亮
                        col = tuple(v * (1.0 - WAVE_DIM * dim) for v in col)  # 稀疏相压暗
                        a = a * (1.0 - 0.35 * dim) + WAVE_ALPHA * lit          # 压缩相自带 alpha
                    acc[y][x] = [col[0], col[1], col[2]]
                    alp[y][x] = max(0.0, min(255.0, a))

        # 2) 裂缝（加色，位置与形状固定，只有亮度变）
        for c in cracks:
            b = crack_brightness(c, f, rng.uniform(0.93, 1.07))
            col = mix(CRACK_DARK, CRACK_LIT, b)
            pts = crack_points(c)
            floor = 120.0 + 90.0 * b
            for k in range(len(pts) - 1):
                t0 = k / (len(pts) - 1)
                t1 = (k + 1) / (len(pts) - 1)
                add_segment(acc, alp, pts[k], pts[k + 1],
                            c["w0"] * (1.0 - 0.58 * t0),
                            c["w0"] * (1.0 - 0.58 * t1),
                            col, 1.0, alpha_floor=floor)

        # 3) 闪电（双层：外层冷蓝辉光 + 内层白色细芯）
        for ev in bolts:
            u = (f + 0.5 - ev["start"]) / ev["dur"]     # 取帧中心，避免生命期首帧被跳过
            if u <= 0.0 or u >= 1.0:
                continue
            strength = ev["peak"] * (math.sin(math.pi * u) ** 0.25)
            for p0, p1, w0, w1, amp in ev["segs"]:
                s = strength * amp
                add_segment(acc, alp, p0, p1,
                            w0 * BOLT_GLOW_W + BOLT_GLOW_PAD,
                            w1 * BOLT_GLOW_W + BOLT_GLOW_PAD,
                            BOLT_GLOW, BOLT_GLOW_A * s, power=2.0)
                add_segment(acc, alp, p0, p1, w0, w1,
                            BOLT_CORE, BOLT_CORE_LIT * s, power=0.55)

        # 量化
        img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        px = img.load()
        for y in range(W):
            for x in range(W):
                a = alp[y][x]
                if a <= 1.0:
                    continue
                r_, g_, b_ = (max(0, min(255, int(round(v)))) for v in acc[y][x])
                px[x, y] = (r_, g_, b_, max(0, min(255, int(round(a)))))
        frames.append(img)

    return frames


def save_sheet(frames, path, scale=5, bg=(65, 65, 75), cols=8):
    rows = (len(frames) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * W * scale, rows * W * scale), bg)
    for i, fr in enumerate(frames):
        tile = Image.new("RGB", (W * scale, W * scale), bg)
        big = fr.resize((W * scale, W * scale), Image.NEAREST)
        tile.paste(big, (0, 0), big)
        sheet.paste(tile, ((i % cols) * W * scale, (i // cols) * W * scale))
    sheet.save(path)


def apply_mode(surge):
    """按模式覆盖参数。

    surge = 第三阶段「登神长阶」：裂隙更大更多、闪电更粗更亮
    （引力波"极快"由 .png.mcmeta 的 frametime 控制 —— 这里管不了，见 ascension_orb_surge.png.mcmeta）。
    """
    global N_CRACKS, CRACK_R_IN, CRACK_R_OUT, CRACK_W0
    global BOLT_CORE_W, BOLT_CORE_LIT, BOLT_GLOW_A, BOLT_GLOW_W
    global CRACK_LIT, WAVE_LIT
    if not surge:
        return
    N_CRACKS = 9                 # 裂隙更多
    CRACK_R_IN = (1.0, 2.4)      # 向球内裂得更深
    CRACK_R_OUT = (4.0, 6.2)     # 向球外炸得更远 —— "裂隙变大"
    CRACK_W0 = (1.05, 1.45)      # 更粗的裂缝
    BOLT_CORE_W = 0.80 * SS      # 闪电更粗
    BOLT_CORE_LIT = 2.30         # 更强
    BOLT_GLOW_A = 0.34           # 辉光更亮 —— "闪电效果更突出"
    BOLT_GLOW_W = 2.0
    CRACK_LIT = (255, 130, 215)  # 裂缝更亮（快撑爆的感觉）
    WAVE_LIT = 0.90              # 引力波对比更强


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    surge = "--surge" in sys.argv
    apply_mode(surge)
    out_png = args[0] if len(args) > 0 else "ascension_orb.png"
    preview_dir = args[1] if len(args) > 1 else None

    frames = render_frames()

    sheet = Image.new("RGBA", (W, W * N), (0, 0, 0, 0))
    for i, fr in enumerate(frames):
        sheet.paste(fr, (0, i * W))
    os.makedirs(os.path.dirname(os.path.abspath(out_png)), exist_ok=True)
    sheet.save(out_png)
    print("written:", out_png, sheet.size)

    if preview_dir:
        os.makedirs(preview_dir, exist_ok=True)
        save_sheet(frames, os.path.join(preview_dir, "preview_dark.png"), bg=(65, 65, 75))
        save_sheet(frames, os.path.join(preview_dir, "preview_gui.png"), bg=(139, 139, 139))
        save_sheet(frames, os.path.join(preview_dir, "preview_light.png"), bg=(198, 198, 205))
        print("previews ->", preview_dir)


if __name__ == "__main__":
    main()
