package com.mc.archiveworld.item;

import com.mc.archiveworld.ArchiveWorldMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ArchiveWorldMod.MODID);

    public static final RegistryObject<Item> ARCHIVE_BOOK =
            ITEMS.register("archive_book", ArchiveBookItem::new);
}
