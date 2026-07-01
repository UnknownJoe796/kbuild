package com.ivieleague.kbuild.maven

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Publishes Maven artifacts directly to an S3 bucket configured as a Maven repository.
 *
 * This uses the AWS Signature Version 4 signing process to authenticate requests,
 * so no AWS SDK dependency is required.
 *
 * Usage:
 * ```kotlin
 * val s3 = S3MavenPublish(
 *     bucketName = "my-maven-repo",
 *     region = "us-west-2",
 *     accessKeyId = System.getenv("AWS_ACCESS_KEY_ID"),
 *     secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")
 * )
 *
 * s3.publish(
 *     groupId = "com.example",
 *     artifactId = "my-library",
 *     version = "1.0.0",
 *     artifacts = mapOf(
 *         "" to jarFile,           // main artifact
 *         "-sources" to sourcesJar,
 *         "-javadoc" to javadocJar,
 *         ".pom" to pomFile
 *     )
 * )
 * ```
 *
 * @param bucketName The S3 bucket name
 * @param region AWS region (e.g., "us-west-2")
 * @param accessKeyId AWS access key ID
 * @param secretAccessKey AWS secret access key
 * @param endpoint Optional custom endpoint (for S3-compatible services like MinIO)
 */
class S3MavenPublish(
    val bucketName: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val endpoint: String? = null
) {
    private val host = endpoint ?: "$bucketName.s3.$region.amazonaws.com"
    private val baseUrl = "https://$host"

    /**
     * Publish a complete Maven artifact with all classifiers.
     *
     * @param groupId Maven group ID (e.g., "com.example")
     * @param artifactId Maven artifact ID (e.g., "my-library")
     * @param version Version string (e.g., "1.0.0")
     * @param packaging Packaging type (default: "jar")
     * @param artifacts Map of classifier to file. Use "" for main artifact, ".pom" for POM.
     * @param output Callback for progress messages
     */
    fun publish(
        groupId: String,
        artifactId: String,
        version: String,
        packaging: String = "jar",
        artifacts: Map<String, File>,
        output: (String) -> Unit = ::println
    ) {
        val basePath = "${groupId.replace('.', '/')}/$artifactId/$version"

        output("Publishing to s3://$bucketName/$basePath")

        for ((classifier, file) in artifacts) {
            val fileName = when {
                classifier == ".pom" -> "$artifactId-$version.pom"
                classifier.isEmpty() -> "$artifactId-$version.$packaging"
                else -> "$artifactId-$version$classifier.$packaging"
            }

            val key = "$basePath/$fileName"
            output("  Uploading $fileName...")

            uploadFile(key, file)

            // Also upload checksums
            uploadChecksum(key, file, "md5")
            uploadChecksum(key, file, "sha1")
        }

        // Update maven-metadata.xml
        updateMetadata(groupId, artifactId, version, output)

        output("Published $groupId:$artifactId:$version to s3://$bucketName/")
    }

    /**
     * Publish using a MavenDeploy configuration.
     */
    fun publish(deploy: MavenDeploy, output: (String) -> Unit = ::println) {
        val pom = deploy.pom
        val artifacts = mutableMapOf<String, File>()

        // Main artifact
        artifacts[""] = deploy.default

        // POM
        artifacts[".pom"] = pom.write()

        // Optional sources
        deploy.sources?.let { artifacts["-sources"] = it }

        // Optional javadoc
        deploy.documentation?.let { artifacts["-javadoc"] = it }

        publish(
            groupId = pom.projectIdentifier.group,
            artifactId = pom.projectIdentifier.name,
            version = pom.projectIdentifier.version.toString(),
            packaging = pom.model.packaging ?: "jar",
            artifacts = artifacts,
            output = output
        )
    }

    private fun uploadFile(key: String, file: File) {
        val contentType = when (file.extension) {
            "jar" -> "application/java-archive"
            "pom", "xml" -> "application/xml"
            "md5", "sha1" -> "text/plain"
            else -> "application/octet-stream"
        }

        val bytes = file.readBytes()
        putObject(key, bytes, contentType)
    }

    private fun uploadChecksum(key: String, file: File, algorithm: String) {
        val digest = when (algorithm) {
            "md5" -> MessageDigest.getInstance("MD5")
            "sha1" -> MessageDigest.getInstance("SHA-1")
            else -> throw IllegalArgumentException("Unknown algorithm: $algorithm")
        }

        val hash = digest.digest(file.readBytes())
        val hexHash = hash.joinToString("") { "%02x".format(it) }

        putObject("$key.$algorithm", hexHash.toByteArray(), "text/plain")
    }

    private fun updateMetadata(
        groupId: String,
        artifactId: String,
        version: String,
        output: (String) -> Unit
    ) {
        val metadataPath = "${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"

        // Try to get existing metadata
        val existingMetadata = try {
            getObject(metadataPath)
        } catch (e: Exception) {
            null
        }

        val versions = mutableSetOf<String>()
        var release = version
        var lastUpdated = ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

        // Parse existing metadata if available
        if (existingMetadata != null) {
            val content = String(existingMetadata)
            // Simple XML parsing for versions
            val versionRegex = Regex("<version>([^<]+)</version>")
            versionRegex.findAll(content).forEach { match ->
                versions.add(match.groupValues[1])
            }
            val releaseRegex = Regex("<release>([^<]+)</release>")
            releaseRegex.find(content)?.let { release = it.groupValues[1] }
        }

        versions.add(version)

        // Determine latest release (non-snapshot)
        val latestRelease = versions
            .filter { !it.contains("SNAPSHOT") }
            .maxByOrNull { it }
            ?: version

        val metadata = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>$groupId</groupId>
              <artifactId>$artifactId</artifactId>
              <versioning>
                <latest>$version</latest>
                <release>$latestRelease</release>
                <versions>
            ${versions.sorted().joinToString("\n") { "      <version>$it</version>" }}
                </versions>
                <lastUpdated>$lastUpdated</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()

        output("  Updating maven-metadata.xml...")
        putObject(metadataPath, metadata.toByteArray(), "application/xml")

        // Checksums for metadata
        val metadataBytes = metadata.toByteArray()
        val md5 = MessageDigest.getInstance("MD5").digest(metadataBytes)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(metadataBytes)

        putObject("$metadataPath.md5", md5.joinToString("") { "%02x".format(it) }.toByteArray(), "text/plain")
        putObject("$metadataPath.sha1", sha1.joinToString("") { "%02x".format(it) }.toByteArray(), "text/plain")
    }

    private fun putObject(key: String, content: ByteArray, contentType: String) {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        val contentHash = sha256Hex(content)

        val canonicalUri = if (endpoint != null) "/$bucketName/$key" else "/$key"
        val canonicalQueryString = ""
        val canonicalHeaders = """
            host:$host
            x-amz-content-sha256:$contentHash
            x-amz-date:$amzDate
        """.trimIndent() + "\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = """
            PUT
            $canonicalUri
            $canonicalQueryString
            $canonicalHeaders
            $signedHeaders
            $contentHash
        """.trimIndent()

        val algorithm = "AWS4-HMAC-SHA256"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = """
            $algorithm
            $amzDate
            $credentialScope
            ${sha256Hex(canonicalRequest.toByteArray())}
        """.trimIndent()

        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$algorithm Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val url = URL("$baseUrl$canonicalUri")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Host", host)
        connection.setRequestProperty("x-amz-date", amzDate)
        connection.setRequestProperty("x-amz-content-sha256", contentHash)
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("Content-Length", content.size.toString())

        connection.outputStream.use { it.write(content) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = try {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            throw RuntimeException("Failed to upload $key: HTTP $responseCode - $errorBody")
        }
    }

    private fun getObject(key: String): ByteArray? {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        val contentHash = "UNSIGNED-PAYLOAD"

        val canonicalUri = if (endpoint != null) "/$bucketName/$key" else "/$key"
        val canonicalQueryString = ""
        val canonicalHeaders = """
            host:$host
            x-amz-content-sha256:$contentHash
            x-amz-date:$amzDate
        """.trimIndent() + "\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = """
            GET
            $canonicalUri
            $canonicalQueryString
            $canonicalHeaders
            $signedHeaders
            $contentHash
        """.trimIndent()

        val algorithm = "AWS4-HMAC-SHA256"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = """
            $algorithm
            $amzDate
            $credentialScope
            ${sha256Hex(canonicalRequest.toByteArray())}
        """.trimIndent()

        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$algorithm Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val url = URL("$baseUrl$canonicalUri")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Host", host)
        connection.setRequestProperty("x-amz-date", amzDate)
        connection.setRequestProperty("x-amz-content-sha256", contentHash)
        connection.setRequestProperty("Authorization", authorization)

        val responseCode = connection.responseCode
        if (responseCode == 404) {
            return null
        }
        if (responseCode !in 200..299) {
            throw RuntimeException("Failed to get $key: HTTP $responseCode")
        }

        return connection.inputStream.use { it.readBytes() }
    }

    // AWS Signature V4 helpers

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun getSignatureKey(key: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256("AWS4$key".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    companion object {
        /**
         * Create an S3MavenPublish instance from environment variables.
         *
         * Uses:
         * - AWS_ACCESS_KEY_ID
         * - AWS_SECRET_ACCESS_KEY
         * - AWS_REGION (optional, defaults to "us-east-1")
         */
        fun fromEnvironment(
            bucketName: String,
            region: String = System.getenv("AWS_REGION") ?: "us-east-1"
        ): S3MavenPublish {
            val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID")
                ?: throw IllegalStateException("AWS_ACCESS_KEY_ID environment variable not set")
            val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")
                ?: throw IllegalStateException("AWS_SECRET_ACCESS_KEY environment variable not set")

            return S3MavenPublish(
                bucketName = bucketName,
                region = region,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey
            )
        }

        /**
         * Create a repository builder for use with MavenAether.
         *
         * Note: This creates a read-only repository reference.
         * For publishing, use S3MavenPublish directly.
         */
        fun repository(
            id: String,
            bucketName: String,
            region: String
        ): org.eclipse.aether.repository.RemoteRepository {
            return org.eclipse.aether.repository.RemoteRepository.Builder(
                id,
                "default",
                "https://$bucketName.s3.$region.amazonaws.com"
            ).build()
        }
    }
}
