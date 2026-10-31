package mc.xalitoria.chat.paper.concurrent

import mc.xalitoria.chat.paper.XaliChat.Companion.debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mc.xalitoria.util.message.fail
import java.util.logging.Logger
import kotlin.time.Duration

/**
 * Batches entries and writes them off-thread; [submit] never blocks. Entries that can't be written
 * (write fails, queue closed, shutdown times out) go to [fallback].
 */
class LogWriter<T : Any>(
    scope: CoroutineScope,
    private val username: String,
    private val logger: Logger,
    private val write: suspend (List<T>) -> Unit,
    private val fallback: (T) -> Unit,
) {
    private val queue = Channel<T>(Channel.UNLIMITED)

    private val consumer = scope.launch(Dispatchers.IO) {
        for (first in queue) {
            val batch = ArrayList<T>(MAX_BATCH)
            batch += first
            while (batch.size < MAX_BATCH) batch += queue.tryReceive().getOrNull() ?: break
            try {
                debug("Writing a batch of {0} {1} log entries", batch.size, username)
                write(batch)
            } catch (e: Exception) {
                logger.fail("Couldn't write ${batch.size} $username log entries to the database; handing them to the fallback.", e)
                batch.forEach(fallback)
            }
        }
    }

    fun submit(entry: T) {
        if (queue.trySend(entry).isFailure) {
            debug("{0} log queue is closed; using the text log fallback", username)
            fallback(entry)
        }
    }

    /** Stops accepting entries and waits up to [timeout] for the queue to drain; leftovers go to [fallback]. */
    suspend fun close(timeout: Duration) {
        queue.close()
        if (withTimeoutOrNull(timeout) { consumer.join() } == null) {
            debug("{0} log queue did not drain within {1}; flushing the rest to the text log", username, timeout)
            consumer.cancel()
            while (true) fallback(queue.tryReceive().getOrNull() ?: break)
        }
    }

    private companion object {
        const val MAX_BATCH = 200
    }
}
