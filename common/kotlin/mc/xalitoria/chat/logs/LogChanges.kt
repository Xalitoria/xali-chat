package mc.xalitoria.chat.logs

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts what has happened to the logs in the shared database since this process started, so a viewer that has seen
 * [version] knows nothing needs reading when it is still the same. Every write by this process bumps it, and
 * [LogPings] bumps it when another server says it wrote.
 *
 * Writes by others are only known about through those pings, so [trusted] says whether they are being heard. Without
 * them an unchanged [version] says nothing and the database has to be read.
 */
class LogChanges {
    private val counter = AtomicLong()

    /** True while [LogPings] is connected, so that every write anywhere has been or will be heard of. Set by it alone. */
    @Volatile
    var trusted = false

    val version: Long get() = counter.get()

    fun bump(): Long = counter.incrementAndGet()
}
