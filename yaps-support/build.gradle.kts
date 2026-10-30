plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val sdkVersion = "1.0.0"

// The protocol fixtures are the source of truth shared by every SDK. In the monorepo they're read
// from protocol/fixtures; the standalone published repo uses the copy in src/test/fixtures.
val monorepoFixtures = rootProject.layout.projectDirectory.dir("../../protocol/fixtures").asFile
val fixturesDir = if (monorepoFixtures.isDirectory) monorepoFixtures else project.file("src/test/fixtures")

android {
    namespace = "ai.yaps.support"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // Libraries ship unminified; consumer-rules.pro protects what the host's R8 must keep.
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.systemProperty("yaps.fixturesDir", fixturesDir.absolutePath)
            test.inputs.dir(fixturesDir).withPropertyName("protocolFixtures")
        }
    }
}

kotlin {
    explicitApi()
}

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Published from the public repo github.com/richawo/yaps-support-android through JitPack.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.richawo"
            artifactId = "yaps-support-android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Support Yaps for Android")
                description.set("In-app support chat for Android apps, with an inbox at support.yaps.ai.")
                url.set("https://support.yaps.ai/docs/android.md")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
