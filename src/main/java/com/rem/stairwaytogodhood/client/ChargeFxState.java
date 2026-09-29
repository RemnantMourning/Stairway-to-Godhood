package com.rem.stairwaytogodhood.client;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 蓄力特效的<b>客户端状态机</b> —— 只为"收起动画"服务。
 *
 * <h2>为什么需要它</h2>
 * 法阵/锁链是纯客户端渲染，目前只在 {@code isCharging} 为真时绘制。
 * 一旦松手，{@code isCharging} 立刻变假 —— 如果什么都不做，法阵会"啪"地消失。
 * 要播放"按释放方式收起"的反向动画，就必须在<b>松手的那一瞬间</b>留住三样东西：
 * <ol>
 *     <li>法阵当时钉在哪（锚点快照）</li>
 *     <li>演出当时进行到哪（elapsed 快照 —— 决定收起时各层从什么进度倒放）</li>
 *     <li>松手发生在什么时候（用玩家 tickCount 计时倒放）</li>
 * </ol>
 * 本类就是这三样东西的存放处，由 {@code MagicCircleRenderer} 每帧维护。
 *
 * <p>纯客户端数据、每帧覆写，不需要持久化，也不涉及同步。
 */
public final class ChargeFxState {

    /** 上一帧是否正在蓄力（用于检测"松手"这个下降沿）。 */
    private static boolean wasCharging;
    /** 正在播放收起动画。 */
    private static boolean retracting;
    /** 收起动画已经播放了多少 tick（松手后每帧累加 partialTick）。 */
    private static float retractElapsed;
    /** 松手瞬间的演出进度（tick）—— 收起 = 从这个值倒放回 0。 */
    private static float elapsedAtRelease;
    /** 松手瞬间法阵的锚点（世界坐标）。 */
    private static Vec3 anchorAtRelease;

    // ---------------------------------------------------------------- 冻结态
    //
    // 需求：黑屏完成之后松手，**不收起**法阵/防御罩，而是把它们冻结在原地（动画静止）。
    // 黑屏之后屏幕本就是黑的，玩家也看不到遮罩 —— 所以遮罩直接关掉省资源，
    // 但世界里的法阵/罩子还要继续存在（否则松手瞬间法阵消失会很突兀）。
    //
    /** 上一帧（蓄力中）是否已经黑屏完成 —— 松手瞬间 isCharging 已变 false、
     *  getChargeTicks 归零，只能在蓄力中每帧把"黑屏完成"这个事实缓存下来。 */
    private static boolean wasBlackDone;
    /** 是否处于"登神完成、冻结保留"状态。 */
    private static boolean frozen;
    /** 冻结时法阵钉在哪（世界坐标）。 */
    private static Vec3 frozenAnchor;
    /** 冻结时的演出进度（tick）—— 法阵/罩子停在这个进度上，不再推进。 */
    private static float frozenElapsed;

    private ChargeFxState() {
    }

    /** 蓄力中：刷新快照，并取消任何进行中的收起/冻结（重新蓄力优先）。 */
    public static void updateCharging(Vec3 anchor, float elapsed, boolean blackDone) {
        wasCharging = true;
        wasBlackDone = blackDone;
        retracting = false;
        retractElapsed = 0.0F;
        anchorAtRelease = anchor;
        elapsedAtRelease = elapsed;
        frozen = false;
        frozenAnchor = null;
        frozenElapsed = 0.0F;
    }

    /**
     * 检测松手（下降沿）。
     * <p>
     * 分支两种结局：
     * <ul>
     *     <li><b>黑屏已完成</b>（上一帧缓存的 {@code wasBlackDone}）：进入<b>冻结态</b> —— 法阵/罩子保留、静止。</li>
     *     <li>否则：走原来的<b>收起动画</b>。</li>
     * </ul>
     * <p>
     * ⭐ 直接用 {@code updateCharging} 上一帧缓存的锚点/elapsed 快照 ——
     * 松手那一帧 isCharging 已变 false、getChargeTicks 归零，绝不能现读。
     */
    public static void onChargeStopped() {
        if (!wasCharging) {
            return;
        }
        wasCharging = false;
        if (wasBlackDone) {
            frozen = true;
            frozenAnchor = anchorAtRelease;
            frozenElapsed = elapsedAtRelease;
            retracting = false;
        } else {
            retracting = true;
            retractElapsed = 0.0F;
        }
    }

