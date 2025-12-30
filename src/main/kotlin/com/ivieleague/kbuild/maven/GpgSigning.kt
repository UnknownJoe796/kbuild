package com.ivieleague.kbuild.maven

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.util.artifact.SubArtifact
import java.io.File

/**
 * GPG signing for Maven artifacts.
 *
 * Signs artifacts using the `gpg` command-line tool.
 * The user must have GPG installed and configured with a signing key.
 *
 * Example:
 * ```
 * val signer = GpgSigner(
 *     keyId = "ABCD1234",  // Optional, uses default key if not specified
 *     passphrase = System.getenv("GPG_PASSPHRASE")
 * )
 *
 * val signedArtifacts = signer.signAll(artifacts)
 * ```
 */
class GpgSigner(
    val keyId: String? = null,
    val passphrase: String? = null,
    val gpgExecutable: String = "gpg"
) {
    /**
     * Check if GPG is available on the system.
     */
    fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder(gpgExecutable, "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sign a file and return the signature file.
     *
     * @param file The file to sign
     * @return The .asc signature file
     */
    fun sign(file: File): File {
        val signatureFile = File(file.absolutePath + ".asc")

        val args = mutableListOf(gpgExecutable)

        // Use specific key if provided
        keyId?.let {
            args.add("--local-user")
            args.add(it)
        }

        // Passphrase handling
        if (passphrase != null) {
            args.add("--batch")
            args.add("--pinentry-mode")
            args.add("loopback")
            args.add("--passphrase-fd")
            args.add("0")
        }

        args.add("--armor")
        args.add("--detach-sign")
        args.add("--output")
        args.add(signatureFile.absolutePath)
        args.add(file.absolutePath)

        val processBuilder = ProcessBuilder(args)
            .redirectErrorStream(true)

        val process = processBuilder.start()

        // Write passphrase to stdin if provided
        if (passphrase != null) {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(passphrase)
                writer.newLine()
                writer.flush()
            }
        }

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("GPG signing failed (exit code $exitCode): $output")
        }

        return signatureFile
    }

    /**
     * Sign an artifact and return the signature artifact.
     */
    fun signArtifact(artifact: Artifact): Artifact {
        val file = artifact.file
            ?: throw IllegalArgumentException("Artifact has no file: $artifact")

        val signatureFile = sign(file)

        return SubArtifact(
            artifact,
            artifact.classifier,
            artifact.extension + ".asc",
            signatureFile
        )
    }

    /**
     * Sign all artifacts and return both original and signature artifacts.
     */
    fun signAll(artifacts: List<Artifact>): List<Artifact> {
        val result = mutableListOf<Artifact>()

        for (artifact in artifacts) {
            result.add(artifact)
            if (artifact.file != null) {
                result.add(signArtifact(artifact))
            }
        }

        return result
    }
}

/**
 * Configuration for GPG signing.
 */
data class GpgConfig(
    val keyId: String? = null,
    val passphrase: String? = null,
    val gpgExecutable: String = "gpg"
) {
    companion object {
        /**
         * Load GPG configuration from environment variables.
         *
         * - GPG_KEY_ID: The key ID to use for signing
         * - GPG_PASSPHRASE: The passphrase for the key
         */
        fun fromEnvironment(): GpgConfig = GpgConfig(
            keyId = System.getenv("GPG_KEY_ID"),
            passphrase = System.getenv("GPG_PASSPHRASE")
        )

        /**
         * Load GPG configuration from a properties file.
         */
        fun fromProperties(file: File): GpgConfig {
            val props = java.util.Properties()
            file.inputStream().use { props.load(it) }
            return GpgConfig(
                keyId = props.getProperty("signing.gnupg.keyName"),
                passphrase = props.getProperty("signing.gnupg.passphrase"),
                gpgExecutable = props.getProperty("signing.gnupg.executable", "gpg")
            )
        }
    }

    fun toSigner(): GpgSigner = GpgSigner(keyId, passphrase, gpgExecutable)
}

/**
 * Extension function to sign artifacts before deployment.
 */
fun List<Artifact>.signWith(signer: GpgSigner): List<Artifact> = signer.signAll(this)
