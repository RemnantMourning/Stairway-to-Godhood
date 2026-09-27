package com.rem.stairwaytogodhood;

import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stairway to Godhood —— 「登神长阶」。
 * <p>
 * 本类保存本模组的常量（命名空间、显示名、日志器），并提供 {@link #id(String)} 工具方法。
 *
 * <h2>关于入口</h2>
 * 本模组的 {@code @Mod} 入口类是 {@link com.rem.ExampleMod}——物品与创造物品栏的注册
 * 由它在构造时调度（{@link com.rem.stairwaytogodhood.registry.ModItems#register} /
 * {@link com.rem.stairwaytogodhood.registry.ModCreativeTabs#register}）。
 * 因此本类不再带 {@code @Mod} 注解，也不参与实例化。
 *
 * <h2>目录结构</h2>
 * <pre>
 * src/main/java/com/rem/stairwaytogodhood/
 *     StairwayToGodhood.java        <- 本文件，常量与命名空间工具
 *     item/AscensionOrbItem.java    <- 物品本体（动态彩虹名称 + 提示文本）
 *     item/ClientTicker.java        <- 纯客户端 tick 计数器（给名称提供动画相位）
 *     registry/ModItems.java        <- 物品注册
 *     registry/ModCreativeTabs.java <- 创造模式物品栏注册
 *
 * src/main/resources/
 *     META-INF/mods.toml            <- 模组元数据
 *     pack.mcmeta                   <- 资源包描述
 *     assets/stairway_to_godhood/
 *         lang/zh_cn.json           <- 中文名与提示文本
 *         lang/en_us.json           <- 英文名与提示文本
 *         models/item/ascension_orb.json
 *         textures/item/ascension_orb.png (+ .png.mcmeta)  <- 16 帧动画贴图
 * </pre>
 */
public final class StairwayToGodhood {

    /** 注册命名空间。必须与 gradle.properties 里的 {@code mod_id} 一致。 */
    public static final String MOD_ID = "stairway_to_godhood";

    public static final String NAME = "Stairway to Godhood";
    public static final Logger LOGGER = LogManager.getLogger();

    private StairwayToGodhood() {
    }

    /** 生成本模组命名空间下的 {@link ResourceLocation}。 */
    public static ResourceLocation id(String path) {
        // 1.20.1 用构造函数；ResourceLocation.fromNamespaceAndPath 是 1.20.5+ 才有的 API
        return new ResourceLocation(MOD_ID, path);
    }
}
