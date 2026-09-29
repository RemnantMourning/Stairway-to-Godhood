package com.rem.stairwaytogodhood.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

/**
 * 第三阶段「登神长阶」的<b>镜头演出</b> —— 一层层把画面收拢、染紫、最后被黑吞没。
 *
 * <h2>四层遮罩，中心始终给宝珠留位置（直到最后被吞）</h2>
 * <ol>
 *     <li><b>锥形遮罩</b>（{@code ascend_vignette}）：中心透明、四周紫黑，
 *         从边缘向中心收拢 —— 宝珠清晰可见。</li>
 *     <li><b>紫幕</b>（{@code ascend_purple}）：**中心留透明孔**的径向贴图，
 *         紫色从四周向内包拢，宝珠仍悬在孔中 —— ⛔ 纯色 fill 铺满会盖死宝珠（翻车实录）。</li>
 *     <li><b>闪电波纹环</b>（{@code ascend_ring}，连续缩放）：
 *         环从屏幕外向内推进 —— "从外到内的引力"，细描边 + 尺寸反比补偿压亮度。</li>
 *     <li><b>黑幕</b>（{@code ascend_black}）：**从中心（宝珠处）向外扩散** ——
 *         宽渐变柔光黑斑从小到大连续放大（无平台区 ⇒ 无可见边界），
 *         最后 28% 行程全屏 fill 兜底补到纯黑。</li>
 * </ol>
 *
 * <h2>三件事同时进行</h2>
 * 除了遮罩，还会取消 HUD（`RenderGuiEvent.Pre` → `setCanceled(true)`）
 * 并放大 FOV（`ComputeFovModifierEvent`）。
 *
 * <h2>⛔ 为什么遮罩中心必须留透明</h2>
 * GUI 在世界之后渲染，而宝珠是第一人称手持物品（`RenderHandEvent` 里画完）。
 * 遮罩永远压在宝珠<b>上面</b> —— 所以紫幕之前的层必须给中心留出可见区，
 * 黑幕阶段才"按剧情"从宝珠处涌出、把一切吞掉。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AscendFocusRenderer {

    private static final ResourceLocation VIGNETTE =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_vignette.png");
    private static final ResourceLocation RING =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_ring.png");
    private static final ResourceLocation PURPLE_VIGNETTE =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_purple.png");
    private static final ResourceLocation RUNES =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_runes.png");
    private static final ResourceLocation FLARE =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_flare.png");
    private static final ResourceLocation BLACK =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/ascend_black.png");

    /** 遮罩类贴图的边长（与生成脚本一致）。 */
    private static final int TEX_SIZE = 512;
    /** 环贴图的边长，以及"环的外接半径 / 贴图半宽"（与 gen_ascend_overlay.py 一致）。 */
    private static final int RING_TEX = 256;
    private static final float RING_R_RATIO = 0.42F;
    /** 符文环 / 闪光贴图的边长（与 gen_ascend_fx.py 一致）。 */
    private static final int FX_TEX = 512;

    // ---------------------------------------------------------------- 炫酷层参数

    /** 符文环每 tick 旋转角（度）—— 慢转才神秘，快了像风扇。 */
    private static final float RUNE_SPIN_PER_TICK = 0.9F;
    /** 符文环的绘制边长（相对屏幕高）。 */
    private static final float RUNE_SIZE = 0.66F;
    /** 过曝闪光的窗口：black 进度的前 12% 是"白光爆发"的一闪。 */
    private static final float FLASH_WINDOW = 0.12F;
    /** 闪光峰值不透明度。 */
    private static final float FLASH_ALPHA = 0.9F;
    /** 闪光绘制边长（相对屏幕高）。 */
    private static final float FLASH_SIZE = 1.35F;
    /** 震动的基础幅度（GUI 坐标）。 */
    private static final float SHAKE_BASE = 0.8F;
    /** 震动的狂暴加成幅度。 */
    private static final float SHAKE_RAGE = 2.6F;
    /**
     * 环的整体不透明度 —— 只做背景氛围。
     * <p>
     * 从 0.65 一路压到 0.22：环是"底噪"而不是主角，
     * 之前即使贴图调淡了、乘上这个系数后仍然亮得抢眼（实机截图确认）。
     */
    private static final float RING_ALPHA = 0.22F;
    /**
     * 环的"尺寸反比补偿"指数。
     * <p>
     * ⛔ 连续缩放有个副作用：**环越大 ⇒ 贴图被放得越大 ⇒ 线条越粗 ⇒ 视觉上越亮**，
     * 实机就是"越靠外的大环越扎眼"。
     * ⇒ 让 alpha 与绘制尺寸成反比（只降不升），外圈自动淡下去。
     * 指数从 0.9 提到 1.1 —— 贴图描边画细后仍要防止外圈大环堆亮。
     */
    private static final double RING_SIZE_COMPENSATION = 1.1D;
    /** 同时存在几个环（错开相位 ⇒ 看起来是连续流动的波纹）。 */
    private static final int RING_COUNT = 3;
    /**
     * 环向内收拢的速度：每 tick 前进多少（1.0 = 一个完整行程）。
     * 从 0.022 提到 0.034 —— 快一点才有"被吸向中心"的引力感。
     */
    private static final float RING_SPEED = 0.034F;
    /** 环的起止半径（相对"屏幕半径"= 对角线的一半）——
     *  起点 > 1.0 保证它在屏幕外，循环回来时不会被看见（这是平滑的关键）。 */
    private static final float RING_R_START = 1.40F;
    private static final float RING_R_END = 0.18F;

    /**
     * 视角扩大的最大幅度（FOV 乘数最高 ×1.45）。
     * 黑幕阶段会反过来收窄（坠入深渊感），见 {@link #onComputeFov}。
     */
    private static final float FOV_BOOST = 0.45F;
    /** 黑幕阶段 FOV 从"拉远"切换为"收窄"的最大收缩量。 */
    private static final float FOV_DIVE = 0.25F;
    /**
     * 黑幕扩散主体的起止尺寸 —— 相对屏幕<b>最长边</b>（正方形绘制，圆形不变椭圆）。
     * <p>
     * 早期 1.1×：黑斑刚从中心"冒头"，渐变带已覆盖屏幕大半 → 无边界；
     * 结束 2.6×：黑区边缘已推出屏幕，配合兜底 fill 到达纯黑。
     */
    private static final float BLACK_MIN_SCALE = 1.1F;
    private static final float BLACK_MAX_SCALE = 2.6F;
    /** 黑幕主体的整体 alpha 提速（前 62% 行程就涨到 1，之后纯靠尺寸外推）。 */
    private static final float BLACK_BODY_ALPHA_RATE = 1.25F;
    /** 从这个进度起，全屏 fill 开始兜底补黑（保证四角也到纯黑）。 */
    private static final float BLACK_CAP_START = 0.55F;
    /** 兜底 fill 的颜色（深紫黑，与黑幕贴图同色系）。 */
    private static final int BLACK_CAP_RGB = 0x0E061E;
    /** 从这个进度起，整屏从深紫黑沉入**纯黑**（最后的收尾）。 */
    private static final float BLACK_FINAL_START = 0.82F;

    private AscendFocusRenderer() {
    }

    /** 平滑缓动：让每一层的出现/消失都不是线性的硬起步。 */
    private static float smooth(float p) {
        return p * p * (3.0F - 2.0F * p);
    }

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        // ⭐ 占位界面已接管全屏（它自己铺纯黑底 + 写字）→ 遮罩不再画 = "黑屏关闭"。
        //    因为界面打开本身也是纯黑底，所以关掉遮罩的瞬间画面毫无变化（无缝），
        //    且顺序上一定是"界面先彻底弹出、黑屏才让位"，中间不会露出游戏画面。
        if (minecraft.screen instanceof NoContentScreen) {
            ChargeFxState.releaseBlackout();   // 界面已弹出，黑屏使命完成
            return;
        }

        // 黑屏保持（演出收尾后、占位界面弹出前）：继续定格纯黑。
        // 也涵盖"冻结态"（黑屏完成后松手保留法阵的旧路径）。
        if (ChargeFxState.isFrozen() || ChargeFxState.isBlackoutHeld()) {
            renderHeldBlack(event, minecraft, player);
            return;
        }

        if (!AscensionOrbItem.isAscending(player)) {
            return;
        }

        float partialTick = event.getPartialTick();
        // 所有进度都过一遍 smoothstep —— 起落都平缓，不会"啪"地开始/结束
        float focus = smooth(AscensionOrbItem.getAscendFocus(player, partialTick));
        float purple = smooth(AscensionOrbItem.getAscendPurple(player, partialTick));
        float black = AscensionOrbItem.getAscendBlack(player, partialTick);
        if (focus <= 0.0F && purple <= 0.0F) {
            return;
        }

        // ① 屏幕界面消失（原版这一帧的 HUD 全部不画）
        event.setCanceled(true);

        Window window = event.getWindow();
        int w = window.getGuiScaledWidth();
        int h = window.getGuiScaledHeight();
        GuiGraphics gui = event.getGuiGraphics();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // ================================================================
        // ⓪ 镜头震动：双正弦（不同频率）叠出"不规则的颤"，幅度随狂暴度
        //    与黑幕推进增长。整个遮罩层一起震 = "镜头"在震，而不是贴图在飘。
        //    ⭐ 全屏层都向外扩 SHAKE_PAD 绘制，否则震动时会露出屏幕边的缝。
        // ================================================================
        PoseStack pose = gui.pose();
        double time = AscensionOrbItem.getChargeTicks(player) + partialTick;
        float rage = AscensionOrbItem.getAscendRage(player, partialTick);
        float shakeAmp = SHAKE_BASE + SHAKE_RAGE * Math.max(rage, black * 0.8F);
        float shakeX = (float) Math.sin(time * 0.9D) * shakeAmp;
        float shakeY = (float) Math.cos(time * 1.37D) * shakeAmp;
        pose.pushPose();
        pose.translate(shakeX, shakeY, 0.0F);
        final int PAD = (int) Math.ceil(shakeAmp) + 2;

        // ================================================================
        // ② 锥形暗边：只在 focus 阶段（紫色还没接管前）收拢。
        //    一旦紫色铺开，锥形暗边就"融入"下面的紫幕，不再单独叠一层
        //    —— 避免"紫底 + 紫黑边"糊在一起明暗发脏。
        // ================================================================
        float vignetteAlpha = focus * (1.0F - purple);
        if (vignetteAlpha > 0.01F) {
            drawFullscreen(gui, VIGNETTE, TEX_SIZE, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE,
                    -PAD, -PAD, w + PAD * 2, h + PAD * 2, vignetteAlpha);
        }

        // ================================================================
        // ③ 紫幕：**中心留透明孔**的径向贴图 —— 紫色从四周向内包拢，
        //    宝珠始终悬在孔中可见（⛔ 纯色 fill 铺满会把宝珠盖死，实机已翻车）。
        //    与锥形暗边交替时两层都连续渐变，中心亮度无跳变。
        // ================================================================
        if (purple > 0.01F) {
            drawFullscreen(gui, PURPLE_VIGNETTE, TEX_SIZE, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE,
                    -PAD, -PAD, w + PAD * 2, h + PAD * 2, purple);
        }

        // ================================================================
        // ④ 闪电波纹：单帧贴图 + 连续缩放，环向内吸。
        //    由 focus 驱动、黑幕接管后淡出。
        // ================================================================
        if (focus > 0.01F) {
            float fade = focus * (1.0F - black);
            if (fade > 0.01F) {
                double base = Math.hypot(w, h) * 0.5D;
                for (int k = 0; k < RING_COUNT; k++) {
                    double ph = (time * RING_SPEED + (double) k / RING_COUNT) % 1.0D;
                    double radius = Mth.lerp((float) ph, RING_R_START, RING_R_END);
                    float env = (float) Math.pow(Math.sin(Math.PI * ph), 0.7D);
                    double half = radius * base / RING_R_RATIO;
                    int size = (int) (half * 2.0D);
                    if (size <= 0) {
                        continue;
                    }
                    double comp = Math.min(1.0D,
                            Math.pow(base / Math.max(1.0D, size), RING_SIZE_COMPENSATION));
                    drawFullscreen(gui, RING, RING_TEX, RING_TEX, 0, 0, RING_TEX, RING_TEX,
                            (w - size) / 2, (h - size) / 2, size, size,
                            (float) (fade * env * RING_ALPHA * comp));
                }
            }
        }

        // ================================================================
        // ④½ 符文环：紫幕阶段绕宝珠缓缓旋转的古老符文 —— 仪式感的核心。
        //    随紫幕淡入，黑幕推进时加速旋转并淡出（临终前的疯狂）。
        // ================================================================
        if (purple > 0.05F && black < 0.9F) {
            float runeAlpha = purple * (1.0F - smooth(Math.min(1.0F, black * 1.25F)));
            if (runeAlpha > 0.01F) {
                // 黑幕一来就加速：像"失控"一样
                float spinBoost = 1.0F + 5.0F * black;
                float angle = (float) (time * RUNE_SPIN_PER_TICK * spinBoost);
                int size = Math.round(h * RUNE_SIZE);
                pose.pushPose();
                pose.translate(w / 2.0F, h / 2.0F, 0.0F);
                pose.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(angle)));
                drawFullscreen(gui, RUNES, FX_TEX, FX_TEX, 0, 0, FX_TEX, FX_TEX,
                        -size / 2, -size / 2, size, size, runeAlpha);
                pose.popPose();
            }
        }

        // ================================================================
        // ④¾ 过曝闪光：黑幕刚冒头的一瞬间，中心白光"嗡"地爆一下再熄灭 ——
        //    能量吸满的爆发点，随后被黑幕吞掉。sin(π·p) = 0→1→0 的一闪。
        // ================================================================
        if (black > 0.001F && black < FLASH_WINDOW) {
            float fp = black / FLASH_WINDOW;
            float flashAlpha = (float) Math.sin(Math.PI * fp) * FLASH_ALPHA;
            int size = Math.round(h * FLASH_SIZE);
            drawFullscreen(gui, FLARE, FX_TEX, FX_TEX, 0, 0, FX_TEX, FX_TEX,
                    (w - size) / 2, (h - size) / 2, size, size, flashAlpha);
        }

        // ================================================================
        // ⑤ 黑幕：**从中心（宝珠处）向外扩散** —— 两段式，全程无可见边界：
        //
        //    ① 扩散主体：一张"中心黑、向外极宽缓衰减（(1-d)^0.8，无平台区）"的
        //       柔光黑斑，从 1.1×屏幕 连续放大到 2.6×屏幕，同时整体 alpha 从 0 涨到 1。
        //       早期黑从中心"冒头"（整体很淡）→ 中期暗区向外推（方向感）→
        //       渐变带横跨大半屏幕，任何时刻都找不到"圆的分界线"。
        //    ② 兜底：最后 28% 行程叠一层全屏 fill，把贴图衰减尾巴补到纯黑。
        //       fill 从 0 连续淡入，衔接处无跳变。
        // ================================================================
        if (black > 0.001F) {
            float eased = smooth(black);

            // ① 扩散主体（正方形绘制 = 正圆扩散，用最长边算避免拉成椭圆）
            int maxDim = Math.max(w, h);
            int size = Math.round(maxDim * Mth.lerp(eased, BLACK_MIN_SCALE, BLACK_MAX_SCALE)) + PAD * 2;
            float bodyAlpha = Math.min(1.0F, black * BLACK_BODY_ALPHA_RATE);
            drawFullscreen(gui, BLACK, TEX_SIZE, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE,
                    (w - size) / 2, (h - size) / 2, size, size, bodyAlpha);

            // ② 兜底补黑（最后一段才出现，从 0 连续淡入）
            float cap = Mth.clamp((black - BLACK_CAP_START) / (1.0F - BLACK_CAP_START), 0.0F, 1.0F);
            if (cap > 0.001F) {
                int ca = (int) (255.0F * smooth(cap));
                gui.fill(-PAD, -PAD, w + PAD * 2, h + PAD * 2, (ca << 24) | BLACK_CAP_RGB);
            }

            // ③ 纯黑收尾：最后 15% 整屏从深紫黑沉入 #000000 ——
            //    深紫是过程色，最终必须归于真正的黑（用户实测残留深紫，已补）。
            float fin = Mth.clamp((black - BLACK_FINAL_START) / (1.0F - BLACK_FINAL_START), 0.0F, 1.0F);
            if (fin > 0.001F) {
                int fa = (int) (255.0F * smooth(fin));
                gui.fill(-PAD, -PAD, w + PAD * 2, h + PAD * 2, fa << 24);
            }
        }

        // 震动包裹结束：复原 pose（RenderGuiEvent 共享 PoseStack，改了必须还回去）
        pose.popPose();

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /**
     * "黑屏保持"期间的遮罩：定格一块**全屏纯黑**（+微震），
     * 不走 focus/purple/black 的进度计算 —— 那些都依赖蓄力状态，而此刻蓄力已停。
     * <p>
     * 用于两处：① 演出收尾后、占位界面弹出前（{@code isBlackoutHeld}）；
     * ② 冻结态（黑屏完成后松手保留法阵的旧路径）。
     * 纯黑与占位界面底色一致 ⇒ 界面顶上来的瞬间画面无变化。
     */
    private static void renderHeldBlack(RenderGuiEvent.Pre event, Minecraft minecraft, LocalPlayer player) {
        event.setCanceled(true);   // 隐藏 HUD

        Window window = event.getWindow();
        int w = window.getGuiScaledWidth();
        int h = window.getGuiScaledHeight();
        GuiGraphics gui = event.getGuiGraphics();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // 轻微持续震动（冻结态仍保留一点"能量待机"的颤动感）
        PoseStack pose = gui.pose();
        double time = player.tickCount + event.getPartialTick();
        float amp = SHAKE_BASE * 0.5F;
        float sx = (float) Math.sin(time * 0.9D) * amp;
        float sy = (float) Math.cos(time * 1.37D) * amp;
        pose.pushPose();
        pose.translate(sx, sy, 0.0F);

        // 全屏纯黑（不透明白字顶满）
        gui.fill(-8, -8, w + 8, h + 8, 0xFF000000);

        pose.popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /**
     * 把一张贴图（或它的某一帧）铺到屏幕的一块矩形上。
     *
     * @param texW/texH  贴图实际尺寸（用于 UV 归一化）
     * @param uW/uH      要取的那块 UV 区域大小（选帧就用整帧的边长）
     * @param uOff/vOff  UV 起点（选帧 = frame * 单帧边长）
     */
    private static void drawFullscreen(GuiGraphics gui, ResourceLocation tex,
                                       int texW, int texH, int uOff, int vOff, int uW, int uH,
                                       int x, int y, int w, int h, float alpha) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        gui.blit(tex, x, y, w, h, (float) uOff, (float) vOff, uW, uH, texW, texH);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        if (!(event.getPlayer() instanceof LocalPlayer player)) {
            return;
        }
        // 占位界面已接管 → FOV 恢复正常（不再收窄）
        if (Minecraft.getInstance().screen instanceof NoContentScreen) {
            return;
        }
        // 黑屏保持 / 冻结态：FOV 定格在收窄状态（黑屏还在，视角也保持）
        if (ChargeFxState.isFrozen() || ChargeFxState.isBlackoutHeld()) {
            event.setNewFovModifier(event.getFovModifier() * (1.0F - FOV_DIVE));
            return;
        }
        if (!AscensionOrbItem.isAscending(player)) {
            return;
        }
        float focus = AscensionOrbItem.getAscendFocus(player, 0.0F);
        float black = AscensionOrbItem.getAscendBlack(player, 0.0F);
        // 前半段：视野拉远（×1.45，天地广阔的"升格"感）；
        // 黑幕一来反过来收窄 —— 像被吸进宝珠深处的"坠入"感。两段都连续过渡。
        float dive = AscendFocusRenderer.smooth(black);
        float mod = 1.0F + FOV_BOOST * focus * (1.0F - dive) - FOV_DIVE * dive;
        event.setNewFovModifier(event.getFovModifier() * mod);
    }
}
