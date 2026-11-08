/*
 * Sentence-mining feature for the NewPipe Mining fork.
 *
 * Kept in its own module so upstream merges only touch a few hook lines in :app.
 * Dependencies that NewPipe already pins come from the version catalog; the ones only this
 * module uses are declared here so libs.versions.toml stays identical to upstream.
 */

import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)
}

configure<LibraryExtension> {
    namespace = "org.schabi.newpipe.mining"
    compileSdk {
        version = release(NEWPIPE_VERSION_SDK_COMPILE_MAJOR) {
            minorApiLevel = NEWPIPE_VERSION_SDK_COMPILE_MINOR
        }
    }

    defaultConfig {
        minSdk {
            version = release(NEWPIPE_VERSION_SDK_MIN)
        }
        // Keep rules for the app's shrunk (release/continuous) builds.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // NewPipeExtractor needs NIO desugaring below API 33, same as :app.
        isCoreLibraryDesugaringEnabled = true
        encoding = "utf-8"
    }

    androidResources {
        // jmdict.db is copied out of the APK on first run; storing it uncompressed makes that fast.
        noCompress += "db"
    }
}

// The JMdict SQLite asset is generated, not committed (~31 MB). Fail early with instructions.
// Only APK packaging needs it; JVM unit tests run without it.
val jmdictAsset = layout.projectDirectory.file("src/main/assets/jmdict.db")
val checkJmdictAsset = tasks.register("checkJmdictAsset") {
    val asset = jmdictAsset.asFile
    doLast {
        if (!asset.exists()) {
            throw GradleException(
                "Missing $asset.\nGenerate it with: python3 mining/tools/build_jmdict.py"
            )
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(checkJmdictAsset)
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    // Pinned by NewPipe
    implementation(libs.newpipe.extractor)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.preference)
    implementation(libs.google.android.material)
    implementation(libs.google.exoplayer.core)
    implementation(libs.google.exoplayer.datasource)

    // Only used by mining
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
    implementation("com.google.android.exoplayer:exoplayer-transformer:${libs.versions.exoplayer.get()}")

    testImplementation(libs.junit)
}
