package com.rem.stairwaytogodhood.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.List;

/**
 * 「登神长阶」。
 *
 * <h2>它现在能做什么</h2>
 * <ul>
 *     <li><b>长按右键蓄力</b>：按住右键把宝珠举到身前，松开结束。
 *         抬起动作本身只有 {@link #RAISE_TICKS} tick，之后就一直是"举着"的状态，
 *         蓄力时长由 {@link #getChargeTicks} 给出、以 {@link #FULL_CHARGE_TICKS} 为满。
 *         释放之后的实际效果还没接（见 {@link #releaseUsing}）。</li>
 *     <li><b>外观</b>：{@code textures/item/ascension_orb.png} 是一张 <b>64×2048</b> 的竖向动画贴图
 *         （64×64 一帧，共 32 帧），画面刻意做得极简：纯黑球体、
 *         球壳上固定位置的<b>裂缝</b>、每帧从随机 2~3 条裂缝<b>向外溢出</b>的闪电，
 *         以及一圈圈向外推的<b>引力波</b>（压缩相 + 稀疏相成对）。</li>
 *     <li><b>名称</b>：用自己的 {@link #getName} 输出<b>逐字循环变色的彩虹渐变</b>。</li>
 *     <li><b>品阶</b>：{@link Rarity#EPIC}（金色名称 + 附魔光效，和它的"登神"气质相称）。</li>
 * </ul>
 *
 * <h2>关于"彩虹渐变名称"的实现</h2>
 * Minecraft 的名称是靠文本组件里的 {@link Style#getColor() 颜色}生效的，而颜色是一个
 * <b>静态值</b>——组件一旦构造出来就定死了，不可能自己随时间变色。因此这里不返回固定的
 * 名称，而是在 <b>每次渲染到 GUI 时重新构造</b>一个组件：每个字都按当前时刻算一个色相。
 * 物品栏/快捷栏每帧都会重新向物品索要名称，于是"彩虹色带沿着文字来回流动"就动起来了。
 * <p>
 * 相位来自 {@link ClientTicker}，它是一个纯客户端的 tick 计数器。
 * 这样做的好处是：<b>本类完全不引用任何客户端类</b>，专用服务端可以安全加载，
 * 不需要 {@code DistExecutor} 之类的隔离手段。
 * <p>
 * 颜色用 {@link TextColor#fromRgb(int)} 构造（它就是 {@code style color} 字段要走的那条路），
 * 并且各个字的 {@code font} 都保持默认，不会冒出缺失字形的豆腐块。
 *
 * <h2>关于"蓄力"为什么放在物品类里</h2>
 * 右键的使用流程（{@code use} → 每 tick {@code onUseTick} → 松手 {@code releaseUsing}）
 * 是 {@code Item} 自己的钩子，客户端和服务端都会走同一套代码，状态天然同步
 * （"是否在使用物品"由 {@code LivingEntity} 的网络数据位同步）。
 * <p>
 * 所以这里只维护<b>逻辑状态</b>，把"举起来"的视觉表现留给客户端
 * （第一人称 / 第三人称各一个 mixin）。本类里出现的静态查询方法
 * （{@link #isCharging} / {@link #getChargeTicks} / {@link #getRaiseProgress} /
 * {@link #getChargeProgress}）就是给那些渲染代码用的，它们都不引用任何客户端类型。
 */
public class AscensionOrbItem extends Item {

    /** 一个完整循环的时长（tick）。60 tick = 3 秒，流动感明显又不晃眼。 */
    private static final int CYCLE_TICKS = 60;

    /** 色相在文字上铺开的"波长"（以字符为单位）：值越大，单个字内的色差越小。 */
    private static final double HUE_WAVELENGTH_CHARS = 3.0D;

    // ================================================================ 蓄力参数

    /**
     * 从"按下右键"到"把宝珠举到身前"的动作时长（tick）。
     * <p>
     * 刻意和蓄力总时长分开：抬臂是个短促的一次性动作，而蓄力可以一直持续下去，
     * 两者用一个进度的话手臂会"抬得没完没了"。8 tick ≈ 0.4 秒，起手不拖沓。
     */
    public static final int RAISE_TICKS = 8;

    /**
     * 蓄满所需时长（tick）。60 tick = 3 秒。
     * <p>
     * 目前只用于计算 {@link #getChargeProgress}，等释放效果接上后就是它的强度依据。
     */
    public static final int FULL_CHARGE_TICKS = 60;

    /**
     * 使用状态的上限。
     * <p>
     * 给一个很大的值 = "只要不松手就一直举着"，不会像食物那样吃到 32 tick 就自己结束。
     * 实际蓄力时长是 {@code USE_DURATION - 剩余tick}，超过 {@link #FULL_CHARGE_TICKS} 后
     * 只是"保持满蓄力"，不再增长有效进度。
     */
    private static final int USE_DURATION = 72000;

    public AscensionOrbItem(Properties properties) {
        super(properties);
    }

    // ================================================================ 蓄力：使用流程

    /**
     * 右键按下 —— 进入蓄力。
     * <p>
     * {@code InteractionResultHolder.consume(...)} 表示"这次交互我已经接手了"，
     * 于是客户端会进入"持续使用"状态，每个 tick 调 {@link #onUseTick}，
     * 松手时调 {@link #releaseUsing}。返回 {@code success} 或 {@code pass} 都不会这样。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    /**
     * "只要不松手就一直举着"。默认的 {@code 72000} 已经是这个语义，这里显式写出来是为了
     * 让 {@link #getChargeTicks} 的换算（{@code USE_DURATION - 剩余tick}）有明确依据。
     */
    @Override
    public int getUseDuration(ItemStack stack) {
        return USE_DURATION;
    }

