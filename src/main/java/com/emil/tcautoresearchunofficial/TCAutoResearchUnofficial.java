package com.emil.tcautoresearchunofficial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.emil.tcautoresearchunofficial.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 模组入口。
 *
 * <p>modid 为 {@code tcautoresearchunofficial}。与原作者的 {@code TCAutoResearchByEmil}
 * 是两个独立模组，可同时安装，配置与存档互不覆盖。</p>
 */
@Mod(modid = TCAutoResearchUnofficial.MODID,
        name = "TCAutoResearchUnofficial",
        version = "1.0.0",
        acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "required-after:Thaumcraft")
public final class TCAutoResearchUnofficial {

    public static final String MODID = "tcautoresearchunofficial";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.emil.tcautoresearchunofficial.client.ClientProxy",
            serverSide = "com.emil.tcautoresearchunofficial.proxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
