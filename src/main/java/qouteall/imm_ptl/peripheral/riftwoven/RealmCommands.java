package qouteall.imm_ptl.peripheral.riftwoven;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.io.IOException;
import java.util.UUID;

/** Optional survival helpers; authorization belongs to the ledger-backed core hooks. */
public final class RealmCommands {
    private RealmCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("riftwoven")
            .then(Commands.literal("invite")
                .then(Commands.argument("realm", UuidArgument.uuid())
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> invite(context, "visit"))
                        .then(Commands.literal("view").executes(context -> invite(context, "view")))
                        .then(Commands.literal("visit").executes(context -> invite(context, "visit")))
                        .then(Commands.literal("build").executes(context -> invite(context, "build"))))))
            .then(Commands.literal("revoke")
                .then(Commands.argument("realm", UuidArgument.uuid())
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(RealmCommands::revoke))))
            .then(Commands.literal("realms").executes(context -> perform(context,
                () -> Riftwoven.listRealms(context.getSource().getPlayerOrException()))))
            .then(Commands.literal("return").executes(context -> perform(context,
                () -> Riftwoven.returnPlayer(context.getSource().getPlayerOrException())))));
    }

    private static int invite(CommandContext<CommandSourceStack> context, String role)
        throws CommandSyntaxException {
        ServerPlayer owner = context.getSource().getPlayerOrException();
        NameAndId member = member(context);
        UUID realm = UuidArgument.getUuid(context, "realm");
        return perform(context, () -> {
            if (role.equals("view")) Riftwoven.grantView(owner, realm, member.id());
            else Riftwoven.grant(owner, realm, member.id(), role.equals("build"));
            context.getSource().sendSuccess(() -> Component.literal(
                "Realm access granted to " + member.name() + " (" + role + ")."), false);
        });
    }

    private static int revoke(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer owner = context.getSource().getPlayerOrException();
        NameAndId member = member(context);
        UUID realm = UuidArgument.getUuid(context, "realm");
        return perform(context, () -> {
            Riftwoven.revoke(owner, realm, member.id());
            context.getSource().sendSuccess(() -> Component.literal(
                "Realm access revoked for " + member.name() + ". Return access remains available."), false);
        });
    }

    private static NameAndId member(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.size() != 1) throw EntityArgument.ERROR_NOT_SINGLE_PLAYER.create();
        return profiles.iterator().next();
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException, CommandSyntaxException;
    }

    private static int perform(CommandContext<CommandSourceStack> context, Action action)
        throws CommandSyntaxException {
        try {
            action.run();
            return 1;
        }
        catch (IOException exception) {
            context.getSource().sendFailure(Component.literal("Realm data is unavailable; no access change confirmed."));
            return 0;
        }
        catch (IllegalArgumentException | IllegalStateException | SecurityException exception) {
            String message = exception.getMessage();
            context.getSource().sendFailure(Component.literal(message == null ? "Realm action denied." : message));
            return 0;
        }
    }
}