    public static boolean isFrozen() {
        return frozen;
    }

    @Nullable
    public static Vec3 getFrozenAnchor() {
        return frozenAnchor;
    }

    public static float getFrozenElapsed() {
        return frozenElapsed;
    }

    /** 清除冻结态（例如玩家离开 / 切维度 / 主动取消时调用）。 */
    public static void clearFrozen() {
        frozen = false;
        frozenAnchor = null;
        frozenElapsed = 0.0F;
    }

    // ---------------------------------------------------------------- 黑屏保持
    //
    // 演出收尾时（黑幕走完）我们会停掉蓄力 —— 但一停 isCharging 就变 false，
    // 依赖它的遮罩会立刻消失，而占位界面要下一 tick 才弹出 ⇒ 中间会露出一帧游戏画面。
    // 所以用一个独立的"黑屏保持"标志：从收尾那一刻起继续画纯黑，
    // 直到占位界面真正打开（AscendFocusRenderer 检测到界面后调用 releaseBlackout）。
    //
    /** 黑屏是否处于"保持"状态（收尾后、占位界面弹出前）。 */
    private static boolean blackoutHeld;

    /** 开始保持黑屏（演出收尾时由 finishAscension 调用）。 */
    public static void holdBlackout() {
        blackoutHeld = true;
    }

    /** 释放黑屏（占位界面已弹出，可以交给界面自己铺黑底了）。 */
    public static void releaseBlackout() {
        blackoutHeld = false;
    }

    public static boolean isBlackoutHeld() {
        return blackoutHeld;
    }

    public static boolean isRetracting() {
        return retracting;
    }

    /** 收起动画推进 dt tick，返回是否已经播完。 */
    public static boolean advanceRetract(float dt) {
        retractElapsed += dt;
        return retractElapsed >= RETRACT_TICKS;
    }

    public static float getRetractElapsed() {
        return retractElapsed;
    }

    public static float getElapsedAtRelease() {
        return elapsedAtRelease;
    }

    public static Vec3 getAnchorAtRelease() {
        return anchorAtRelease;
    }

    /**
     * 收起动画总时长（tick）。
     * <p>
     * 30 tick = 1.5 秒 —— 比出现（约 3.5 秒）快、但足够看清"法阵原路收回去"的过程。
     * 调大 = 更慢更从容；调小 = 干脆利落地消失。
     */
    public static final float RETRACT_TICKS = 30.0F;

    // ================================================================ 扩展接口
    //
    // 【收起动画的扩展点】目前"收起"= 把演出的 elapsed 倒放（法阵升起、锁链缩回、能量罩回缩
    // 全是同一套倒放逻辑，见 MagicCircleRenderer.onRenderLevel 的 else 分支）。
    // 以后如果想做更讲究的收场，可以从这里挂钩子，例如：
    //
    //   1) 不同的收场曲线：现在用 smoothstep × 1.2（提前收完，避免末尾闪烁）。
    //      想改成"先炸一下再收"或"加速塌缩"，替换 MagicCircleRenderer 里那个 k 的计算即可。
    //   2) 收场音效 / 粒子：在 onChargeStopped() 里（下降沿只触发一次）播放，
    //      这里已经拿到了"松手瞬间"这个唯一的时间点。
    //   3) 分阶段收场：让能量罩先收、锁链后收 —— 给各段加独立的 progress 上限即可，
    //      因为各段的进度都是从同一个 renderElapsed 推出来的。
    //
    // 目前先保持这个简单实现，够用。
}
