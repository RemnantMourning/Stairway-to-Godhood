package com.rem.stairwaytogodhood.client;

import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import com.rem.stairwaytogodhood.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端的物品模型谓词注册。
 *
 * <h2>为什么需要它：第三阶段要"换一张贴图"</h2>
 * 「登神长阶」第三阶段宝珠的引力波要变得极快 —— 而 <b>动画播放速度写在
 * {@code .png.mcmeta} 的 {@code frametime} 里，是静态资源，代码改不了</b>。
 * 所以做法是准备两套贴图（{@code ascension_orb.png} 正常 /
 * {@code ascension_orb_surge.png} 狂暴），用原版的**模型谓词（overrides）**切换：
 *
 * <pre>
 * models/item/ascension_orb.json
 *   "overrides": [ { "predicate": { "stairway_to_godhood:surge": 0.5 },
 *                    "model": "stairway_to_godhood:item/ascension_orb_surge" } ]
 * </pre>
 *
 * 本类注册的就是那个 {@code stairway_to_godhood:surge} 谓词 —— 取值
 * "是否已进入第三阶段"，≥ 0.5 时原版模型系统自动换成狂暴贴图。
 * 物品栏图标、第一人称手持、第三人称手持**全都会跟着切换**（因为都走模型解析）。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AscensionOrbClientSetup {

    /** 模型谓词名 —— 必须与 {@code models/item/ascension_orb.json} 里的 key 一致。 */
    public static final ResourceLocation SURGE =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "surge");

    private AscensionOrbClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // ⚠️ 必须走 enqueueWork：FMLClientSetupEvent 会在并行线程上触发，
        //    而 ItemProperties 的注册表不是线程安全的。
        event.enqueueWork(() -> ItemProperties.register(
                ModItems.ASCENSION_ORB.get(),
                SURGE,
                (stack, level, entity, seed) -> AscensionOrbItem.isAscending(entity) ? 1.0F : 0.0F));
    }
}
