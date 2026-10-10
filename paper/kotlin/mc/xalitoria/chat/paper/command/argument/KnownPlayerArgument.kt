package mc.xalitoria.chat.paper.command.argument

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import mc.xalitoria.chat.paper.util.*
import mc.xalitoria.util.message.*
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import java.util.concurrent.CompletableFuture

/**
 * A player by UUID or exact name, online or seen before, as an [OfflinePlayer]. Unknown players fail
 * the parse with [invalid]; tab completion skips vanished players.
 */
class KnownPlayerArgument(
    private val server: Server,
    private val invalid: (name: String) -> String,
) : CustomArgumentType.Converted<OfflinePlayer, String> {
    private val unknown = DynamicCommandExceptionType { name ->
        MessageComponentSerializer.message().serialize((FAIL_COLOR + invalid(name.toString())).toComponent())
    }

    override fun getNativeType(): ArgumentType<String> = StringArgumentType.word()

    @Throws(CommandSyntaxException::class)
    override fun convert(nativeType: String): OfflinePlayer =
        server.findKnownPlayer(nativeType) ?: throw unknown.create(nativeType)

    override fun <S : Any> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val sender = (context.source as? CommandSourceStack)?.sender ?: return builder.buildFuture()
        return builder.suggestMatching(server.visiblePlayerNames(sender))
    }
}
