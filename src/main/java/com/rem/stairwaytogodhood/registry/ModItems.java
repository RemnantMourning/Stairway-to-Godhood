package com.rem.stairwaytogodhood.registry;

import com.rem.stairwaytogodhood.StairwayToGodhood;
import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册。
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, StairwayToGodhood.MOD_ID);

    /**
     * 登神长阶：一枚原型宝珠，目前没有任何实际作用。
     * <p>
     * 用 {@link Rarity#EPIC} 是为了让名称渲染成金色并带上附魔光效——
     * 与"登神"的定位相称，也方便在物品栏里一眼找到它。
     * <p>
     * {@code stacksTo(1)}：它是唯一的、不该被批量堆叠的东西。
     */
    public static final RegistryObject<Item> ASCENSION_ORB = ITEMS.register("ascension_orb",
            () -> new AscensionOrbItem(new Item.Properties()
                    .rarity(Rarity.EPIC)
                    .stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
