# Releasing quarkus-extension-analyzer

This plugin publishes to the Sonatype Central Repository (Maven Central) under the coordinates
`io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin`. All release machinery is
isolated in a Maven profile named `release`, so day-to-day builds are unaffected.

## Prerequisites (one-time, user side)

1. Sonatype Central account and namespace. Create an account at central.sonatype.com and register
   the `io.github.paoloantinori` namespace. Namespace verification proves you own
   `github.com/paoloantinori`; the portal offers a GitHub-repo-based verification (creating the
   namespace repository) or a DNS TXT record. See the official guide:
   https://central.sonatype.org/publish/publish-portal-guide/
2. GPG signing key. Generate a key, publish it to keys.openpgp.org and keyserver.ubuntu.com, and
   have it available on the releasing machine.
3. Central credentials in `~/.m2/settings.xml`. The `central-publishing-maven-plugin` reads the
   server entry whose id matches `publishingServerId` (set to `central` in the POM). Create a
   Portal access token at central.sonatype.com and add:

   <server>
     <id>central</id>
     <username>TOKEN_USERNAME</username>
     <password>TOKEN_PASSWORD</password>
   </server>

## Before releasing

The analyzer must be re-validated on both benches after the TASK-12 bytecode-signal widening.
Complete TASK-13 (re-baseline the Apicurio Registry and Quarkus super-heroes benches) on an idle
machine before cutting a release. See docs/M4-QUARKIVERSE-EVAL.md for why this matters.

## Cutting a release

1. Pick a release version (the project is currently `1.0-SNAPSHOT`). Decide the scheme, for
   example `1.0.0` for a first stable release or `0.1.0` to signal pre-1.0.
2. Update the version, commit, and tag.
3. Deploy with the release profile:

   mvn -Prelease clean deploy

   This builds source and javadoc jars, GPG-signs the artifacts, and uploads the bundle to the
   Central Portal, which auto-publishes.
4. Confirm the artifact appears at
   https://central.sonatype.com/artifact/io.github.paoloantinori/quarkus-extension-analyzer-maven-plugin
   and, after propagation, on Maven Central.

## Release profile plugin versions

The release profile pins `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, and
`org.sonatype.central:central-publishing-maven-plugin`. Confirm these are the latest at release
time; the Central plugin version is documented at
https://central.sonatype.org/publish/publish-portal-maven/.
