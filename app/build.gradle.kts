plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Single place to bump the app version: major.minor.patch, versionCode = major*10000 + minor*100 + patch.
val appVersionName = "0.12.1"
val appVersionCode = appVersionName.split(".").let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }

android {
    namespace = "com.lsa.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.taehunkim.nunsori"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing: the key lives outside the repo (see docs/development.md, "Release build").
    // Without these properties (e.g. a fresh clone) `assembleRelease` still builds, but the APK is unsigned.
    fun signingProp(name: String): String? = (project.findProperty(name) as String?) ?: System.getenv(name)
    val releaseStore = signingProp("NUNSORI_STORE_FILE")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = signingProp("NUNSORI_STORE_PASSWORD")
            keyAlias = signingProp("NUNSORI_KEY_ALIAS")
            keyPassword = signingProp("NUNSORI_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    androidResources { generateLocaleConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Room migration tests read the exported schemas.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// Licenses shown in Settings › About: generated from the repo-root files so there is one source (git-ignored output).
val licensesAsset = tasks.register("generateLicensesAsset") {
    val notices = rootProject.file("THIRD_PARTY_NOTICES.md")
    val license = rootProject.file("LICENSE")
    val out = file("src/main/assets/licenses.txt")
    inputs.files(notices, license)
    outputs.file(out)
    doLast { out.writeText(notices.readText() + "\n\n----------------------------------------\n\n" + license.readText()) }
}
tasks.named("preBuild") { dependsOn(licensesAsset) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.androidsvg)
    // AI models run in the OndolSaem app; this is its client SDK (JitPack, or ../ondolsaem as a composite build).
    implementation(libs.ondolsaem.sdk)
    // The AIDL contract Nunsori serves to other apps (the same module they use as a client SDK).
    implementation(project(":sdk"))
    implementation(libs.kotlinx.coroutines.android)
    // Room reads its schema bundle with kotlinx.serialization; an older runtime pulled in transitively breaks migration tests.
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser implementation for JVM unit tests
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
