package com.ivieleague.kbuild.kotlin

import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes every *in-process* use of the embedded Kotlin compiler.
 *
 * The embeddable compiler (used in-process for Kotlin/JS, the commonMain metadata compile, and
 * classpath ABI snapshotting) keeps global mutable state and cannot safely run concurrently with
 * itself. This lock enforces the project invariant: at most one in-process compilation at any
 * instant. Kotlin/JVM compilation runs out-of-process in the Kotlin daemon and native compilation
 * runs in konanc subprocesses, so those overlap each other and the single in-process operation
 * freely — only the in-process work funnels through here.
 *
 * The lock is reentrant so an in-process step that nests another (none today) cannot self-deadlock.
 */
object InProcessCompileLock {
    @PublishedApi
    internal val lock = ReentrantLock()

    /** Run [block] holding the in-process compiler lock. Inline so callers may `return` from within. */
    inline fun <T> guard(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
