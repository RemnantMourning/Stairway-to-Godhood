package com.rem.mixin;

import com.rem.stairwaytogodhood.item.AscensionOrbItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 蓄力时把玩家的<b>双臂抬到身前</b>，看起来像双手把宝珠捧在面前。
 * <p>
 * 作用范围是第三人称：按 F5 看自己、以及别的玩家看你。
 * （第一人称另有 {@link MixinAscensionOrbHandRender} 处理。）
 *
 * <h2>为什么不用 Forge 的 {@code IClientItemExtensions#getArmPose}</h2>
 * {@code HumanoidModel.ArmPose} 确实实现了 {@code IExtensibleEnum}，理论上能
 * {@code ArmPose.create(...)} 出新的姿态常量。但原版 {@code poseRightArm()} 是
 * <b>编译期的 {@code switch}</b>，编译后会生成一张按 {@code ordinal} 索引的
 * {@code $SwitchMap} 数组——那是另一个类里长度固定的静态字段。
 * 新增的枚举值序号落在数组之外，走进去就是 {@code ArrayIndexOutOfBoundsException}。
 * <p>
 * 直接用 mixin 改角度则完全没有这个隐患，而且数值由自己说了算。
 *
 * <h2>为什么 {@code @At("TAIL")}</h2>
 * {@code setupAnim} 里的手臂姿态（站姿、潜行、骑乘、以及各种 {@code ArmPose}）
 * 全部算完之后，我们在<b>最后</b>动手，就不会被原版逻辑覆盖掉。
 * {@code PlayerModel.setupAnim} 调完 {@code super.setupAnim} 之后只做袖子/裤腿的
 * {@code copyFrom}，所以袖子会跟着改后的手臂一起动，不会有"手臂动了袖子没动"的破绽。
 */
@Mixin(HumanoidModel.class)
public abstract class MixinAscensionOrbArmPose {

    /**
     * 双臂前伸时，左右手各自向身体中线收拢的幅度（弧度）。
     * <p>
     * <b>越大两手越靠拢</b>，宝珠（在主手上）也就越靠近身体中线。
     * 0.40 时宝珠还偏在中线左侧一点（实机截图里约偏 20px），调到 0.52 收进来一档。
     */
    private static final float ARM_INWARD = 0.52F;

    /** 前伸角度基准（弧度）。-90° 是"水平端平"，这里放松到 -83°，更像捧着而不是端着。 */
    private static final float ARM_FORWARD = -1.45F;

    /** 手臂跟着头部俯仰的比例。低头时手自然往下走，不然会像僵在原处。 */
    private static final float HEAD_FOLLOW = 0.45F;

    @Inject(
            // 描述符里是"一个实体 + 5 个 float"：setupAnim(T entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)。
            // 注意别写成 6 个 F —— 泛型 T 擦除后是 LivingEntity，加上后面 5 个 float 一共 6 个参数。
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL"))
    private void stairway$ascensionOrbRaise(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                            float ageInTicks, float netHeadYaw, float headPitch,
                                            CallbackInfo ci) {
        // partialTick 传 0：手臂角度是每 tick 重算的，而 tick 之间的跳变本来就很小，
        // 这里再插值反而会让手臂比身体晚半拍。
        float t = AscensionOrbItem.getRaiseProgress(entity, 0.0F);
        if (t <= 0.0F) {
            return;
        }

        // HumanoidModel 是泛型类，mixin 里拿不到类型变量，转成通配再访问公开字段最直接。
        // （head / rightArm / leftArm 都是原版的 public final ModelPart。）
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;

        float targetX = ARM_FORWARD + model.head.xRot * HEAD_FOLLOW;

        // 用 lerp 从"原版算出来的当前角度"过渡到目标角度：
        // t=0 时完全保持原样，t=1 时完全举起，中间是连续的 —— 不用自己写过渡曲线。
        model.rightArm.xRot = Mth.lerp(t, model.rightArm.xRot, targetX);
        model.leftArm.xRot = Mth.lerp(t, model.leftArm.xRot, targetX);
        model.rightArm.yRot = Mth.lerp(t, model.rightArm.yRot, -ARM_INWARD);
        model.leftArm.yRot = Mth.lerp(t, model.leftArm.yRot, ARM_INWARD);
        model.rightArm.zRot = Mth.lerp(t, model.rightArm.zRot, 0.0F);
        model.leftArm.zRot = Mth.lerp(t, model.leftArm.zRot, 0.0F);
    }
}
