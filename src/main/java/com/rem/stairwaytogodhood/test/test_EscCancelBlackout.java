package com.rem.stairwaytogodhood.test;

import com.rem.stairwaytogodhood.ChargeAnchor;
import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.client.ChargeFxState;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 【测试用 · 独立文件】按 <b>ESC</b> 取消黑屏（中断登神演出）。
 *
 * <p>⚠️ 本类是<b>完全独立</b>的测试代码，不参与任何其它逻辑的耦合：
 * <ul>
 *     <li>只订阅 {@link InputEvent.Key} 一个事件，靠 {@code @SubscribeEvent} 自动挂载；</li>
 *     <li>只用其它类的<b>公开 API</b>（不碰任何私有实现）；</li>
 *     <li>删除本文件即可彻底移除该功能，<b>不影响打包、不影响其它任何代码</b>。</li>
 * </ul>
 *
 * <h2>触发逻辑</h2>
 * 玩家在<b>登神演出</b>（第三阶段）里按下 ESC：
 * <ol>
 *     <li>立刻让玩家松手（{@code releaseUsingItem}），中断蓄力；</li>
 *     <li>清理阵法锚点（{@code ChargeAnchor.clear}）；</li>
 *     <li>清理"黑屏后冻结"状态（{@code ChargeFxState.clearFrozen}）。</li>
 * </ol>
 * 于是遮罩退场、法阵/罩子按正常收起流程回收，画面恢复正常。
 *
 * <p>注意：ESC 默认也会打开暂停菜单 —— 本类<b>不</b>拦截暂停菜单，
 * 只是顺带在按下时把登神演出清掉。若想"ESC 只取消黑屏、不弹菜单"，
 * 需要额外 {@code event.setCanceled(true)}，目前刻意不这么做。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class test_EscCancelBlackout {

    private test_EscCancelBlackout() {
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        // 只在 ESC 被<b>按下</b>（action == PRESS）时触发，不处理长按/松开
        if (event.getKey() != GLFW.GLFW_KEY_ESCAPE || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        // 只有正在"登神演出"（第三阶段）时才需要取消黑屏
        if (!AscensionOrbItem.isAscending(player)) {
            return;
        }

        // ① 中断蓄力（松手）
        player.releaseUsingItem();

        // ② 清理阵法锚点（服务端/逻辑层）
        ChargeAnchor.clear(player);

        // ③ 清理"黑屏后冻结保留"状态（客户端状态机）
        ChargeFxState.clearFrozen();
    }
}
