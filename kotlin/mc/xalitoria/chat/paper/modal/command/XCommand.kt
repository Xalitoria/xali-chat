package mc.xalitoria.chat.paper.modal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.chat.paper.XaliChat

abstract class XCommand(val plugin: XaliChat) {
    abstract val description: String
    abstract val mainCommand: String
    open val aliases: List<String> = []
    abstract fun builder(): LiteralArgumentBuilder<CommandSourceStack>
    fun register(registrar: Commands) {
        registrar.register(builder().build(), description, aliases)
    }
}