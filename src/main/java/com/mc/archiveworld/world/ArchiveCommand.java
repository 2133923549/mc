package com.mc.archiveworld.world;

import com.mc.archiveworld.item.ModItems;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

public class ArchiveCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("archiveworld")
                .requires(src -> src.getEntity() instanceof ServerPlayer p && hasBook(p));

        root.then(Commands.literal("enter").executes(ctx -> {
            var p = (ServerPlayer) ctx.getSource().getEntity();
            ArchiveWorldManager.enterArchiveWorld(p);
            return 1;
        }));

        root.then(Commands.literal("info").executes(ctx -> {
            showInfo((ServerPlayer) ctx.getSource().getEntity());
            return 1;
        }));

        root.then(Commands.literal("mark").executes(ctx -> {
            var p = (ServerPlayer) ctx.getSource().getEntity();
            ArchiveMarks.add(p.getX(), p.getY(), p.getZ());
            p.sendSystemMessage(Component.literal("Position marked."));
            return 1;
        }));

        root.then(Commands.literal("markers").executes(ctx -> {
            listMarkers((ServerPlayer) ctx.getSource().getEntity());
            return 1;
        }));

        root.then(Commands.literal("tp").then(
            Commands.argument("index", IntegerArgumentType.integer(0)).executes(ctx -> {
                teleportToMarker((ServerPlayer) ctx.getSource().getEntity(),
                        IntegerArgumentType.getInteger(ctx, "index"));
                return 1;
            })
        ));

        root.then(Commands.literal("backup").executes(ctx -> {
            var p = (ServerPlayer) ctx.getSource().getEntity();
            var path = ArchiveBackup.createBackup(p.getServer());
            if (path != null) {
                p.sendSystemMessage(Component.literal("Backup created: " + path.getFileName()));
            } else {
                p.sendSystemMessage(Component.literal("Backup failed."));
            }
            return 1;
        }));

        root.then(Commands.literal("reset").executes(ctx -> {
            var p = (ServerPlayer) ctx.getSource().getEntity();
            ArchiveWorldManager.resetPosition(p.getUUID());
            p.sendSystemMessage(Component.literal("Archive position reset."));
            return 1;
        }));

        dispatcher.register(root);
    }

    private static boolean hasBook(ServerPlayer p) {
        return p.getInventory().hasAnyMatching(s -> s.is(ModItems.ARCHIVE_BOOK.get()));
    }

    private static void showInfo(ServerPlayer p) {
        if (!ArchiveWorldStorage.exists()) {
            p.sendSystemMessage(Component.literal("Archive World unavailable."));
            return;
        }
        var meta = ArchiveMeta.loadOrCreate();
        var marks = ArchiveMarks.loadAll();
        p.sendSystemMessage(Component.literal("[Archive World]"));
        p.sendSystemMessage(Component.literal("  Name: " + meta.worldName));
        p.sendSystemMessage(Component.literal("  Created: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(meta.createdAt))));
        p.sendSystemMessage(Component.literal("  Marks: " + marks.size()));
        p.sendSystemMessage(Component.literal("  Generator: " + meta.generatorType));
    }

    private static void listMarkers(ServerPlayer p) {
        var marks = ArchiveMarks.loadAll();
        if (marks.isEmpty()) {
            p.sendSystemMessage(Component.literal("No markers saved."));
            return;
        }
        p.sendSystemMessage(Component.literal("━━ Markers (" + marks.size() + ") ━━").withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < marks.size(); i++) {
            final int idx = i;
            var m = marks.get(i);
            p.sendSystemMessage(Component.literal(" [" + (idx + 1) + "] (" + (int) m.x + ", " + (int) m.y + ", " + (int) m.z + ")")
                    .withStyle(ChatFormatting.AQUA)
                    .withStyle(s -> s.withClickEvent(
                            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/archiveworld tp " + idx))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to teleport")))));
        }
    }

    private static void teleportToMarker(ServerPlayer p, int index) {
        var marks = ArchiveMarks.loadAll();
        if (index < 0 || index >= marks.size()) return;
        var level = p.getServer().getLevel(ArchiveWorldManager.ARCHIVE_KEY);
        if (level == null) {
            p.sendSystemMessage(Component.literal("Archive World not loaded."));
            return;
        }
        var m = marks.get(index);
        p.teleportTo(level, m.x, m.y, m.z, p.getYRot(), p.getXRot());
    }
}
