package com.rem.stairwaytogodhood;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蓄力第二阶段的<b>锚点</b>：魔法阵钉在世界里的哪个位置。
 *
 * <h2>为什么要记这个</h2>
 * 需求是"先在脚下展开魔法阵，再伸锁链把玩家拴住"，所以阵法一旦出现就必须<b>钉死</b>，
 * 不能跟着玩家走 —— 否则玩家一边被"拴着"一边还能走，锁链就成了装饰。
 * <p>
 * 于是这里只做一件事：<b>记住某个实体在阵法出现那一刻所站的位置，并且只记这一次</b>。
 * 之后无论玩家怎么动（在锁定生效前的一两 tick 内）、视角怎么转，阵法都留在原地。
 *
 * <h2>为什么按 UUID 存在静态表里</h2>
 * 不需要额外同步：阵法完全是<b>服务端</b>驱动粒子实现的，客户端不需要知道锚点；
 * 而"是否锁住移动"由 {@code AscensionOrbItem#isAnchored} 从原版同步状态算出来，
 * 也不依赖这张表。所以这张表只是服务端内部的一份临时记录，用最简单的形式即可。
 * <p>
 * 清理点在 {@code releaseUsing}（松手必调用）。极端情况（蓄力中直接退游戏/换维度）
 * 会残留一个条目，属于可忽略的量级。
 */
public final class ChargeAnchor {

    private static final Map<UUID, Vec3> ANCHORS = new ConcurrentHashMap<>();

    private ChargeAnchor() {
    }

    /**
     * 取锚点；<b>还没有就此刻建立</b>。
     * <p>
     * 用 {@code computeIfAbsent} 是有意的：调用方每 tick 都会调一次，
     * 但只有第一次会真的写入，后续都只是读取 —— 锚点因此天然"只记一次"。
     */
    public static Vec3 beginIfAbsent(LivingEntity entity) {
        return ANCHORS.computeIfAbsent(entity.getUUID(), key -> entity.position());
    }

    @Nullable
    public static Vec3 get(LivingEntity entity) {
        return ANCHORS.get(entity.getUUID());
    }

    /** 松手 / 取消蓄力时调用。 */
    public static void clear(LivingEntity entity) {
        ANCHORS.remove(entity.getUUID());
    }
}
