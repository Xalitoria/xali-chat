package mc.xalitoria.chat.paper.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import mc.xalitoria.util.bukkit.runTask
import mc.xalitoria.util.bukkit.runTaskLater
import mc.xalitoria.util.bukkit.runTaskTimer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/*
 * Where code runs, on Paper and on Folia: util's scheduler helpers (Entity.runTask, Plugin.runTask and the rest), with
 * the one question this plugin keeps asking added: who is the code for? A command sender is a player, who is an
 * entity with a thread of its own, or the console, which has none.
 */

/**
 * Runs [block] on the thread that owns this sender: a player's own region on Folia, the main thread on Paper. For the
 * console, and for anyone who isn't an entity, it is the global region. A player who has left by then is skipped.
 */
fun CommandSender.runOwned(plugin: Plugin, block: () -> Unit) {
    if (this is Entity) runTask(plugin) { block() } else plugin.runTask { block() }
}

/** [runOwned] after [ticks] ticks. */
fun CommandSender.runOwnedLater(plugin: Plugin, ticks: Long, block: () -> Unit) {
    if (this is Entity) runTaskLater(plugin, ticks) { block() } else plugin.runTaskLater(ticks) { block() }
}

/** Runs [block] on the global region (the main thread on Paper): console commands, and anything that isn't about one player or place. */
fun Plugin.runGlobal(block: () -> Unit) {
    runTask { block() }
}

/** Runs [block] on the global region every [period] ticks, after one, until it cancels the task it is handed. */
fun Plugin.runGlobalRepeating(period: Long, block: (ScheduledTask) -> Unit): ScheduledTask =
    runTaskTimer(1, period) { block(task) }

/** Runs [block] on the global region and returns what it returns, or throws what it threw. */
suspend fun <T> Plugin.onGlobalThread(block: () -> T): T {
    val result = CompletableDeferred<T>()
    runGlobal { result.completeWith(runCatching(block)) }
    return result.await()
}
