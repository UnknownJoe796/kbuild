package com.ivieleague.kbuild.kotlin

import java.util.concurrent.locks.ReentrantLock

/**
 * The single in-process compilation permit.
 *
 * The embeddable Kotlin compiler (used in-process for Kotlin/JS, the commonMain metadata compile,
 * and classpath ABI snapshotting) keeps process-global state and cannot safely run concurrently
 * with itself. This permit enforces the project invariant: **at most one in-process compilation in
 * the kbuild JVM at any instant.** Kotlin/JVM compilation runs in the Kotlin daemon and native
 * compilation in konanc subprocesses, so those overlap each other and the single in-process
 * operation freely.
 *
 * Two ways to use the permit:
 * - [guard] blocks until the permit is free, then runs in-process. Used for work that must run
 *   in-process (classpath snapshotting) or has no forked form.
 * - [runInProcessOrFork] runs in-process if the permit is immediately free, otherwise runs [fork]
 *   (a separate kbuild JVM). This lets the two in-process-capable compiles (JS and metadata)
 *   **overlap**: whichever is ready first takes the permit and runs in-process; the other forks.
 *
 * The lock is reentrant so the lower-level [guard] calls inside an already-permitted in-process
 * compile (e.g. JS's compiler invocation) re-enter harmlessly on the same thread.
 */
object InProcessCompileLock {
    @PublishedApi
    internal val lock = ReentrantLock()

    /** Run [block] holding the in-process permit, blocking until it is free. */
    inline fun <T> guard(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Run [inProcess] holding the permit if it is immediately available, otherwise run [fork].
     * Both must produce the same result; [fork] is expected to run the same compile in a child JVM.
     */
    inline fun <T> runInProcessOrFork(fork: () -> T, inProcess: () -> T): T {
        if (lock.tryLock()) {
            try {
                return inProcess()
            } finally {
                lock.unlock()
            }
        }
        return fork()
    }
}
