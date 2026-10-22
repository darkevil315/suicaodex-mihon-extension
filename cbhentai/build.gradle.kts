import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ----------------------------------------------------------------------------------
// Extension metadata for CBHentai
// ----------------------------------------------------------------------------------
val extName = "CBHentai"
val pkgNameSuffix = "vi.hentaicube"
val extClass = ".HentaiCB"
val extVersionCode = 107003
val extVersionName = "1.6.103"
val libVersion = "1.6"

val tachiyomixVersion = "1.6.0"

android {
    namespace = "eu.kanade.tachiyomi.extension.$pkgNameSuffix"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.$pkgNameSuffix"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = extVersionName

        manifestPlaceholders["appName"] = "$extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["contentWarning"] = "2" // 0=Safe, 1=Mixed, 2=NSFW
        manifestPlaceholders["extensionLib"] = libVersion
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    var hasReleaseKeystore = false
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
        hasReleaseKeystore = true
    } else if (!System.getenv("CI_KEYSTORE_PATH").isNullOrEmpty()) {
        keystoreProperties["storeFile"] = System.getenv("CI_KEYSTORE_PATH")
        keystoreProperties["storePassword"] = System.getenv("CI_KEYSTORE_PASSWORD")
        keystoreProperties["keyAlias"] = System.getenv("CI_KEY_ALIAS")
        keystoreProperties["keyPassword"] = System.getenv("CI_KEY_PASSWORD")
        hasReleaseKeystore = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.github.mihonapp:tachiyomix:$tachiyomixVersion")
    compileOnly("com.github.mihonapp:injekt:91edab2317")
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("org.jsoup:jsoup:1.22.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("androidx.preference:preference:1.2.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
