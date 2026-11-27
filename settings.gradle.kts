import java.net.URI


pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = URI.create("https://jitpack.io") }
    }
}

rootProject.name = "OUTKAST"

include(":app")
include(":detekt-rules")

include(":core:utils")
include(":core:nips")
include(":core:app-config")
include(":core:networking-lightning")
include(":core:networking-http")
include(":core:networking-primal")
include(":core:networking-upload")
include(":core:caching")
include(":core:testing")

include(":data:shared:local")

include(":data:caching:local")
include(":data:caching:remote")
include(":data:caching:repository")

// NWC (Nostr Wallet Connect, NIP-47) client: how `app` pays from an EXTERNAL wallet the user connects.
include(":data:wallet:remote-nwc")

include(":data:account:local")
include(":data:account:remote")
include(":data:account:signer")
include(":data:account:repository")

include(":domain:nostr")
include(":domain:primal")
include(":domain:account")
