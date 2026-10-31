package mc.xalitoria.chat.paper.modal.ic

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import mc.xalitoria.chat.paper.XaliChat
import mc.xalitoria.chat.paper.data.constants.Permissions
import mc.xalitoria.util.message.PLAIN
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Chat tokens (`[item]`, `[inv]`, `[ender]`, `[ping]`) that expand into hoverable or clickable components.
 * Inventory tokens store a snapshot that `/xview <id>` opens read-only; the newest [MAX_SNAPSHOTS] are kept.
 */
class Interactives(private val plugin: XaliChat) : Listener {
    enum class Token(val text: String, val permission: String) {
        ITEM("[item]", Permissions.INTERACTIVE_ITEM),
        INVENTORY("[inv]", Permissions.INTERACTIVE_INVENTORY),
        ENDER("[ender]", Permissions.INTERACTIVE_ENDER),
        PING("[ping]", Permissions.INTERACTIVE_PING),
    }

    private class Snapshot(val title: Component, val size: Int, val slots: Map<Int, ItemStack>)

    /** Holds a snapshot inventory, so clicks in it are recognized and canceled. */
    private class SnapshotHolder : InventoryHolder {
        lateinit var view: Inventory
        override fun getInventory(): Inventory = view
    }

    private val snapshots = object : LinkedHashMap<String, Snapshot>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>) = size > MAX_SNAPSHOTS
    }.let(java.util.Collections::synchronizedMap)

    /** The tokens in [text] that [player] may use, each with its replacement. Empty when there are none. */
    fun expand(player: Player, text: String): Map<Token, Component> {
        if (!plugin.config.data.interactiveChat || '[' !in text) return emptyMap()
        val tokens = player.onOwnThread {
            Token.entries
                .filter { it.text in text && player.hasPermission(it.permission) }
                .mapNotNull { token -> render(token, player)?.let { token to it } }
                .toMap()
        }.orEmpty()
        debug("Interactive tokens for {0}: {1}", player.name, tokens.keys)
        return tokens
    }

    /**
     * Plain-text stand-ins for the tokens [player] used in [text], for bridges that can't show hover or click
     * components. Independent of [expand], so it doesn't matter which listener sees the message first.
     */
    fun describe(player: Player, text: String): Map<Token, String> {
        if (!plugin.config.data.interactiveChat || '[' !in text) return emptyMap()
        return player.onOwnThread {
            Token.entries
                .filter { it.text in text && player.hasPermission(it.permission) }
                .mapNotNull { token ->
                    when (token) {
                        Token.ITEM -> player.inventory.itemInMainHand.takeUnless { it.isEmpty }
                            ?.let { PLAIN.serialize(GlobalTranslator.render(itemComponent(it), Locale.US)) }
                        Token.INVENTORY -> "[Inventory]"
                        Token.ENDER -> "[Ender Chest]"
                        Token.PING -> "[${player.ping}ms]"
                    }?.let { token to it }
                }
                .toMap()
        }.orEmpty()
    }

    fun Component.expandTokens(tokens: Map<Token, Component>): Component =
        tokens.entries.fold(this) { message, entry ->
            message.replaceText { it.matchLiteral(entry.key.text).replacement(entry.value) }
        }

    /** Opens snapshot [id] for [viewer]; false when it has expired. */
    fun view(viewer: Player, id: String): Boolean {
        val snapshot = snapshots[id] ?: run {
            debug("Snapshot {0} requested by {1} is gone", id, viewer.name)
            return false
        }
        val holder = SnapshotHolder()
        holder.view = Bukkit.createInventory(holder, snapshot.size, snapshot.title)
        snapshot.slots.forEach { (slot = key, item = value) -> holder.view.setItem(slot, item) }
        viewer.openInventory(holder.view)
        return true
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is SnapshotHolder) event.isCancelled = true
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SnapshotHolder) event.isCancelled = true
    }

    private fun render(token: Token, player: Player): Component? = when (token) {
        Token.ITEM -> player.inventory.itemInMainHand.takeUnless { it.isEmpty }?.let(::itemComponent)
        Token.INVENTORY -> snapshotComponent(
            token, player, msg.inventoryTitle(player.name), size = 54, slots = inventorySlots(player),
        )
        Token.ENDER -> snapshotComponent(
            token, player, msg.enderTitle(player.name), size = 27,
            slots = player.enderChest.contents.withIndex().associate { (slot = index, item = value) -> slot to item },
        )
        Token.PING -> Component.text("[${player.ping}ms]", NamedTextColor.GREEN)
    }

    private fun itemComponent(item: ItemStack): Component = Component.text("[")
        .append(item.effectiveName())
        .append(Component.text(if (item.amount > 1) " x${item.amount}" else ""))
        .append(Component.text("]"))
        .hoverEvent(item)

    private fun snapshotComponent(token: Token, player: Player, title: String, size: Int, slots: Map<Int, ItemStack?>): Component {
        val id = Uuid.random().toHexString().take(ID_LENGTH)
        snapshots[id] = Snapshot(
            Component.text(title),
            size,
            slots.mapNotNull { (slot = key, item = value) -> item?.takeUnless { it.isEmpty }?.let { slot to it.clone() } }.toMap(),
        )
        debug("Stored snapshot {0} ({1}) for {2}, {3} of {4} snapshots kept", id, token, player.name, snapshots.size, MAX_SNAPSHOTS)
        return Component.text(token.text, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
            .hoverEvent(Component.text(msg.hover(player.name)))
            .clickEvent(ClickEvent.runCommand("/xview $id"))
    }

    /** Storage rows first, then the hotbar, armor (helmet first) and offhand, laid out on a double chest. */
    private fun inventorySlots(player: Player): Map<Int, ItemStack?> = buildMap {
        val contents = player.inventory.storageContents
        contents.drop(HOTBAR).forEachIndexed { slot, item -> put(slot, item) }
        contents.take(HOTBAR).forEachIndexed { slot, item -> put(slot + ARMOR_ROW - HOTBAR, item) }
        player.inventory.armorContents.reversed().forEachIndexed { slot, item -> put(ARMOR_ROW + slot, item) }
        put(ARMOR_ROW + 5, player.inventory.itemInOffHand)
    }

    /** Runs [block] on the thread that owns [this] (the region thread on Folia), waiting briefly for the result. */
    private fun <T> Player.onOwnThread(block: () -> T): T? {
        if (plugin.server.isOwnedByCurrentRegion(this)) return block()
        val result = CompletableFuture<T?>()
        scheduler.run(plugin, { result.complete(block()) }, { result.complete(null) })
        return runCatching { result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { debug("Timed out reading {0}'s state on their own thread: {1}", name, it) }
            .getOrNull()
    }

    private val msg get() = plugin.messageConfig.data.commands.interactive

    private companion object {
        const val MAX_SNAPSHOTS = 200
        const val ID_LENGTH = 8
        const val TIMEOUT_SECONDS = 2L
        const val HOTBAR = 9
        const val ARMOR_ROW = 45
    }
}
