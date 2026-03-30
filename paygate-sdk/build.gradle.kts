plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val sdkVersion = rootProject.file("../VERSION").readText().trim()

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
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}

val ossrhUsername = System.getenv("OSSRH_USERNAME")
val ossrhPassword = System.getenv("OSSRH_PASSWORD")
val signingPrivateKey = System.getenv("SIGNING_PRIVATE_KEY")
val signingPassword = System.getenv("SIGNING_PASSWORD")
val sonatypeHost = System.getenv("SONATYPE_HOST")?.takeIf { it == "s01" || it == "oss" } ?: "s01"
val publishToMavenCentral = !ossrhUsername.isNullOrBlank() &&
    !ossrhPassword.isNullOrBlank() &&
    !signingPrivateKey.isNullOrBlank() &&
    !signingPassword.isNullOrBlank()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.paygate"
                artifactId = "paygate-sdk"
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
                    url = uri("https://$sonatypeHost.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    credentials {
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
