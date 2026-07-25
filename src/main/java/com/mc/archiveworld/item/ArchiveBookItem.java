package com.mc.archiveworld.item;

import com.mc.archiveworld.world.ArchiveMeta;
import com.mc.archiveworld.world.ArchiveWorldManager;
import com.mc.archiveworld.world.ArchiveWorldStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ArchiveBookItem extends Item {

    public ArchiveBookItem() {
        super(new Item.Properties()
                .stacksTo(1)
                .rarity(Rarity.UNCOMMON));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(itemStack);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                showInfo(serverPlayer);
            } else {
                ArchiveWorldManager.enterArchiveWorld(serverPlayer);
            }
        }

        return InteractionResultHolder.success(itemStack);
    }

    private static void showInfo(ServerPlayer player) {
        if (!ArchiveWorldStorage.exists()) {
            player.sendSystemMessage(Component.literal("Archive World unavailable."));
            return;
        }
        var meta = ArchiveMeta.loadOrCreate();
        var fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        player.sendSystemMessage(Component.literal(
                "[Archive World]"));
        player.sendSystemMessage(Component.literal(
                "  Name: " + meta.worldName));
        player.sendSystemMessage(Component.literal(
                "  Generator: " + meta.generatorType));
        player.sendSystemMessage(Component.literal(
                "  Created: " + fmt.format(new Date(meta.createdAt))));
        player.sendSystemMessage(Component.literal(
                "  Last Access: " + fmt.format(new Date(meta.lastAccessedAt))));
    }
}
