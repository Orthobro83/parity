import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin business logic: money, parsing, FX math, list classification, QR framing, CSV.
// No Android or Compose dependencies. iOS targets are added in Phase 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    compilerOptions {
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.bignum)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.zxing.core)
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