    /**
     * {@link UseAnim#NONE} = 让原版<b>不要</b>给第一人称套任何特化动作
     * （不像 {@code BOW} 会把物品拉向怀里、{@code SPEAR} 会往前捅）。
     * <p>
     * 举起宝珠的全套变换由客户端 mixin 自己算，原版别插手。
     * 顺带一提，正因为不是 BOW/CROSSBOW，原版会同时渲染两只手 —— 正好是"双手捧"需要的。
     */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /**
     * 蓄力期间每 tick 调用一次，客户端与服务端都会调。
     * <p>
     * 后续的蓄力音效、粒子、阶段提示（"蓄力 1/3"之类）都应该接在这里；
     * 真正会改变世界的结果请留给 {@link #releaseUsing}，避免中途反复触发。
     */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        // 目前"蓄力中"的全部表现都由姿态动画承担，这里先留空。
    }

    /**
     * 松开右键。
     *
     * @param timeLeft 还剩多少 tick 没用完，所以这次蓄力实际持续了
     *                 {@code USE_DURATION - timeLeft} tick。
     *                 需要"这次蓄了多少"时优先用这个值 —— 此刻
     *                 {@link #getChargeTicks} 已经失效了（{@code isUsingItem()} 刚被置回 false）。
     */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        // TODO 释放效果（冲击波 / 位移 / 召唤 / 消耗……）接在这里。
        //      蓄力时长就是 USE_DURATION - timeLeft，可以据此分档。
    }

    // ================================================================ 蓄力：状态查询

    /**
     * 这个实体此刻是否正在为「登神长阶」蓄力。
     * <p>
     * 判断依据是"正在使用物品"这个原版状态位，它是<b>同步的</b>
     * （存在 {@code LivingEntity} 的网络数据里），所以客户端看到的其它玩家
     * 也会正确地摆出举宝珠的姿势。
     */
    public static boolean isCharging(@Nullable LivingEntity entity) {
        return entity != null
                && entity.isUsingItem()
                && entity.getUseItem().getItem() instanceof AscensionOrbItem;
    }

    /** 这次蓄力已经持续了多少 tick。没在蓄力时为 0。 */
    public static int getChargeTicks(@Nullable LivingEntity entity) {
        if (!isCharging(entity)) {
            return 0;
        }
        return Mth.clamp(USE_DURATION - entity.getUseItemRemainingTicks(), 0, USE_DURATION);
    }

    /**
     * 抬起进度：<b>0</b> = 手臂自然垂着，<b>1</b> = 已经完全举到身前。
     * <p>
     * 用于姿态动画，只走前 {@link #RAISE_TICKS} tick，之后恒为 1。
     * 收尾用 ease-out，起手干脆、定格稳当。
     *
     * @param partialTick 渲染插值用的帧内小数（0~1），让举起的动作不跟着 tick 跳
     */
    public static float getRaiseProgress(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick;
        float p = Mth.clamp(elapsed / RAISE_TICKS, 0.0F, 1.0F);
        return 1.0F - (1.0F - p) * (1.0F - p);
    }

    /**
     * 蓄力进度：<b>0</b> = 刚按下，<b>1</b> = 已蓄满（{@link #FULL_CHARGE_TICKS} tick）。
     * <p>
     * 与 {@link #getRaiseProgress} 的区别：这个是"能量攒了多少"，那个是"手抬到位没有"。
     * 目前渲染没用它，等释放效果接上后就靠它分档。
     */
    public static float getChargeProgress(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick;
        return Mth.clamp(elapsed / FULL_CHARGE_TICKS, 0.0F, 1.0F);
    }

    // ================================================================ 外观

    /**
     * 动态彩虹名称。
     * <p>
     * 文字内容取自 {@code Component.translatable(...)}，所以会跟随玩家语言环境
     * （中文显示"登神长阶"，英文显示"Ascension Stairway"）。
     * 拿到翻译文本后，<b>逐字</b>拆开、各自染一个随时间流动的色相。
     */
    @Override
    public Component getName(ItemStack stack) {
        String text = Component.translatable(getDescriptionId(stack)).getString();
        return rainbowComponent(text, ClientTicker.getTicks());
    }

    /**
     * 把一段文字逐字染成随时间流动的彩虹色。
     *
     * @param text 要染色的文字
     * @param tick 相位来源（通常是一个随时间递增的 tick 计数）
     */
    public static Component rainbowComponent(String text, long tick) {
        MutableComponent root = Component.empty();
        double base = (tick % CYCLE_TICKS) / (double) CYCLE_TICKS;
        for (int i = 0; i < text.length(); i++) {
            double hue = (base + i / HUE_WAVELENGTH_CHARS) % 1.0D;
            int rgb = hsvToRgb(hue, 0.90D, 1.0D);
            root.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        }
        return root;
    }

    /** HSV -> RGB（0xRRGGBB），避免为此引入额外的颜色工具类。 */
    private static int hsvToRgb(double h, double s, double v) {
        Color c = Color.getHSBColor((float) h, (float) s, (float) v);
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    /**
     * 彩虹球：自带附魔光效，在物品栏里就能看出它不是凡物。
     * <p>
     * （品阶已经是 {@link Rarity#EPIC}，这里显式返回 true 是为了让效果不受品阶配置影响。）
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /**
     * 物品自身的说明。
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.stairway_to_godhood.ascension_orb.tooltip")
                .withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("item.stairway_to_godhood.ascension_orb.tooltip.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
