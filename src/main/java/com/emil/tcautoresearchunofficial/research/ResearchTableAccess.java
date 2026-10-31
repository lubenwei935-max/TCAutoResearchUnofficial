package com.emil.tcautoresearchunofficial.research;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.common.Thaumcraft;
import thaumcraft.common.container.ContainerResearchTable;
import thaumcraft.common.lib.network.PacketHandler;
import thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer;
import thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;
import thaumcraft.common.tiles.TileResearchTable;

/**
 * 研究台 GUI / 容器数据的唯一访问点。
 *
 * <p>只用到神秘时代自己的公开成员（{@code GuiResearchTable.note}、
 * {@code ContainerResearchTable.tileEntity}、{@code TileResearchTable.bonusAspects}），
 * 不需要 {@code @Shadow}，也就不需要 Mixin。</p>
 */
public final class ResearchTableAccess {

    private ResearchTableAccess() {
    }

    public static EntityPlayer player() {
        return Minecraft.getMinecraft().thePlayer;
    }

    public static ResearchNoteData note(GuiResearchTable gui) {
        return gui == null ? null : gui.note;
    }

    /** 打开着的容器对应的研究台方块；未打开研究台时返回 null。 */
    public static TileResearchTable table() {
        EntityPlayer player = player();
        if (player != null && player.openContainer instanceof ContainerResearchTable) {
            return ((ContainerResearchTable) player.openContainer).tileEntity;
        }
        return null;
    }

    /** 玩家已发现要素的当前状态。 */
    public static AspectList knowledge(EntityPlayer player) {
        if (player == null) {
            return new AspectList();
        }
        AspectList list = Thaumcraft.proxy.getPlayerKnowledge().getAspectsDiscovered(player.getCommandSenderName());
        return list == null ? new AspectList() : list;
    }

    public static AspectList knowledge() {
        return knowledge(player());
    }

    /** 发送一次要素合成请求（与神秘时代自己的 GUI 走同一个包）。 */
    public static void combine(TileResearchTable table, Aspect first, Aspect second) {
        EntityPlayer player = player();
        if (player == null || table == null) {
            return;
        }
        boolean bonusA = table.bonusAspects != null && table.bonusAspects.getAmount(first) > 0;
        boolean bonusB = table.bonusAspects != null && table.bonusAspects.getAmount(second) > 0;
        PacketHandler.INSTANCE.sendToServer((IMessage) new PacketAspectCombinationToServer(
                player, table.xCoord, table.yCoord, table.zCoord, first, second, bonusA, bonusB, true));
    }

    /** 把要素放进笔记的某个格子。 */
    public static void place(TileResearchTable table, HexUtils.Hex hex, Aspect aspect) {
        EntityPlayer player = player();
        if (player == null || table == null || hex == null || aspect == null) {
            return;
        }
        PacketHandler.INSTANCE.sendToServer((IMessage) new PacketAspectPlaceToServer(
                player, (byte) hex.q, (byte) hex.r,
                table.xCoord, table.yCoord, table.zCoord, aspect));
    }
}
