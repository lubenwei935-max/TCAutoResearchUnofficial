package com.emil.tcautoresearchunofficial.client;

import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;
import com.emil.tcautoresearchunofficial.research.ResearchAssistant;
import com.emil.tcautoresearchunofficial.util.ClientTasks;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/** 客户端唯一的“心跳”：先把工作线程的任务切回主线程，再推进状态机。 */
public final class ClientTickHandler {

    private boolean errorLogged;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != Side.CLIENT) {
            return;
        }
        try {
            ClientTasks.drain();
            ResearchAssistant.get().tick();
        } catch (Throwable t) {
            // 每 tick 都会跑，日志只记一次，避免刷屏
            if (!errorLogged) {
                errorLogged = true;
                TCAutoResearchUnofficial.LOG.error("研究台助手 tick 异常（已跳过本次）", t);
            }
        }
    }
}
