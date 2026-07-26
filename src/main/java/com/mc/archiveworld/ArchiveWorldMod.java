package com.mc.archiveworld;

import com.mc.archiveworld.item.ModItems;
import com.mc.archiveworld.world.ArchiveWorldManager;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import com.mc.archiveworld.world.ArchiveCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ArchiveWorldMod.MODID)
public class ArchiveWorldMod {

    public static final String MODID = "archiveworld";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ArchiveWorldMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModItems.ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Archive World Mod initialized");
    }

    private void addCreative(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.ARCHIVE_BOOK);
        }
    }

    @SubscribeEvent
    public void onServerStarted(final ServerStartedEvent event) {
        ArchiveWorldManager.loadSharedArchiveWorld(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        ArchiveWorldManager.onServerStopping();
    }

    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        ArchiveCommand.register(event.getDispatcher());
    }
}
