package com.emil.tcautoresearchunofficial.client;

import net.minecraftforge.common.MinecraftForge;

import com.emil.tcautoresearchunofficial.proxy.CommonProxy;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        FMLCommonHandler.instance().bus().register(new ClientTickHandler());
    }
}
