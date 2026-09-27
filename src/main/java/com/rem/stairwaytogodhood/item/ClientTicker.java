package com.rem.stairwaytogodhood.item;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 一个纯客户端的 tick 计数器，给"彩虹渐变名称"提供动画相位。
 *
 * <h2>为什么要单独搞一个东西来数 tick？</h2>
 * {@link net.minecraft.world.item.Item#getName} 的签名里没有 {@code Level}，
 * 而 {@code ItemStack} 也不公开"所属世界"字段，所以拿不到 {@code level.getGameTime()}。
 * <p>
 * 常见做法是塞一个 {@code DistExecutor.unsafeRunForDist(() -> Minecraft.getInstance()...)}，
 * 但这有两个毛病：
 * <ol>
 *     <li>物品类会因此引用客户端类，专用服务端加载时需要小心隔离，很脆弱；</li>
 *     <li>服务端拿不到 level 时相位恒为 0，颜色就"不动"了。</li>
 * </ol>
 * <p>
 * 这里改成：<b>客户端</b>在游戏 tick 时把计数写进一个普通的静态字段。
 * 服务端读到的永远是 0（不流动，但颜色正确），客户端读到的是实时值（顺畅流动）。
 * 因为字段本身是 {@code long}、类里没有任何客户端类型，两端都能安全加载。
 * <p>
 * 本类通过 {@code Dist.CLIENT} 只订阅客户端事件，专用服务端不会注册它。
 */
@Mod.EventBusSubscriber(
        modid = com.rem.stairwaytogodhood.StairwayToGodhood.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientTicker {

    /** 客户端已运行的 tick 数。服务端始终为 0。 */
    private static long ticks = 0L;

    private ClientTicker() {
    }

    /** 当前相位用的 tick 数。两端都能安全调用。 */
    public static long getTicks() {
        return ticks;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 只在每个 tick 的开头加一，避免 END 相位重复计数
        if (event.phase == TickEvent.Phase.START) {
            ticks++;
        }
    }
}
