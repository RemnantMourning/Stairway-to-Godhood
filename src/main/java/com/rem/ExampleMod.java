package com.rem;

import com.rem.stairwaytogodhood.registry.ModCreativeTabs;
import com.rem.stairwaytogodhood.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ExampleMod.MOD_ID)
public class ExampleMod {

    /** 注册命名空间。必须与 gradle.properties 里的 mod_id 保持一致。 */
    public static final String MOD_ID = "stairway_to_godhood";

    public static final Logger LOGGER = LogManager.getLogger();

    public ExampleMod() {

        // This is our mod's event bus, used for things like registry or lifecycle events
        IEventBus MOD_BUS = BusBuilder.builder().build();

        // This listener is fired on both client and server during setup.
        MOD_BUS.addListener(this::commonSetup);
        // This listener is only fired during client setup, so we can use client-side methods here.
        MOD_BUS.addListener(this::clientSetup);

        // Most other events are fired on Forge's bus.
        // If we want to use annotations to register event listeners,
        // we need to register our object like this!
        MinecraftForge.EVENT_BUS.register(this);

        // For more information on how to deal with events in Forge,
        // like automatically subscribing an entire class to an event bus
        // or using static methods to listen to events,
        // feel free to check out the Forge wiki!

        // ---- Stairway to Godhood 内容注册 ----
        // 物品与创造物品栏必须挂到 FML 提供的 mod event bus 上才会生效。
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modBus);         // 「登神长阶」宝珠
        ModCreativeTabs.register(modBus);  // 创造模式物品栏

        // 配置文件：config/stairway_to_godhood-client.toml（占位界面的语句与参数）
        com.rem.stairwaytogodhood.client.NoContentConfig.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Hello from common setup! This is *after* registries are done, so we can do this:");
        LOGGER.info("Look, I found a {}!", Items.DIAMOND);
        if (Items.ACACIA_BOAT instanceof BoatItem b) {
            b.getDefaultInstance().exampleMod$doNothing();
        }
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Hey, we're on Minecraft version {}!", Minecraft.getInstance().getLaunchedVersion());
    }

    @SubscribeEvent
    public void kaboom(ExplosionEvent.Detonate event) {
        LOGGER.info("Kaboom! Something just blew up in {}!", event.getLevel());
    }
}
