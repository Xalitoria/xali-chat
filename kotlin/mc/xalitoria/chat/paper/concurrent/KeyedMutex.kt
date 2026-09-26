package mc.xalitoria.chat.paper.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes callers per key; different keys never block each other. Mutexes live for the process. */
class KeyedMutex<K> {
    private val mutexes = ConcurrentHashMap<K, Mutex>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T =
        mutexes.computeIfAbsent(key) { Mutex() }.withLock { block() }
}
