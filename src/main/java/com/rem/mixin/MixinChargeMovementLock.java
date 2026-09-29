package com.rem.mixin;

import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>锁链把玩家缠好之后</b>把玩家钉在原地 —— 不能走动、不能跳。
 *
 * <h2>锁定时机与锁链演出严格同步</h2>
 * 判定条件是 {@link AscensionOrbItem#isChained}（蓄力已超过
 * {@link AscensionOrbItem#CHAIN_LOCK_TICKS} = 落阵 + 锁链飞行 + 缠绕）：
 * <ul>
 *     <li><b>前 3.5 秒</b>：边走边蓄力，本 mixin 完全不介入；</li>
 *     <li><b>落阵 → 锁链飞来</b>（12 tick）：仍然能被惯性推着滑动（但法阵已钉死脚下）；</li>
 *     <li><b>缠绕完成</b>（再 10 tick）：从这里开始锁死 —— 视觉上"链子缠好了"，逻辑上才锁。</li>
 * </ul>
 *
 * <h2>为什么必须用 mixin，而不是事件</h2>
 * 试过用 {@code LivingEvent.LivingTickEvent} / {@code TickEvent.PlayerTickEvent} 清零
 * {@code player.xxa / zza / jumping}，<b>全部无效</b>。查字节码找到了原因：
 *
 * <pre>
 * LocalPlayer.tick()
 *   └─ super.tick() → LivingEntity.tick()
 *        ├─ ForgeHooks.onLivingTick      ← 所有事件钩子都在这附近，太早了
 *        └─ aiStep()
 *             ├─ LocalPlayer.aiStep()    ← 这里才调用 input.tick()，从按键重新读输入
 *             └─ serverAiStep()          ← ★ xxa = input.leftImpulse; zza = ...; jumping = ...
 *             └─ travel(xxa, yya, zza)   ← 真正产生位移
 * </pre>
 *
 * 也就是说玩家的移动意图是在 {@code serverAiStep()} 里才写进 {@code xxa/zza} 的，
 * 早于它的任何清零都会被覆盖。所以直接拦"真正产生位移"的那两个方法，最干脆、也不依赖时序。
 *
 * <h2>拦哪两处</h2>
 * <ul>
 *     <li>{@code travel(Vec3)} —— 水平和垂直<b>位移</b>的唯一出口。
 *         {@code Player} 自己重写了这个方法（不是沿用 {@code LivingEntity} 的），
 *         所以 mixin 的目标必须是 {@code Player}。</li>
 *     <li>{@code jumpFromGround()} —— 玩家跳跃的唯一入口。同样被 {@code Player} 重写。</li>
 * </ul>
 *
 * <h2>刻意留着不管的东西</h2>
 * <ul>
 *     <li><b>重力照常</b>：只清水平分量、保留 {@code y} 速度。这样"跳起来之后才落阵"
 *         仍会正常落地，而不是诡异地悬停在空中。</li>
 *     <li><b>视角照常能转</b>：转视角不经过 {@code travel}，所以被拴住时仍可环顾四周。</li>
 * </ul>
 *
 * <p>端通用：不含任何客户端类型，专用服务端也能安全加载（锁定因此是服务端权威的）。
 */
@Mixin(Player.class)
public abstract class MixinChargeMovementLock {

    /**
     * 清掉水平位移。
     * <p>
     * 把传给 {@code travel} 的输入向量清零 ⇒ 不再产生水平加速度；
     * 再把已有的水平速度清零 ⇒ 连惯性滑行也一并去掉
     * （否则一边奔跑一边蓄力，落阵那一瞬间会往前溜一段，正好是最容易被察觉的破绽）。
     */
    @ModifyVariable(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
    private Vec3 stairway$zeroTravelInput(Vec3 travelVector) {
        Player self = (Player) (Object) this;
        if (!AscensionOrbItem.isChained(self)) {
            return travelVector;
        }
        // 只清水平两轴；y 留给重力与垂直移动，免得在空中被"定住"
        return new Vec3(0.0D, travelVector.y, 0.0D);
    }

    /** 同样的地方顺手把惯性清掉（每 tick 一次，所以不会被累积回去）。 */
    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void stairway$killHorizontalMomentum(Vec3 travelVector, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!AscensionOrbItem.isChained(self)) {
            return;
        }
        Vec3 v = self.getDeltaMovement();
        if (v.x != 0.0D || v.z != 0.0D) {
            self.setDeltaMovement(0.0D, v.y, 0.0D);
        }
    }

    /**
     * 禁止跳跃。
     * <p>
     * 直接取消 {@code jumpFromGround()} —— 它是玩家起跳的唯一入口，
     * 在这里拦最干净（{@code jumping} 字段保持为 true 也无所谓，没有别的代码读它来起跳）。
     */
    @Inject(method = "jumpFromGround()V", at = @At("HEAD"), cancellable = true)
    private void stairway$lockJump(CallbackInfo ci) {
        if (AscensionOrbItem.isChained((Player) (Object) this)) {
            ci.cancel();
        }
    }
}
