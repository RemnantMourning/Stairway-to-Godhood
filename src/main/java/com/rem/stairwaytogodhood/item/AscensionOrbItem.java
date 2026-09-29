package com.rem.stairwaytogodhood.item;

import com.rem.stairwaytogodhood.ChargeAnchor;
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

    /**
     * <b>第一阶段时长</b>：这段时间里玩家<b>可以自由行走和跳跃</b>。
     * <p>
     * 70 tick ≈ 3.5 秒，取在需求"3 到 4 秒"的中间。
     * 这一段是刻意的"留白"——先让玩家把宝珠举起来、还能边走边找位置，
     * 时间一到才在<b>脚下当时所在处</b>落阵，然后连人带阵一起定住。
     */
    public static final int WALK_TICKS = 70;

    /**
     * <b>锁链飞行时长</b>（tick）：法阵就位后，锁链从行星齿轮的中心
     * 沿柔性曲线飞向玩家所需的时间。必须与渲染端
     * {@code MagicCircleRenderer.CHAIN_FLY_TICKS} 保持一致。
     */
    public static final int CHAIN_FLY_TICKS = 12;

    /**
     * <b>锁链缠绕时长</b>（tick）：链尖抵达玩家后，沿身体螺旋缠绕一圈半所需的时间。
     * 必须与渲染端 {@code MagicCircleRenderer.CHAIN_WRAP_TICKS} 保持一致。
     */
    public static final int CHAIN_WRAP_TICKS = 10;

    /**
     * <b>锁死移动的时刻</b>：{@code WALK_TICKS + CHAIN_FLY_TICKS + CHAIN_WRAP_TICKS} = 92。
     * <p>
     * ⭐ 移动锁定的判定依据（见 {@link #isChained}）—— 不是"落阵就锁"，
     * 而是"<b>锁链在身上缠好之后</b>才锁"，视觉与逻辑严格对齐。
     */
    public static final int CHAIN_LOCK_TICKS = WALK_TICKS + CHAIN_FLY_TICKS + CHAIN_WRAP_TICKS;

    /** 能量罩从法阵边缘升起所需的时间（tick）。必须与渲染端 {@code DOME_RISE_TICKS} 一致。 */
    public static final int DOME_RISE_TICKS = 20;

    /** 能量罩开始升起的时刻 = 锁链缠好之后（演出顺序：落阵 → 锁链 → 能量罩）。 */
    public static final int DOME_START_TICKS = CHAIN_LOCK_TICKS;

    /** 能量罩完全升起的时刻。 */
    public static final int DOME_UP_TICKS = DOME_START_TICKS + DOME_RISE_TICKS;

    /**
     * <b>能量罩半径（格）</b>—— 渲染端 {@code MagicCircleRenderer} 用它决定罩体大小。
     */
    public static final double DOME_RADIUS = 6.5D;

    /**
     * <b>第三阶段「登神长阶」</b>开始时刻 = 能量罩升起完成之后。
     * <p>
     * 从这个时刻起宝珠进入狂暴状态：物品抖动、引力波极快（切到 surge 贴图，
     * {@code frametime} 从 4 降到 1）、裂隙更大、闪电更突出。
     */
    public static final int ASCEND_TICKS = DOME_UP_TICKS;

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
     * 蓄力期间每 tick 调用一次，<b>客户端与服务端都会调</b>。
     *
     * <h2>两个阶段的分界就在这里</h2>
     * <ul>
     *     <li>{@code elapsed < }{@link #WALK_TICKS}：<b>第一阶段</b>，什么都不做 ——
     *         玩家可以随便走随便跳（移动锁定见 {@code MixinChargeMovementLock}，
     *         它的判定条件是 {@link #isAnchored} 而不是 {@link #isCharging}，
     *         所以这个阶段完全不受限制）。</li>
     *     <li>跨过 {@link #WALK_TICKS}：<b>第二阶段</b>。在这里把锚点钉在玩家<b>此刻的脚下</b>,
     *         后续的齿轮魔法阵与锁链都由客户端读这个锚点来渲染
     *         （见 {@code client.MagicCircleRenderer}）。</li>
     * </ul>
     *
     * <p>锚点用 {@code beginIfAbsent} ⇒ 只记第一次，之后玩家再动阵法也不会跟着跑 ——
     * 否则"被锁链拴住"就只是装饰了。
     */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (getChargeTicks(entity) < WALK_TICKS) {
            // 第一阶段：还没到落阵的时候。顺手保证状态干净
            // （正常流程里锚点本就为空，这里只是防御"上一轮没收尾"）。
            ChargeAnchor.clear(entity);
            return;
        }

        // 第二阶段：阵法出现的这一刻，把锚点定在脚下 —— 且只记这一次。
        // （返回值不再使用：法阵完全由客户端渲染，服务端只需把锚点钉住。）
        ChargeAnchor.beginIfAbsent(entity);
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
        // 收阵：清掉锚点，下次再蓄力"落阵"时会重新钉在脚下。
        ChargeAnchor.clear(entity);

        // TODO 释放效果（冲击波 / 位移 / 召唤 / 消耗……）接在这里。
        //      蓄力时长就是 USE_DURATION - timeLeft，可以据此分档；
        //      是否走到了第二阶段可以用 timeLeft <= USE_DURATION - WALK_TICKS 判断。
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

    /**
     * <b>是否已经进入第二阶段</b>（脚下落阵、被锁链拴住、不能再移动跳跃）。
     * <p>
     * 这是比 {@link #isCharging} 更严格的条件，也是移动锁定的判定依据：
     * 蓄力前 {@link #WALK_TICKS} tick 里玩家可以自由走动（"第一段蓄力"），
     * 之后才被定住。
     * <p>
     * 注意这个判断只依赖原版同步的"正在使用物品"状态与已用时长，
     * 因此<b>客户端看到的其他玩家</b>也能得到一致结果（他们的锁链表现是服务端粒子，天然可见）。
     */
    public static boolean isAnchored(@Nullable LivingEntity entity) {
        return getChargeTicks(entity) >= WALK_TICKS;
    }

    /**
     * <b>锁链是否已经缠在身上</b>（蓄力超过 {@link #CHAIN_LOCK_TICKS}）。
     * <p>
     * ⭐ 这是移动锁定的<b>唯一判定</b>：落阵只代表"演出开始"，
     * 锁链飞过来（{@link #CHAIN_FLY_TICKS}）并在身上缠好（{@link #CHAIN_WRAP_TICKS}）
     * 之后，玩家才真正被锁住 —— 视觉进度和逻辑判定严格同步，
     * 不会出现"阵法刚落地人就被定住、锁链还在半空飞"的违和感。
     */
    public static boolean isChained(@Nullable LivingEntity entity) {
        return getChargeTicks(entity) >= CHAIN_LOCK_TICKS;
    }

    /**
     * <b>能量罩是否已经完全升起</b>（蓄力超过 {@link #DOME_UP_TICKS}）。
     * <p>
     * 与渲染上的升起动画严格同步；也是"第三人称自动切回第一人称"的触发时机
     * （见 {@code MagicCircleRenderer}）。
     */
    public static boolean isDomeUp(@Nullable LivingEntity entity) {
        return getChargeTicks(entity) >= DOME_UP_TICKS;
    }

    /**
     * <b>是否已进入第三阶段「登神长阶」</b>（蓄力超过 {@link #ASCEND_TICKS}）。
     * <p>
     * 供渲染层使用：宝珠切到 surge 贴图（引力波极快、裂隙更大、闪电更突出）、
     * 物品本身开始抖动。也是 {@code stairway_to_godhood:surge} 这个模型谓词的取值来源。
     */
    public static boolean isAscending(@Nullable LivingEntity entity) {
        return getChargeTicks(entity) >= ASCEND_TICKS;
    }

    /** 登神阶段内的"狂暴进度" 0~1（用于抖动幅度随充能继续增强）。 */
    public static float getAscendRage(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick - ASCEND_TICKS;
        return Mth.clamp(elapsed / 60.0F, 0.0F, 1.0F);   // 之后 3 秒内线性涨到 1
    }

    /** 「登神聚焦」淡入时长（tick）—— 进入第三阶段后，屏幕遮罩与视角扩大在这个时间里推到满。 */
    public static final int ASCEND_FOCUS_TICKS = 22;

    /**
     * <b>登神聚焦进度</b> 0~1：驱动"屏幕界面消失、视角扩大、锥形紫黑遮罩收拢"三件事。
     * <p>
     * 比 {@link #getAscendRage} 快（30 tick = 1.5 秒推满）—— 特写要"一下子上来"才有冲击力，
     * 而抖动是持续渐强的。
     */
    public static float getAscendFocus(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick - ASCEND_TICKS;
        return Mth.clamp(elapsed / ASCEND_FOCUS_TICKS, 0.0F, 1.0F);
    }

    // ---------------------------------------------------------------- 登神遮罩演化
    //
    // 三个进度首尾相接，把屏幕一路"吃"掉：
    //   focus  30t  锥形遮罩收拢（边缘先暗，中间还亮）
    //   purple 60t  紫色逐渐铺满（连中心一起变紫）
    //   black  60t  黑色从中心向外蔓延，最终全黑
    //
    /** 紫色铺满的起始（tick，相对蓄力开始）—— 锥形遮罩收拢完成之后。 */
    public static final int ASCEND_PURPLE_START = ASCEND_TICKS + ASCEND_FOCUS_TICKS;
    /** 紫色铺满所需时间（tick）。 */
    public static final int ASCEND_PURPLE_TICKS = 48;
    /** 黑幕扩散的起始（tick）—— 紫色铺满之后。 */
    public static final int ASCEND_BLACK_START = ASCEND_PURPLE_START + ASCEND_PURPLE_TICKS;
    /**
     * 黑幕扩散所需时间（tick）。
     * <p>
     * 160 tick = 8 秒 —— 用户反馈 100 tick 时"变黑太快"。
     * 黑幕是最"重"的一层，进一步拉长后是"慢慢暗下去、慢慢沉入纯黑"，
     * 中间那段深紫黑（能量残留）才有足够时间被看见。
     */
    public static final int ASCEND_BLACK_TICKS = 160;

    /** 紫色铺满进度 0~1。 */
    public static float getAscendPurple(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick - ASCEND_PURPLE_START;
        return Mth.clamp(elapsed / ASCEND_PURPLE_TICKS, 0.0F, 1.0F);
    }

    /**
     * <b>黑幕完全落定</b>的时刻（tick，相对蓄力开始）—— 屏幕已彻底变黑。
     * <p>
     * 这是"登神演出收尾"的分界：过了这一刻，蓄力目标已经达成，
     * 松手时不再收起法阵/罩子，而是把它们**冻结在原地**（见 {@code ChargeFxState} 的冻结态）。
     */
    public static final int ASCEND_BLACK_DONE_TICKS = ASCEND_BLACK_START + ASCEND_BLACK_TICKS;

    /**
     * 是否已经黑屏完成（蓄力超过 {@link #ASCEND_BLACK_DONE_TICKS}）。
     * 供"松手后是否保留法阵"的判定使用。
     */
    public static boolean isBlackDone(@Nullable LivingEntity entity) {
        return getChargeTicks(entity) >= ASCEND_BLACK_DONE_TICKS;
    }

    /** 黑幕从中心扩散的进度 0~1（1 = 全屏已黑）。 */
    public static float getAscendBlack(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick - ASCEND_BLACK_START;
        return Mth.clamp(elapsed / ASCEND_BLACK_TICKS, 0.0F, 1.0F);
    }

    /**
     * 能量罩的升起进度：<b>0</b> = 刚要从地面冒出来，<b>1</b> = 完全升起。
     * 渲染端用它驱动罩体的高度与透明度。
     */
    public static float getDomeRise(@Nullable LivingEntity entity, float partialTick) {
        if (!isCharging(entity)) {
            return 0.0F;
        }
        float elapsed = getChargeTicks(entity) + partialTick - DOME_START_TICKS;
        return Mth.clamp(elapsed / DOME_RISE_TICKS, 0.0F, 1.0F);
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
