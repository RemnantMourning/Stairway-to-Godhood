package com.rem.stairwaytogodhood.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rem.stairwaytogodhood.ChargeAnchor;
import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * 蓄力特效：**分层落下的齿轮法阵 + 从行星齿轮中心飞出的柔性锁链**。
 *
 * <h2>演出时间轴（与 {@code AscensionOrbItem.WALK_TICKS} 对齐）</h2>
 * <ol>
 *     <li><b>0 ~ 70 tick（可移动）</b>：四层贴图一圈一圈依次落下、扩大、淡入，
 *         期间完全静止且中心跟随玩家。</li>
 *     <li><b>70 tick 落阵</b>：锚点钉死（{@code ChargeAnchor}）；
 *         齿轮开始自转（16 帧真传动动画）+ 整阵刚性公转 + 亮度呼吸；
 *         同时 <b>4 条锁链从 4 个行星齿轮的轮毂中心飞出</b>（{@code CHAIN_FLY_TICKS}）。</li>
 *     <li><b>82 ~ 92 tick 缠绕</b>：链尖抵达后沿玩家身体螺旋缠绕一圈半
 *         （{@code CHAIN_WRAP_TICKS}）。<b>缠好那一刻</b>移动锁定才生效
 *         （判定在 {@code MixinChargeMovementLock} → {@code isChained}）。</li>
 *     <li><b>松手 / 取消</b>：法阵与锁链按出现方式<b>反向收起</b>
 *         （{@link ChargeFxState} 快照松手瞬间，倒放 16 tick 后消失）。</li>
 * </ol>
 *
 * <h2>锁链的柔性设计</h2>
 * 飞行段不是直线：每条链走一条<b>二次贝塞尔曲线</b> ——
 * 控制点在"起点→玩家"中点的基础上向上抬、向外推，再叠加随时间摆动的横向偏移，
 * 所以链条是甩出来的弧线，还会微微晃动。缠绕段是绕身体的<b>螺旋线</b>。
 * 每个链环沿曲线的切线方向摆放，链与链之间保持垂直交错（真实链条的样子）。
 *
 * <h2>运动模型（v2：真·传动 + 刚性公转）</h2>
 * 齿轮自转烘在 16 帧贴图里按真实齿数比联动（行星 = −中心×20/12，齿圈 = −中心×20/44，
 * 16 帧走完公共周期 90° 逐像素无缝循环）；渲染层只做整阵刚性公转 + 呼吸。
 * ⛔ 历史教训：各层独立反向旋转会让啮合相位漂移、齿互相穿插（实机"穿模"）。
 *
 * <p>⛔ 顶点必须 {@code vc.vertex(matrix, x, y, z)} 显式传矩阵（不带矩阵的重载不变换坐标）；
 * 渲染类型用 {@code entityTranslucent}（不剔除，单面四边形背面也要可见）+ {@code FULL_BRIGHT}。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MagicCircleRenderer {

    // ---------------------------------------------------------------- 贴图
    private static final ResourceLocation TEX_CENTER =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/mc_center.png");
    private static final ResourceLocation TEX_PLANETS =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/mc_planets.png");
    private static final ResourceLocation TEX_DECORS =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/mc_decors.png");
    private static final ResourceLocation TEX_RING =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/mc_ring.png");
    private static final ResourceLocation TEX_CHAIN =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/chain_link.png");
    private static final ResourceLocation TEX_CUFF =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/chain_cuff.png");
    private static final ResourceLocation TEX_DOME =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/effect/energy_dome.png");

    // ---------------------------------------------------------------- 魔法阵
    /** 阵法半径（格）。 */
    private static final double RADIUS = 6.5D;
    /** 各层悬浮高度（格）：内齿圈最贴地、中心齿轮最高 —— 层不能共面（深度排序会崩）。 */
    private static final double[] LAYER_HEIGHTS = {0.06D, 0.16D, 0.26D, 0.36D};
    /** 落下动画的起点高度（约在玩家头顶上方）。 */
    private static final double DROP_HEIGHT = 2.2D;
    /** 全部就位的时刻（tick）= 物品逻辑层的 {@code WALK_TICKS}。 */
    private static final int LAY_DONE = AscensionOrbItem.WALK_TICKS;
    /** 整阵公转速度（度/tick）：0.15 ⇒ 3°/秒，一圈 2 分钟。刚性旋转不破坏啮合。 */
    private static final float ARRAY_SPIN_PER_TICK = 0.15F;
    /** 亮度呼吸的角速度（rad/tick）—— 只在就位之后生效。 */
    private static final float BREATH_SPEED = 0.09F;

    /** 各层出现窗口 [t0, t1]（tick）。数组顺序 = 绘制顺序：外圈垫底、中心齿轮压顶。 */
    private static final int[][] LAYER_WINDOWS = {
            {42, 68},           // 内齿圈 + 外环（垫底）
            {28, 52},           // 八角星装饰阵
            {14, 38},           // 行星齿轮
            {0, 22},            // 中心齿轮（最上）
    };
    private static final ResourceLocation[] LAYER_TEXTURES =
            {TEX_RING, TEX_DECORS, TEX_PLANETS, TEX_CENTER};
    /** 四层贴图的帧数（与 gen_magic_circle.py 的 FRAMES 一致；四层同步播放）。 */
    private static final int FRAME_COUNT = 16;
    /** 每帧显示几 tick：3 ⇒ 公共周期 90° 需 2.4 秒。 */
    private static final int FRAME_TICKS = 3;
    /** 竖排多帧贴图的 UV 内缩量（避开 mipmap 把相邻帧颜色渗进来）。 */
    private static final float FRAME_INSET = 1.5F / 512.0F;

    // ---------------------------------------------------------------- 锁链
    /** 锁链条数 = 行星齿轮数（每条从一个行星齿轮的轮毂中心飞出）。 */
    private static final int CHAINS = 4;
    /** 行星齿轮中心在贴图上的半径（px，512 空间）—— 与 gen_magic_circle.py 的 PLANET_DIST 一致。 */
    private static final float PLANET_DIST_PX = 144.0F;
    /** 贴图像素 → 世界格子的换算：整张贴图铺满直径 2×RADIUS。 */
    private static final float PX_TO_WORLD = (float) (2.0D * RADIUS / 512.0D);
    /** 锁链飞行时长（tick）—— 与 {@code AscensionOrbItem.CHAIN_FLY_TICKS} 一致。 */
    private static final float CHAIN_FLY_TICKS = AscensionOrbItem.CHAIN_FLY_TICKS;
    /** 锁链缠绕时长（tick）—— 与 {@code AscensionOrbItem.CHAIN_WRAP_TICKS} 一致。 */
    private static final float CHAIN_WRAP_TICKS = AscensionOrbItem.CHAIN_WRAP_TICKS;
    /** 链环间距（格）—— 约等于环内长的一半，相邻环正好相扣。 */
    private static final double LINK_PITCH = 0.36D;
    /** 链环半圆段的半径（格）—— 跑道环的半宽。 */
    private static final double LINK_RADIUS = 0.16D;
    /** 链环直段长度（格）—— 跑道环比圆环"长"的部分。 */
    private static final double LINK_STRAIGHT = 0.22D;
    /** 链环板宽（格）—— 扁板沿环面法向的宽度（参考图那种厚板链）。 */
    private static final double LINK_WIDTH = 0.13D;
    /** 链环板厚（格）—— 径向厚度。 */
    private static final double LINK_THICK = 0.05D;
    /** 跑道中心线的采样段数（决定链环的圆润程度与顶点量）。 */
    private static final int LINK_SEGMENTS = 16;
    /** 单条链（飞行段）链环数上限。 */
    private static final int MAX_LINKS = 34;
    /** 链环不透明度：半透明紫链。 */
    private static final int CHAIN_ALPHA = 205;
    /** 飞行段的贝塞尔控制点：相对"起点→玩家"中点的抬升高度（格）。 */
    private static final double FLY_ARC_HEIGHT = 1.6D;
    /** 飞行段的贝塞尔控制点：向外推的距离（格）—— 弧线甩得更开。 */
    private static final double FLY_ARC_OUTWARD = 1.8D;
    /** 柔性摆动的幅度（格）。 */
    private static final double FLY_WOBBLE = 0.9D;
    /** 柔性摆动的角速度（rad/tick）。 */
    private static final double FLY_WOBBLE_SPEED = 0.25D;
    /** 四肢缠绕螺旋的半径（格）—— 略大于胳膊/腿的粗细。 */
    private static final double WRAP_RADIUS = 0.22D;
    /** 四肢缠绕的圈数（绕每根肢体缠几圈）。 */
    private static final double WRAP_TURNS = 3.0;
    /** 手臂的肩部锚点：相对身体中心的水平偏移与高度（格）。 */
    private static final double ARM_OFFSET_X = 0.32D;
    private static final double ARM_Y = 1.35D;
    /** 手臂长度（格）—— 从肩到腕，缠绕沿这段。 */
    private static final double ARM_LENGTH = 0.62D;
    /** 腿的髋部锚点：相对身体中心的水平偏移与高度（格）。 */
    private static final double LEG_OFFSET_X = 0.14D;
    private static final double LEG_Y = 0.72D;
    /** 腿长（格）—— 从髋到踝，缠绕沿这段。 */
    private static final double LEG_LENGTH = 0.66D;

    // ---------------------------------------------------------------- 能量罩
    /** 能量罩开始升起的时刻（tick）= 锁链缠好之后 —— 与物品逻辑层一致。 */
    private static final float DOME_START = AscensionOrbItem.DOME_START_TICKS;
    /** 升起时长（tick）—— 与 {@code AscensionOrbItem.DOME_RISE_TICKS} 一致。 */
    private static final float DOME_RISE_TICKS = AscensionOrbItem.DOME_RISE_TICKS;
    /** 罩体半径（格）—— 与逻辑层共用同一个常量，保证判定与视觉一致。 */
    private static final double DOME_RADIUS = AscensionOrbItem.DOME_RADIUS;
    /** 罩体高度（格）。 */
    private static final double DOME_HEIGHT = 4.2D;
    /** 圆周分段数（越大越圆滑；48 段在 6.5 格半径上足够平滑）。 */
    private static final int DOME_SEGMENTS = 48;
    /** 罩体不透明度（能量膜是半透明的）。 */
    private static final int DOME_ALPHA = 150;

    private static final double TAU = Math.PI * 2.0D;
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);

    /** 上一帧能量罩是否已升起（用于检测"刚刚升起"的上升沿，只切一次视角）。 */
    private static boolean wasDomeUp;
    /**
     * 待打开占位界面（下一客户端 tick 执行）。
     * <p>
     * ⛔ 不在渲染事件里直接 {@code setScreen} —— 那是渲染回调，切界面属于"逻辑"操作，
     * 放到 tick 里执行更稳妥（延迟 1 tick ≈ 16ms，肉眼无感）。
     */
    private static boolean pendingNoContent;

    /** 结束演出：清掉黑屏/锚点、停掉蓄力，并安排下一 tick 打开占位界面。 */
    private static void finishAscension(LocalPlayer player) {
        ChargeFxState.clearFrozen();
        ChargeAnchor.clear(player);
        // 停掉蓄力（客户端 → 发包通知服务端）。这样服务端 onUseTick 不再运行、
        // 玩家也不再"举着宝珠蓄力"，整段演出干净收尾。
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null && player.isUsingItem()) {
            minecraft.gameMode.releaseUsingItem(player);
        }
        // ⭐ 蓄力停了 ⇒ isCharging 变 false，遮罩本来会立刻消失。
        //    用"黑屏保持"顶上，直到占位界面真正弹出（见 AscendFocusRenderer）。
        ChargeFxState.holdBlackout();
        pendingNoContent = true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !pendingNoContent) {
            return;
        }
        pendingNoContent = false;
        NoContentScreen.open();
    }

    /**
     * ESC 结束"冻结保留"状态（黑屏保持中）。
     * <p>
     * 行为：关掉黑屏 + 清掉法阵/罩子/守卫/锚点 → 进入占位界面（{@link NoContentScreen}）。
     * <p>
     * ⭐ 与 {@code test_EscCancelBlackout} 互补：那个类处理"蓄力中 ESC"（isAscending），
     * 这里处理"冻结态 ESC" —— 冻结时 isCharging 已是 false，那边判不到。
     * <p>
     * ⚠️ 拦截原版 ESC（{@code setCanceled(true)}），否则会同时弹出暂停菜单。
     * 占位界面打开后冻结态已清除，本处理器不再拦截，ESC 就交还给界面本身去关闭。
     */
    @SubscribeEvent
    public static void onEscKey(InputEvent.Key event) {
        if (event.getKey() != GLFW.GLFW_KEY_ESCAPE || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        if (ChargeFxState.isFrozen()) {
            event.setCanceled(true);          // 拦下原版 ESC，避免同时弹暂停菜单
            finishAscension(player);          // 关黑屏 + 进占位界面
        }
    }

    private MagicCircleRenderer() {
    }

    /**
     * 玩家死亡 → 清掉客户端冻结态（死了不再带着静止的法阵/罩子）。
     * <p>
     * ⛔ 不能用 {@code LivingDeathEvent} —— 它只在<b>服务端</b>触发
     * （{@code die()} 是服务端方法），客户端 LocalPlayer 死亡时根本收不到。
     * ⇒ 改在渲染循环里每帧检测"本地玩家已死亡"，检测到就清冻结态。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        // 死亡清理（客户端侧）：冻结态 / 黑屏保持 / 待打开界面一并取消（重生后画面干净）
        if (player.isDeadOrDying()) {
            if (ChargeFxState.isFrozen()) {
                ChargeFxState.clearFrozen();
            }
            ChargeFxState.releaseBlackout();
            pendingNoContent = false;
        }

        float partialTick = event.getPartialTick();
        boolean charging = AscensionOrbItem.isCharging(player);
        float elapsed = AscensionOrbItem.getChargeTicks(player) + partialTick;

        // ---------- 黑屏完成 → 立即结束演出、进入占位界面（不等玩家松手） ----------
        // 黑幕进度走到头（isBlackDone）就算演出达成，直接跳转；
        // 黑屏遮罩的关闭交给 AscendFocusRenderer（它检测到占位界面已打开才停画，
        // 所以是"界面彻底弹出后黑屏才关掉"，中间不会露出游戏画面）。
        if (charging && AscensionOrbItem.isBlackDone(player)) {
            finishAscension(player);
            return;
        }

        // ---------- 功能①：第三人称蓄力 → 能量罩开启那一刻自动切到第一人称 ----------
        // 玩家在第三人称下蓄力，等能量罩完全升起（isDomeUp）时拉回第一人称，
        // 让"登神长阶"的屏幕演出（AscendFocusRenderer）能被完整看到。
        // ⭐ 用 wasDomeUp 检测"刚刚升起"的上升沿，只在那一刻切一次，不每帧覆盖玩家手动切视角。
        if (charging && AscensionOrbItem.isDomeUp(player) && !wasDomeUp) {
            if (minecraft.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
                minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            }
        }
        wasDomeUp = charging && AscensionOrbItem.isDomeUp(player);

        // ---------- 决定这一帧画什么：正常演出 / 冻结保留 / 松手后的倒放 ----------
        float renderElapsed;
        Vec3 center;

        if (charging) {
            if (elapsed < 1.0F) {
                return; // 刚按下，演出还没开始
            }
            // ⭐ 落下阶段"以玩家为中心"（法阵跟着走）；就位之后钉在锚点上不再移动。
            Vec3 anchor;
            if (AscensionOrbItem.isAnchored(player)) {
                Vec3 a = ChargeAnchor.get(player);
                anchor = a != null ? a : player.position();
            } else {
                anchor = player.position();
            }
            ChargeFxState.updateCharging(anchor, elapsed, AscensionOrbItem.isBlackDone(player));
            renderElapsed = elapsed;
            center = anchor;
        } else if (ChargeFxState.isFrozen()) {
            // 兜底：冻结态若因故没在松手当帧处理，这里补一次（关黑屏 + 进占位界面）
            finishAscension(player);
            return;
        } else {
            // 松手下降沿：黑屏已完成 → 直接转入占位界面；否则 → 收起动画。
            // ⚠️ onChargeStopped 内部用上一帧缓存的快照（锚点/elapsed/wasBlackDone），
            //    不现读 —— 此刻 getChargeTicks 已归零。
            ChargeFxState.onChargeStopped();
            // 黑屏完成后的松手：立刻结束演出 → 关黑屏 + 进占位界面（当帧就转，避免闪一帧）
            if (ChargeFxState.isFrozen()) {
                finishAscension(player);
                return;
            }
            if (!ChargeFxState.isRetracting()) {
                return; // 没在蓄力、也没有收起动画要播
            }
            if (ChargeFxState.advanceRetract(partialTick)) {
                return; // 收起动画播完
            }
            // 倒放：eff = 松手瞬间进度 × (1 − k)，一切按出现的方式原路收回去。
            // k 用 smoothstep（两端都平缓）× 1.2 稍微提前归零 ——
            // 让"收完"发生在时长结束之前，最后几帧法阵已经收干净（appear=0 自然不画），
            // 避免"下一帧整体啪地消失"的闪烁。
            float u = ChargeFxState.getRetractElapsed() / ChargeFxState.RETRACT_TICKS;
            float k = Mth.clamp(u * u * (3.0F - 2.0F * u) * 1.2F, 0.0F, 1.0F);
            renderElapsed = ChargeFxState.getElapsedAtRelease() * (1.0F - k);
            Vec3 a = ChargeFxState.getAnchorAtRelease();
            if (a == null || renderElapsed < 1.0F) {
                return;
            }
            center = a;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        // 运动三件套，全部在就位（LAY_DONE）之后才启动：
        // ① 齿轮自转：四层同步走同一帧（贴图内部已按传动比画好联动）
        int frame = renderElapsed >= LAY_DONE
                ? Math.floorMod((int) Math.floor(renderElapsed / FRAME_TICKS), FRAME_COUNT)
                : 0;
        // ② 整阵公转：所有层共用同一个慢速角度（刚性旋转，不破坏啮合）
        float angle = renderElapsed >= LAY_DONE
                ? (renderElapsed - LAY_DONE) * ARRAY_SPIN_PER_TICK
                : 0.0F;
        // ③ 呼吸（收起时不呼吸 —— 只在正常演出时才有）
        float breath = charging && renderElapsed >= LAY_DONE
                ? 0.84F + 0.16F * Mth.sin(renderElapsed * BREATH_SPEED)
                : 1.0F;

        for (int i = 0; i < LAYER_TEXTURES.length; i++) {
            float appear = Mth.clamp(
                    (renderElapsed - LAYER_WINDOWS[i][0]) / (float) (LAYER_WINDOWS[i][1] - LAYER_WINDOWS[i][0]),
                    0.0F, 1.0F);
            if (appear <= 0.0F) {
                continue;
            }
            renderLayer(poseStack, buffers, LAYER_TEXTURES[i], center, camera,
                    appear, angle, breath, LAYER_HEIGHTS[i], frame);
        }

        // 锁链：落阵后从行星齿轮中心飞向玩家并缠绕（renderElapsed 倒放时自动收回）
        if (renderElapsed >= LAY_DONE) {
            float fly = Mth.clamp((renderElapsed - LAY_DONE) / CHAIN_FLY_TICKS, 0.0F, 1.0F);
            float wrap = Mth.clamp((renderElapsed - LAY_DONE - CHAIN_FLY_TICKS) / CHAIN_WRAP_TICKS, 0.0F, 1.0F);
            renderChains(poseStack, buffers, center, camera, player, partialTick, angle, fly, wrap, renderElapsed);
        }

        // 能量罩：锁链缠好之后从法阵边缘升起（renderElapsed 倒放时自动回缩）
        if (renderElapsed >= DOME_START) {
            float rise = Mth.clamp((renderElapsed - DOME_START) / DOME_RISE_TICKS, 0.0F, 1.0F);
            renderDome(poseStack, buffers, center, camera, rise, angle, renderElapsed);
        }

        buffers.endBatch();
    }

    // ================================================================ 单层渲染

    /**
     * 画一层。{@code appear} ∈ [0,1] 是这一层的登场进度：
     * 从头顶上方落到地面（ease-out）、以中心为轴从小扩大、同时淡入。
     * {@code angle} 是整阵公转角（四层相同）；{@code frame} 是齿轮自转帧（四层同步）。
     */
    private static void renderLayer(PoseStack poseStack, MultiBufferSource buffers,
                                    ResourceLocation texture, Vec3 center, Vec3 camera,
                                    float appear, float angle, float breath, double layerHeight,
                                    int frame) {
        float ease = 1.0F - (1.0F - appear) * (1.0F - appear);   // ease-out：落下先快后慢
        double drop = (1.0F - ease) * DROP_HEIGHT;
        float scale = 0.55F + 0.45F * ease;
        float radius = (float) (RADIUS * scale);

        // 淡入（前半段快速变可见）× 就位后的亮度呼吸
        float fadeIn = Mth.clamp(appear * 1.8F, 0.0F, 1.0F);
        int alpha = (int) (255.0F * fadeIn * breath);

        poseStack.pushPose();
        poseStack.translate(center.x - camera.x,
                center.y - camera.y + layerHeight + drop,
                center.z - camera.z);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));   // 平铺到地面
        // 躺平之后局部 Z 轴 = 世界 +Y，绕 Z 旋转 = 绕世界竖直轴转
        poseStack.mulPose(Axis.ZP.rotationDegrees(angle));

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(texture));

        // 当前帧的纵向 UV 带（四层都是 16 帧动画表；两端内缩防 mipmap 渗色）
        float v0 = frame / (float) FRAME_COUNT + FRAME_INSET;
        float v1 = (frame + 1) / (float) FRAME_COUNT - FRAME_INSET;
        vtx(consumer, matrix, -radius, -radius, 0.0F, 0.0F, v1, alpha);
        vtx(consumer, matrix, radius, -radius, 0.0F, 1.0F, v1, alpha);
        vtx(consumer, matrix, radius, radius, 0.0F, 1.0F, v0, alpha);
        vtx(consumer, matrix, -radius, radius, 0.0F, 0.0F, v0, alpha);

        poseStack.popPose();
    }

    // ================================================================ 锁链

    /**
     * 4 条粗紫半透明锁链：从 4 个行星齿轮的轮毂中心飞出，沿<b>柔性贝塞尔曲线</b>
     * 甩向玩家，抵达后沿身体<b>螺旋缠绕</b>一圈半。
     *
     * <p>{@code fly} ∈ [0,1] 飞行进度（链尖沿曲线推进）；{@code wrap} ∈ [0,1] 缠绕进度。
     * 倒放（收起）时两者一起回退 —— 链子原路缩回齿轮。
     */
    private static void renderChains(PoseStack poseStack, MultiBufferSource buffers,
                                     Vec3 center, Vec3 camera, LocalPlayer player,
                                     float partialTick, float arrayAngleDeg, float fly, float wrap,
                                     float chainTime) {
        if (fly <= 0.0F) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(TEX_CHAIN));
        VertexConsumer cuffConsumer = buffers.getBuffer(RenderType.entityTranslucent(TEX_CUFF));
        // ⭐ 柔性摆动的时间源用 renderElapsed（冻结态下是定值 → 链子静止），
        //    而不是实时 tickCount（否则"冻结"后链子还会一直晃）。
        float now = chainTime;
        Vec3 body = player.getPosition(partialTick);

        for (int c = 0; c < CHAINS; c++) {
            // ---- 玩家身体朝向与肢体环位置（飞行段终点 = 环的位置，保证链条连到环上）----
            float yaw = (float) Math.toRadians(player.getYHeadRot());
            Vec3 facing = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
            Vec3 right = facing.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();

            int limb = c;                      // 0=左臂 1=右臂 2=左腿 3=右腿
            boolean isArm = limb < 2;
            boolean isLeft = (limb % 2 == 0);
            double sideSign = isLeft ? -1.0D : 1.0D;
            double offsetX = isArm ? ARM_OFFSET_X : LEG_OFFSET_X;
            double anchorY = isArm ? ARM_Y : LEG_Y;
            double length = isArm ? ARM_LENGTH : LEG_LENGTH;
            double ringY = anchorY - length * 0.5D;
            Vec3 ringCenter = new Vec3(body.x + right.x * offsetX * sideSign,
                    body.y + ringY,
                    body.z + right.z * offsetX * sideSign);

            // ---- 起点：行星齿轮轮毂中心（贴图偏移 → 世界，再随整阵公转旋转） ----
            double planetAng = Math.toRadians(45.0 + 90.0 * c);
            double tx = PLANET_DIST_PX * Math.cos(planetAng);
            double ty = PLANET_DIST_PX * Math.sin(planetAng);
            double rad = Math.toRadians(arrayAngleDeg);
            double rx = tx * Math.cos(rad) - ty * Math.sin(rad);
            double ry = tx * Math.sin(rad) + ty * Math.cos(rad);
            Vec3 hub = new Vec3(center.x + rx * PX_TO_WORLD,
                    center.y + LAYER_HEIGHTS[2],
                    center.z - ry * PX_TO_WORLD);

            // ---- 飞行段：二次贝塞尔 + 柔性摆动，终点 = 环的位置 ----
            Vec3 mid = hub.add(ringCenter).scale(0.5D);
            Vec3 outward = hub.subtract(center);
            if (outward.lengthSqr() < 1.0E-6D) {
                outward = new Vec3(1.0D, 0.0D, 0.0D);
            }
            outward = outward.normalize();
            Vec3 control = mid.add(0.0D, FLY_ARC_HEIGHT, 0.0D)
                    .add(outward.scale(FLY_ARC_OUTWARD));
            // 柔性摆动：控制点随时间横向漂移，链条像被风吹着
            double wob = Math.sin(now * FLY_WOBBLE_SPEED + c * 1.7D) * FLY_WOBBLE;
            Vec3 side = outward.cross(new Vec3(0.0D, 1.0D, 0.0D));
            if (side.lengthSqr() < 1.0E-6D) {
                side = new Vec3(0.0D, 0.0D, 1.0D);
            }
            control = control.add(side.normalize().scale(wob));

            // 链尖沿曲线推进（ease-in-out：先快后慢地甩到位），终点就是环的位置
            float sFly = fly * fly * (3.0F - 2.0F * fly);
            drawCurveChain(poseStack, consumer, camera, hub, control, ringCenter, sFly);

            // ---- 拴环段：飞行到位后，环从大收小铐在肢体上 ----
            if (wrap > 0.0F) {
                float ringScale = (float) (0.4F + 0.6F * wrap);
                placeFlatRing(poseStack, cuffConsumer, camera, ringCenter, ringScale, c);
            }
        }
    }

    /**
     * 沿一条二次贝塞尔曲线摆放链环，链尖画到参数 {@code sMax} ∈ [0,1] 为止。
     * <p>
     * 做法：细采样曲线求总长 → 按 {@code LINK_PITCH} 在弧长上等距取点 →
     * 每个点沿曲线切线方向摆一个链环。
     */
    private static void drawCurveChain(PoseStack poseStack, VertexConsumer consumer, Vec3 camera,
                                       Vec3 p0, Vec3 p1, Vec3 p2, float sMax) {
        if (sMax <= 0.001F) {
            return;
        }
        int steps = 48;
        double[] cum = new double[steps + 1];
        Vec3 prev = bezier(p0, p1, p2, 0.0D);
        for (int i = 1; i <= steps; i++) {
            Vec3 p = bezier(p0, p1, p2, i / (double) steps);
            cum[i] = cum[i - 1] + p.subtract(prev).length();
            prev = p;
        }
        double total = cum[steps] * sMax;
        int links = (int) Math.min(MAX_LINKS, Math.floor(total / LINK_PITCH));

        int seg = 1;
        double acc = 0.0D;
        prev = bezier(p0, p1, p2, 0.0D);
        for (int placed = 0; placed < links; placed++) {
            double target = (placed + 0.5) * LINK_PITCH;
            while (seg <= steps && cum[seg] < target) {
                seg++;
            }
            if (seg > steps) {
                break;
            }
            double t = seg / (double) steps;
            Vec3 at = bezier(p0, p1, p2, t);
            Vec3 dir = bezier(p0, p1, p2, Math.min(1.0D, t + 0.02D)).subtract(at);
            placeLink(poseStack, consumer, camera, at, dir, placed);
        }
    }

    /** 在 {@code pos} 处沿 {@code dir} 方向摆一个链环。 */
    private static void placeLink(PoseStack poseStack, VertexConsumer consumer, Vec3 camera,
                                  Vec3 pos, Vec3 dir, int index) {
        Vec3 d = dir.normalize();
        poseStack.pushPose();
        poseStack.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);
        poseStack.mulPose(new Quaternionf().rotationTo(UP,
                new Vector3f((float) d.x, (float) d.y, (float) d.z)));
        // 相邻链环互相垂直 90° —— 真实链条串起来的样子
        poseStack.mulPose(Axis.YP.rotationDegrees(index * 90.0F));
        ring(consumer, poseStack.last().pose());
        poseStack.popPose();
    }

    /**
     * 在 {@code center} 处放一个**水平环**（环面法线竖直），套住竖直的肢体。
     * <p>
     * 复用 {@link #ring} 的跑道形厚板几何 —— 它默认画在 XY 平面（环面法线 +Z），
     * 这里先绕 X 转 -90° 让它平躺到 XZ 平面，环面法线就变成竖直 +Y。
     * 用**环专用贴图** {@link #TEX_CUFF}（区别于飞行段链环的 {@link #TEX_CHAIN}）。
     * {@code scale} 控制环的大小（收紧扣合的动画：从大变小）。
     */
    private static void placeFlatRing(PoseStack poseStack, VertexConsumer chainConsumer, Vec3 camera,
                                      Vec3 center, float scale, int index) {
        // 用环专用贴图（区别于链环），但复用主循环的 MultiBufferSource 以便统一 endBatch。
        // 这里从同一个 buffers 拿一个独立 RenderType 的 consumer 需要 buffers 引用，
        // 所以由调用方传入已用 TEX_CUFF 建好的 consumer。
        poseStack.pushPose();
        poseStack.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));   // 环面从 XY 转到 XZ（水平）
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(Axis.YP.rotationDegrees(index * 37.0F));   // 各环略有错位，视觉不死板
        ring(chainConsumer, poseStack.last().pose());
        poseStack.popPose();
    }

    /** 二次贝塞尔。 */
    private static Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double u = 1.0D - t;
        return p0.scale(u * u).add(p1.scale(2.0D * u * t)).add(p2.scale(t * t));
    }

    // ================================================================ 能量罩

    /**
     * 法阵边缘升起的**紫色能量罩** —— 一圈竖直的圆柱形能量膜。
     *
     * <p>做法：把圆周分成 {@link #DOME_SEGMENTS} 段，每段一个竖直四边形，
     * 贴 {@link #TEX_DOME}（u = 绕圆周，v = 从罩顶到罩底）。
     *
     * <p>升起动画：{@code rise} ∈ [0,1] 驱动两个量 ——
     * 罩体高度从 0 长到 {@link #DOME_HEIGHT}（像从地面往上"长"出来），
     * 同时透明度淡入。罩体会随时间极缓慢地旋转（能量流动感）。
     *
     * <p>渲染类型用 {@code entityTranslucent}（**不剔除**）—— 从罩内往外看、
     * 从罩外往里看都得可见，因此单面圆柱的两个方向都要能画出来。
     */
    private static void renderDome(PoseStack poseStack, MultiBufferSource buffers,
                                   Vec3 center, Vec3 camera, float rise, float arrayAngle,
                                   float elapsed) {
        if (rise <= 0.001F) {
            return;
        }
        float ease = 1.0F - (1.0F - rise) * (1.0F - rise);       // ease-out：升起先快后慢
        double height = DOME_HEIGHT * ease;
        int alpha = (int) (DOME_ALPHA * Mth.clamp(rise * 1.6F, 0.0F, 1.0F));

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(TEX_DOME));

        poseStack.pushPose();
        poseStack.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
        // 罩体自身缓慢自转，配合贴图里的竖向流线 → 能量在流动
        poseStack.mulPose(Axis.YP.rotationDegrees(elapsed * 0.35F));

        Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < DOME_SEGMENTS; i++) {
            double a0 = TAU * i / DOME_SEGMENTS;
            double a1 = TAU * (i + 1) / DOME_SEGMENTS;
            float x0 = (float) (DOME_RADIUS * Math.cos(a0));
            float z0 = (float) (DOME_RADIUS * Math.sin(a0));
            float x1 = (float) (DOME_RADIUS * Math.cos(a1));
            float z1 = (float) (DOME_RADIUS * Math.sin(a1));
            float u0 = i / (float) DOME_SEGMENTS;
            float u1 = (i + 1) / (float) DOME_SEGMENTS;

            // 下边(u,v=1 罩底) → 上边(v=0 罩顶)。v 方向与贴图约定一致：贴图上边是罩顶。
            vtx(consumer, matrix, x0, 0.0F, z0, u0, 1.0F, alpha);
            vtx(consumer, matrix, x1, 0.0F, z1, u1, 1.0F, alpha);
            vtx(consumer, matrix, x1, (float) height, z1, u1, 0.0F, alpha);
            vtx(consumer, matrix, x0, (float) height, z0, u0, 0.0F, alpha);
        }
        poseStack.popPose();
    }

    /**
     * 一个链环：**跑道形厚板**（参考图的样式）—— 两条平行直边 + 两端半圆弯，
     * 由一块宽 {@code LINK_WIDTH}、厚 {@code LINK_THICK} 的板沿跑道中心线扫掠而成。
     *
     * <p>局部坐标：链环长轴沿 +Y（链的延伸方向），环面躺在 XY 平面，
     * 板宽方向沿局部 Z。长轴沿 +Y 正好与 {@code placeLink} 的"局部 +Y 对准链方向"配合。
     *
     * <p>实现：中心线按弧长均匀采样 {@link #LINK_SEGMENTS} 段，每个采样点放一个
     * 4 角截面（±厚 × ±宽），相邻截面的对应角连成 4 条面（内侧/外侧/背面/正面）。
     * 渲染类型不剔除背面，所以只需发外表面。
     */
    private static void ring(VertexConsumer consumer, Matrix4f matrix) {
        double r = LINK_RADIUS;
        double d = LINK_STRAIGHT;
        double halfW = LINK_WIDTH * 0.5D;
        double halfT = LINK_THICK * 0.5D;
        int segs = LINK_SEGMENTS;

        // 中心线按弧长均分：总长 = 2×直段 + 2π×半径
        double perimeter = 2.0D * d + TAU * r;
        double[] xs = new double[segs + 1];
        double[] ys = new double[segs + 1];
        double[] txs = new double[segs + 1];
        double[] tys = new double[segs + 1];
        for (int i = 0; i <= segs; i++) {
            double s = perimeter * i / segs;                 // 沿中心线的弧长
            double x, y, tx, ty;
            if (s < d) {                                     // 右直段（下 → 上）
                x = r; y = -d * 0.5D + s; tx = 0.0D; ty = 1.0D;
            } else if (s < d + Math.PI * r) {                // 上半圆（右 → 左，经顶）
                double a = (s - d) / r;                      // 0 → π
                x = r * Math.cos(a); y = d * 0.5D + r * Math.sin(a);
                tx = -Math.sin(a); ty = Math.cos(a);
            } else if (s < 2.0D * d + Math.PI * r) {         // 左直段（上 → 下）
                x = -r; y = d * 0.5D - (s - d - Math.PI * r); tx = 0.0D; ty = -1.0D;
            } else {                                         // 下半圆（左 → 右，经底）
                double a = (s - 2.0D * d - Math.PI * r) / r + Math.PI;   // π → 2π
                x = r * Math.cos(a); y = -d * 0.5D + r * Math.sin(a);
                tx = -Math.sin(a); ty = Math.cos(a);
            }
            xs[i] = x; ys[i] = y; txs[i] = tx; tys[i] = ty;
        }

        // 相邻截面连线，扫出 4 个面。截面 4 角 = (±厚 along 法向) × (±宽 along Z)。
        // 法向 n = tangent 旋转 -90°（指向跑道外侧）。
        for (int i = 0; i < segs; i++) {
            double nx0 = tys[i], ny0 = -txs[i];              // 外法向（截面 i）
            double nx1 = tys[i + 1], ny1 = -txs[i + 1];      // 截面 i+1
            double u0 = i / (double) segs, u1 = (i + 1) / (double) segs;

            // 4 角：[径向 -t/2 / +t/2][Z 向 -w/2 / +w/2]
            double ax0 = xs[i] - nx0 * halfT, ay0 = ys[i] - ny0 * halfT;   // 内
            double bx0 = xs[i] + nx0 * halfT, by0 = ys[i] + ny0 * halfT;   // 外
            double ax1 = xs[i + 1] - nx1 * halfT, ay1 = ys[i + 1] - ny1 * halfT;
            double bx1 = xs[i + 1] + nx1 * halfT, by1 = ys[i + 1] + ny1 * halfT;

            // 内侧面（n = -t/2，z 从 -w/2 到 +w/2）
            quad(consumer, matrix, ax0, ay0, -halfW, ax1, ay1, -halfW, ax1, ay1, halfW, ax0, ay0, halfW, u0, u1);
            // 外侧面（n = +t/2）
            quad(consumer, matrix, bx0, by0, halfW, bx1, by1, halfW, bx1, by1, -halfW, bx0, by0, -halfW, u0, u1);
            // 背面（z = -w/2，n 从 -t/2 到 +t/2）
            quad(consumer, matrix, ax0, ay0, -halfW, ax1, ay1, -halfW, bx1, by1, -halfW, bx0, by0, -halfW, u0, u1);
            // 正面（z = +w/2）
            quad(consumer, matrix, bx0, by0, halfW, bx1, by1, halfW, ax1, ay1, halfW, ax0, ay0, halfW, u0, u1);
        }
    }

    /** 扫掠体的一个面片：截面 i 的两角 → 截面 i+1 的两角。u 沿链长方向。 */
    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double u0, double u1) {
        float v0 = 0.0F, v1 = 1.0F;
        vtx(consumer, matrix, (float) x0, (float) y0, (float) z0, (float) u0, v0, CHAIN_ALPHA);
        vtx(consumer, matrix, (float) x1, (float) y1, (float) z1, (float) u1, v0, CHAIN_ALPHA);
        vtx(consumer, matrix, (float) x2, (float) y2, (float) z2, (float) u1, v1, CHAIN_ALPHA);
        vtx(consumer, matrix, (float) x3, (float) y3, (float) z3, (float) u0, v1, CHAIN_ALPHA);
    }

    // ================================================================ 顶点工具

    /**
     * 写一个顶点。
     * <p>
     * ⛔ 必须传 {@code matrix}：{@code VertexConsumer} 只有带矩阵的重载会做坐标变换。
     */
    private static void vtx(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
                            float u, float v, int alpha) {
        consumer.vertex(matrix, x, y, z)
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
