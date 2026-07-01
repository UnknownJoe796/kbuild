package com.ivieleague.kbuild.maven

import org.apache.maven.model.Developer
import org.apache.maven.model.License
import org.apache.maven.model.Model
import org.apache.maven.model.Scm

/** License metadata for a Maven POM. */
data class PomLicense(
    val name: String,
    val url: String? = null
)

/** Developer metadata for a Maven POM. */
data class PomDeveloper(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null
)

/**
 * Declarative POM metadata for a published library.
 *
 * Fields left null are omitted from the generated POM — identical to not calling any configuration
 * at all — so defaults are fully preserved. Pass this to [KmpPublisher] or [JvmLibrary.createPom]
 * instead of using a raw Apache Maven [Model] callback.
 */
data class PomMetadata(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val licenses: List<PomLicense> = emptyList(),
    val developers: List<PomDeveloper> = emptyList(),
    val scmUrl: String? = null
)

/** Applies [PomMetadata] fields onto a Maven [Model]. Internal use only. */
internal fun PomMetadata.applyTo(model: Model) {
    name?.let { model.name = it }
    description?.let { model.description = it }
    url?.let { model.url = it }
    if (licenses.isNotEmpty()) {
        model.licenses = licenses.map { lic ->
            License().apply {
                name = lic.name
                lic.url?.let { url = it }
            }
        }
    }
    if (developers.isNotEmpty()) {
        model.developers = developers.map { dev ->
            Developer().apply {
                dev.id?.let { id = it }
                dev.name?.let { name = it }
                dev.email?.let { email = it }
            }
        }
    }
    if (scmUrl != null) {
        model.scm = Scm().apply { url = scmUrl }
    }
}
