package com.mc.archiveworld.item;

import com.mc.archiveworld.world.ArchiveWorldManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;

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
                showMenu(serverPlayer);
            } else {
                ArchiveWorldManager.enterArchiveWorld(serverPlayer);
            }
        }

        return InteractionResultHolder.success(itemStack);
    }

    private static void showMenu(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("━━ Archive World ━━").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(btn("/archiveworld enter", "[Enter World]", ChatFormatting.GREEN, "Click to enter"));
        player.sendSystemMessage(btn("/archiveworld info", "[Show Info]", ChatFormatting.AQUA, "Click for details"));
        player.sendSystemMessage(btn("/archiveworld mark", "[Mark Here]", ChatFormatting.YELLOW, "Click to save position"));
        player.sendSystemMessage(btn("/archiveworld markers", "[Markers]", ChatFormatting.LIGHT_PURPLE, "View saved markers"));
        player.sendSystemMessage(btn("/archiveworld backup", "[Backup]", ChatFormatting.BLUE, "Backup archive world"));
        player.sendSystemMessage(btn("/archiveworld reset", "[Reset Position]", ChatFormatting.RED, "Click to reset position"));
    }

    private static Component btn(String cmd, String label, ChatFormatting color, String hover) {
        return Component.literal(" " + label)
                .withStyle(color)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }
}
