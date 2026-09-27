package com.rem.stairwaytogodhood.registry;

import com.rem.stairwaytogodhood.StairwayToGodhood;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏。
 * <p>
 * 本模组目前只有一个物品，单独占一个物品栏有点小题大做，但保留它的好处是
 * 后续往里加东西时不用再动注册结构。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StairwayToGodhood.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.ASCENSION_ORB.get()))
                    .title(Component.translatable("itemGroup.stairway_to_godhood.main"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.ASCENSION_ORB.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modBus) {
        CREATIVE_MODE_TABS.register(modBus);
    }
}
