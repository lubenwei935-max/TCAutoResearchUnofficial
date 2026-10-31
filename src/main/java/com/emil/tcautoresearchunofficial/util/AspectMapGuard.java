package com.emil.tcautoresearchunofficial.util;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.emil.tcautoresearchunofficial.Config;
import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.Thaumcraft;

/**
 * 给玩家的「已发现要素」列表换上 {@link SafeAspectMap}。
 *
 * <p>崩溃链：渲染线程 {@code GuiResearchTable.drawAspectText} 遍历
 * {@code PlayerKnowledge.getAspectsDiscovered(name).aspects}，
 * Netty 线程 {@code PacketAspectDiscovery} 同时往同一个 LinkedHashMap 里写。
 * 我们的自动解锁会高频触发要素发现包，等于把这条竞态放大到必现。</p>
 *
 * <p>换掉 {@code AspectList.aspects} 的实例（字段是 public 且非 final）即可让
 * 读侧拿到快照、写侧排队，双方不再互相打断。只动客户端内存，不影响存档与网络协议。</p>
 */
public final class AspectMapGuard {

    /** 每 20 tick 检查一次，列表被神秘时代重建时能自动补上。 */
    private static final int CHECK_INTERVAL = 20;

    private static Field aspectsField;
    private static WeakReference<AspectList> guarded = new WeakReference<AspectList>(null);
    private static boolean unavailable;
    private static int ticks;

    private AspectMapGuard() {
    }

    public static void tick() {
        if (unavailable || !Config.safeAspectIteration) {
            return;
        }
        if (++ticks < CHECK_INTERVAL) {
            return;
        }
        ticks = 0;
        try {
            install();
        } catch (Throwable t) {
            unavailable = true;
            TCAutoResearchUnofficial.LOG.warn("无法为玩家要素列表启用并发保护（已放弃，不影响其它功能）", t);
        }
    }

    private static void install() throws Exception {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        AspectList list = Thaumcraft.proxy.getPlayerKnowledge().getAspectsDiscovered(player.getCommandSenderName());
        if (list == null || list == guarded.get()) {
            return;
        }
        if (aspectsField == null) {
            aspectsField = AspectList.class.getDeclaredField("aspects");
            aspectsField.setAccessible(true);
        }
        Object current = aspectsField.get(list);
        if (!(current instanceof SafeAspectMap)) {
            @SuppressWarnings("unchecked")
            Map<Aspect, Integer> source = (Map<Aspect, Integer>) current;
            aspectsField.set(list, new SafeAspectMap(source));
            TCAutoResearchUnofficial.LOG.debug("已为玩家要素列表启用遍历安全映射");
        }
        guarded = new WeakReference<AspectList>(list);
    }
}
