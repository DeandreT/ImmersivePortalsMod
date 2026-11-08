package qouteall.imm_ptl.core.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/** The debug commands do not inspect their loader-specific command source. */
final class ClientCommands {
    static LiteralArgumentBuilder<Object> literal(String literal) {
        return LiteralArgumentBuilder.literal(literal);
    }

    static <T> RequiredArgumentBuilder<Object, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private ClientCommands() {}
}
