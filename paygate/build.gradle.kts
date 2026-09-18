plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val sdkVersion = rootProject.file("VERSION").readText().trim()

android {
    namespace = "com.paygate.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // **8.0.0 is a floor, not a preference — do not lower it.**
    //
    // `ProductDetailsResponseListener` is binary-incompatible between 7 and 8:
    //
    //   7.1.1  onProductDetailsResponse(BillingResult, List<ProductDetails>)
    //   8.0.0  onProductDetailsResponse(BillingResult, QueryProductDetailsResult)
    //
    // Gradle resolves a conflict by taking the *highest* version, so any host
    // app that also depends on Billing 8 — every app using `in_app_purchase`
    // 0.5.x, which requires exactly 8.0.0 — puts 8 on the classpath regardless
    // of what this module compiled against. A lambda compiled against 7.1.1
    // then implements a method the 8.0.0 client never calls, and the callback
    // dies with AbstractMethodError somewhere inside Play's own thread.
    //
    // The symptom is not a crash. `queryProductDetails` simply never resumes,
    // its 8-second timeout fires, and the reader who tapped Buy watches the
    // paywall sit still and then close. That shipped, and it is why this is
    // pinned forward rather than left to resolution.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}

/**
 * The Maven coordinate's group.
 *
 * Configurable because Maven Central verifies that you own the namespace, and
 * which one you can claim depends on a domain you control:
 *   com.paygate            needs paygate.com
 *   dev.paygate            needs paygate.dev
 *   io.github.build-context  needs only the GitHub org, so it is always claimable
 *
 * GitHub Packages does not verify anything, which is why this has been
 * com.paygate unchallenged. Changing it changes the coordinate every consumer
 * imports, so the React Native and Flutter pins and the install docs move with
 * it — see sdks/RELEASING.md.
 */
val paygateGroupId: String = (findProperty("paygate.groupId") as String?) ?: "com.paygate"

val ossrhUsername = System.getenv("OSSRH_USERNAME")
val ossrhPassword = System.getenv("OSSRH_PASSWORD")
val signingPrivateKey = System.getenv("SIGNING_PRIVATE_KEY")
val signingPassword = System.getenv("SIGNING_PASSWORD")

/**
 * Where Central actually accepts uploads now.
 *
 * The old OSSRH hosts this used to point at — oss.sonatype.org and
 * s01.oss.sonatype.org — were decommissioned and both return 404, so the Central
 * path here could not have worked regardless of credentials. Sonatype kept an
 * OSSRH-compatible staging API on the Portal precisely so `maven-publish` setups
 * like this one keep working; a deployment lands there and is then released from
 * central.sonatype.com.
 *
 * Overridable so a future host change does not need a code edit.
 */
val centralStagingUrl: String = System.getenv("SONATYPE_STAGING_URL")
    ?: "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"

/**
 * Central requires signed artifacts, so credentials alone are not enough. All
 * four have to be present or the repository is not registered at all — a
 * half-configured Central publish that uploads unsigned artifacts fails late,
 * after the upload, which is the slowest way to find out.
 */
val publishToMavenCentral = !ossrhUsername.isNullOrBlank() &&
    !ossrhPassword.isNullOrBlank() &&
    !signingPrivateKey.isNullOrBlank() &&
    !signingPassword.isNullOrBlank()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = paygateGroupId
                artifactId = "paygate"
                version = sdkVersion
                from(components["release"])
                pom {
                    name.set("Paygate Android SDK")
                    description.set("Paywalls, server-driven flows, and Google Play Billing for Android.")
                    url.set("https://github.com/build-context/paygate")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("paygate")
                            name.set("Paygate")
                            // Central's POM validation wants a contactable
                            // developer, not just an id.
                            email.set("support@paygate.dev")
                            organization.set("Build Context")
                            organizationUrl.set("https://github.com/build-context")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/build-context/paygate.git")
                        developerConnection.set("scm:git:ssh://git@github.com/build-context/paygate.git")
                        url.set("https://github.com/build-context/paygate")
                    }
                }
            }
        }
        repositories {
            val ghRepo = System.getenv("GITHUB_REPOSITORY")
            val ghToken = System.getenv("GITHUB_TOKEN")
            if (!ghRepo.isNullOrBlank() && !ghToken.isNullOrBlank()) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$ghRepo")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")?.takeIf { it.isNotBlank() } ?: "git"
                        password = ghToken
                    }
                }
            }
            if (publishToMavenCentral) {
                maven {
                    name = "Sonatype"
                    url = uri(centralStagingUrl)
                    credentials {
                        // A Central Portal *user token*, not the account login.
                        // Generate it at central.sonatype.com under Account.
                        username = ossrhUsername
                        password = ossrhPassword
                    }
                }
            }
        }
    }

    signing {
        if (!signingPrivateKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(signingPrivateKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}
