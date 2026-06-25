# Publishing to S3 as a Maven Repository

KBuild supports publishing Maven artifacts directly to S3 buckets configured as Maven repositories. This is useful for private artifact hosting without needing a full Nexus/Artifactory setup.

## Quick Start

```kotlin
val s3 = S3MavenPublish.fromEnvironment(
    bucketName = "my-maven-repo",
    region = "us-west-2"
)

s3.publish(
    groupId = "com.example",
    artifactId = "my-library",
    version = "1.0.0",
    artifacts = mapOf(
        "" to jarFile,
        "-sources" to sourcesJar,
        ".pom" to pomFile
    )
)
```

## Environment Variables

Set these before publishing:

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-west-2  # optional, defaults to us-east-1
```

## S3 Bucket Setup

### 1. Create the Bucket

```bash
aws s3 mb s3://my-maven-repo --region us-west-2
```

### 2. Configure Bucket Policy

For public read access (open source projects):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "PublicReadGetObject",
            "Effect": "Allow",
            "Principal": "*",
            "Action": "s3:GetObject",
            "Resource": "arn:aws:s3:::my-maven-repo/*"
        }
    ]
}
```

For private access (use IAM policies instead):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:GetObject",
                "s3:PutObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::my-maven-repo",
                "arn:aws:s3:::my-maven-repo/*"
            ]
        }
    ]
}
```

### 3. Disable Block Public Access (if public)

```bash
aws s3api put-public-access-block \
    --bucket my-maven-repo \
    --public-access-block-configuration \
    "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false"
```

## Using the Repository

### In Gradle

```kotlin
repositories {
    maven {
        url = uri("https://my-maven-repo.s3.us-west-2.amazonaws.com")
    }
}
```

### In KBuild

```kotlin
val myRepo = S3MavenPublish.repository(
    id = "my-repo",
    bucketName = "my-maven-repo",
    region = "us-west-2"
)

val dependencies = MavenAether.libraries(
    dependencies = listOf(com.ivieleague.kbuild.common.Dependency("com.example:my-library:1.0.0").aether()),
    repositories = listOf(myRepo, MavenAether.central)
)
```

## Full Example

```kotlin
object Build {
    fun publishToS3() {
        val mainJar = compile()
        val sourcesJar = createSourcesJar()
        val pomFile = createPom()

        val s3 = S3MavenPublish(
            bucketName = "my-maven-repo",
            region = "us-west-2",
            accessKeyId = System.getenv("AWS_ACCESS_KEY_ID")!!,
            secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")!!
        )

        s3.publish(
            groupId = "com.example",
            artifactId = "my-library",
            version = "1.0.0",
            artifacts = mapOf(
                "" to mainJar,
                "-sources" to sourcesJar,
                ".pom" to pomFile
            ),
            output = ::println
        )
    }
}
```

## With MavenDeploy

If you already have a `MavenDeploy` configuration:

```kotlin
val deploy = MavenDeploy(
    pom = myPom,
    default = { jarFile },
    sources = { sourcesJar }
)

val s3 = S3MavenPublish.fromEnvironment(bucketName = "my-maven-repo")
s3.publish(deploy)
```

## What Gets Published

For each artifact, the following files are uploaded:

```
com/example/my-library/1.0.0/
├── my-library-1.0.0.jar
├── my-library-1.0.0.jar.md5
├── my-library-1.0.0.jar.sha1
├── my-library-1.0.0-sources.jar
├── my-library-1.0.0-sources.jar.md5
├── my-library-1.0.0-sources.jar.sha1
├── my-library-1.0.0.pom
├── my-library-1.0.0.pom.md5
└── my-library-1.0.0.pom.sha1

com/example/my-library/
├── maven-metadata.xml
├── maven-metadata.xml.md5
└── maven-metadata.xml.sha1
```

## S3-Compatible Services

For MinIO or other S3-compatible services:

```kotlin
val s3 = S3MavenPublish(
    bucketName = "my-bucket",
    region = "us-east-1",
    accessKeyId = "minioadmin",
    secretAccessKey = "minioadmin",
    endpoint = "http://localhost:9000"
)
```

## CI/CD Integration

### GitHub Actions

```yaml
- name: Publish to S3
  env:
    AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
    AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
    AWS_REGION: us-west-2
  run: kbuild Build.publishS3
```

### GitLab CI

```yaml
publish:
  script:
    - kbuild Build.publishS3
  variables:
    AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID
    AWS_SECRET_ACCESS_KEY: $AWS_SECRET_ACCESS_KEY
    AWS_REGION: us-west-2
```

## Troubleshooting

### Access Denied

- Check IAM permissions include `s3:PutObject`
- Verify bucket policy allows your IAM user/role
- Check AWS credentials are set correctly

### Signature Mismatch

- Ensure system clock is accurate (NTP sync)
- Check region matches bucket location

### SSL Certificate Errors

- Use HTTPS endpoint (default)
- For custom endpoints, ensure valid SSL certificate
