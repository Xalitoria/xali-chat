package mc.xalitoria.chat.paper.util

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.bukkit.runTaskTimer
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.Plugin

/*
 * Where code runs, on Paper and on Folia: util's scheduler helpers (`Entity.runTask`, `Plugin.runTask`, `runOwned` and
 * the rest), with the shapes this plugin's callers like added.
 */

/** Runs [block] on the global region (the main thread on Paper): console commands, and anything that isn't about one player or place. */
fun Plugin.runGlobal(block: () -> Unit) {
    runTask { block() }
}

/** Runs [block] on the global region every [period] ticks, after one, until it cancels the task it is handed. */
fun Plugin.runGlobalRepeating(period: Long, block: (ScheduledTask) -> Unit): ScheduledTask =
    runTaskTimer(1, period) { block(task) }
