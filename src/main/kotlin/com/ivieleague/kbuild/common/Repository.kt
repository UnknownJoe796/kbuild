package com.ivieleague.kbuild.common

import org.eclipse.aether.repository.RemoteRepository
import java.io.File

/**
 * A Maven repository used as a publish target.
 *
 * Use [Repository.mavenLocal] for the local `~/.m2/repository`, or supply a URL for any
 * remote repository.
 *
 * Example:
 * ```
 * MyLibrary.publish(Repository("https://lightningkite-maven.s3.us-west-2.amazonaws.com", id = "lightningkite"))
 * ```
 */
data class Repository(val url: String, val id: String = "repo") {

    companion object {
        /** The local Maven cache (`~/.m2/repository`). Default publish target. */
        val mavenLocal = Repository(
            url = "file://" + File(System.getProperty("user.home"), ".m2/repository").invariantSeparatorsPath,
            id = "local"
        )
    }

    /** Convert to an Eclipse Aether [RemoteRepository] for internal use. */
    internal fun toAether(): RemoteRepository =
        RemoteRepository.Builder(id, "default", url).build()
}
