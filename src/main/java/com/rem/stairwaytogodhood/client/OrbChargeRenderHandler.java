package com.rem.stairwaytogodhood.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蓄力时第一人称的宝珠渲染 —— <b>完全自己画，不走原版手部渲染链</b>。
 *
 * <h2>为什么推翻之前的 mixin 方案</h2>
 * 之前的做法是在原版渲染链的不同位置注入（{@code renderArmWithItem} 的 HEAD /
 * {@code renderItem} 调用点 / {@code renderModelLists}），想"顺着原版变换把物品摆正"。
 * 但原版链上叠了太多层互相牵制的变换（手臂平移、display 的旋转/缩放/平移、
 * HUD 的正交投影），每一个都会影响读数：
 * <ul>
 *     <li>在 {@code renderItem} 之前读不到 display 旋转（它要进 {@code render} 才应用）；</li>
 *     <li>在 {@code renderModelLists} 里读到了，但那个方法是通用的 ——
 *         蓄力时连<b>物品栏格子里的图标</b>都会被自校准拽走
 *         （快捷栏是 HUD、不走 {@code screen} 过滤），而且 HUD 用的是正交投影，
 *         归位后的坐标直接飞到屏幕左上角。</li>
 * </ul>
 * <p>
 * 所以这里换成<b>釜底抽薪</b>：{@code RenderHandEvent} 把原版那只手取消掉，
 * 宝珠由自己来画 —— 位置、大小、朝向全部由这里直接指定，
 * 不再经过手臂变换、display 变换，也就没有那些牵扯。
 *
 * <h2>渲染细节</h2>
 * <ul>
 *     <li><b>位置</b>：{@code translate(0, 0, -DISTANCE)} —— 相机正前方。
 *         屏幕正中就是准星，所以球心天然落在准星上，<b>不需要任何手调数值</b>。</li>
 *     <li><b>朝向</b>：用 {@link ItemDisplayContext#GUI} —— 物品栏里的物品就是
 *         "正面对着你"的（你能看到贴图图案），天然不用转角度。</li>
 *     <li><b>大小</b>：{@code SCALE * t}，跟着抬臂进度一起从小变大，
 *         和"举起来"的动作是同一个节奏。</li>
 * </ul>
 *
 * <h2>为什么事件是安全的第一道关卡</h2>
 * {@code RenderHandEvent} 对每只手各触发一次。这里只取消两种情况：
 * 蓄力手（换成自己画的球）、蓄力时<em>空着的</em>副手（免得画面里凭空一只手）。
 * 副手若拿着别的东西（火把/盾牌）就照常渲染，互不影响。
 * 快捷栏、物品栏的图标渲染走的是别的路径，跟这个事件完全无关 —— 不会再误伤。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OrbChargeRenderHandler {

    /** 球离相机的距离（格）。<b>越负越远、球看起来越小</b>。 */
    private static final float DISTANCE = 0.75F;

    /** 球的目标大小（乘数）。想让球更大就调大这个数。 */
    private static final float SCALE = 1.0F;

    private OrbChargeRenderHandler() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        if (!AscensionOrbItem.isCharging(player)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        boolean chargingHand = event.getHand() == player.getUsedItemHand()
                && stack.getItem() instanceof AscensionOrbItem;
        boolean freeOffHand = event.getHand() != player.getUsedItemHand() && stack.isEmpty();

        if (chargingHand) {
            // 蓄力手：取消原版渲染，换成自己画的球
            event.setCanceled(true);
        } else if (freeOffHand) {
            // 蓄力时空着的副手：不画，画面里就只有悬在准星上的那颗球
            event.setCanceled(true);
            return;
        } else {
            // 副手拿着别的东西：照常渲染，互不影响
            return;
        }

        float t = AscensionOrbItem.getRaiseProgress(player, event.getPartialTick());
        float scale = SCALE * t;
        if (scale <= 0.0F) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // 相机空间：+x 右、+y 上、-z 前。x/y 归零 = 屏幕正中 = 准星。
        poseStack.translate(0.0F, 0.0F, -DISTANCE);
        // 第三阶段「登神长阶」：物品开始抖动 —— 两个不同频率的正弦相乘
        // （乘积形式让振幅时大时小，像不受控的震颤，而不是规则的来回摆）。
        float rage = AscensionOrbItem.getAscendRage(player, event.getPartialTick());
        if (rage > 0.0F) {
            double nt = player.tickCount + event.getPartialTick();
            double amp = 0.010D + 0.030D * rage;
            double jx = Math.sin(nt * 1.70D) * Math.sin(nt * 0.63D) * amp;
            double jy = Math.sin(nt * 2.10D + 1.3D) * Math.sin(nt * 0.87D) * amp;
            poseStack.translate(jx, jy, 0.0D);
        }
        poseStack.scale(scale, scale, scale);
        // GUI 上下文的物品是"正面对着你"的（物品栏图标就是它），不用再转任何角度。
        minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI,
                event.getPackedLight(), OverlayTexture.NO_OVERLAY,
                poseStack, event.getMultiBufferSource(), minecraft.level, 0);
        poseStack.popPose();
    }
}
