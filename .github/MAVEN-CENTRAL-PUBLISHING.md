# Maven Central publishing configuration

The release workflow is `.github/workflows/publish-maven-central.yml`.
Run it from **Actions → Publish Maven to Maven Central → Run workflow**.

## 1. Central Portal prerequisites

1. Create/sign in to a Sonatype Central Portal account.
2. Verify the namespace `io.github.msmt2018` in Central Portal before the first upload.
3. Generate a Central Portal **User Token**. The API credentials are the generated token username and token password, not the account password and not legacy OSSRH credentials.
4. Publish the public GPG key corresponding to `GPG_SIGNING_KEY` to a public keyserver. The workflow also attempts to submit the imported public key to `keyserver.ubuntu.com`.

If the verified namespace differs, set the repository Actions variable `CENTRAL_GROUP_ID` to the exact verified groupId. Do not set it to an unverified namespace.

## 2. Required GitHub Actions secrets

Open **Repository → Settings → Secrets and variables → Actions → New repository secret** and add all five entries:

| Secret name | Required value | Used for |
| --- | --- | --- |
| `GPG_PASSWORD` | Passphrase protecting the private GPG key | Non-interactive GPG signing |
| `GPG_SIGNING_KEY` | ASCII-armored private key, or Base64 of that complete key file | Importing the release signing key |
| `MAVEN_PASSWORD` | Central Portal User Token password | Credential consistency check |
| `MAVEN_PASSWORD_BASE64` | Base64 encoding of the exact `MAVEN_PASSWORD` value | Building the Publisher API bearer token |
| `MAVEN_USERNAME` | Central Portal User Token username | Building the Publisher API bearer token |

`MAVEN_PASSWORD_BASE64` may contain either the Base64 encoding of `MAVEN_PASSWORD` alone (recommended), or the Base64 encoding of `MAVEN_USERNAME:MAVEN_PASSWORD`. The workflow accepts both forms and checks that the decoded value matches the two raw credential secrets. Generate the password-only form locally without adding a newline:

```bash
printf '%s' 'PASTE_THE_CENTRAL_USER_TOKEN_PASSWORD_HERE' | base64 -w 0
```

Paste the output as the value of `MAVEN_PASSWORD_BASE64`. Do not commit credentials, private keys, or generated bearer tokens to the repository.

For `GPG_SIGNING_KEY`, either paste the full ASCII-armored private key beginning with `-----BEGIN PGP PRIVATE KEY BLOCK-----`, or Base64-encode the complete key file:

```bash
base64 -w 0 private-key.asc
```

## 3. Maven coordinates

The root Gradle publication configuration now defaults to:

```text
groupId: io.github.msmt2018
artifactId: each published module name
version: yyyyMMdd-<7-character Git commit hash>
```

The Central workflow supports an optional manually supplied version. The legacy JitPack workflow explicitly overrides the groupId to preserve the existing `com.github.msmt2018.Android-Windbell-TreeSitter` coordinates.

Consumers of the Central release can use Maven Central without repository credentials:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.msmt2018:tree-sitter-cpp:<published-version>")
}
```

## 4. What the workflow validates and publishes

- Installs the pinned JDK, Android NDK, CMake and Android Build Tools.
- Installs grammar package dependencies and generates Java grammar ABI 15.
- Builds the project and publishes all configured Maven publications to Maven Local.
- Requires 60 POMs and verifies the main AAR/JAR plus matching sources and Javadoc JARs.
- Adds the required developer POM metadata and prepares Maven Repository Layout.
- Imports the GPG key, publishes its public key to the configured keyserver, and signs every primary artifact/POM.
- Generates MD5, SHA-1, SHA-256 and SHA-512 checksums.
- Uploads a Central deployment bundle and retains a copy as a GitHub Actions artifact.
- Polls the Central Publisher API until publication succeeds, or reports validation failure.

The workflow defaults to `AUTOMATIC`: Central publishes after successful validation. `USER_MANAGED` leaves a validated deployment awaiting manual publication in Central Portal.

## 5. First release checklist

- [ ] The `io.github.msmt2018` namespace is verified in Central Portal.
- [ ] All five repository Actions secrets exist and are correct.
- [ ] `MAVEN_PASSWORD_BASE64` decodes to either `MAVEN_PASSWORD` or `MAVEN_USERNAME:MAVEN_PASSWORD`.
- [ ] The public GPG key is available from a public keyserver.
- [ ] Run the workflow first with `USER_MANAGED` to inspect Central validation before enabling automatic publication.
