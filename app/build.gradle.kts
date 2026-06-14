import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()

if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
} else {
    versionProps["VERSION_CODE"] = "1"
    versionProps["VERSION_NAME"] = "1.0.0"
}

val vCode = versionProps["VERSION_CODE"].toString().toInt()
val vName = versionProps["VERSION_NAME"].toString()

tasks.register("incrementVersion") {
    doLast {
        val nextCode = vCode + 1
        val parts = vName.split(".")
        val nextName = if (parts.size >= 3) {
            "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}"
        } else {
            "$vName.1"
        }
        versionProps["VERSION_CODE"] = nextCode.toString()
        versionProps["VERSION_NAME"] = nextName
        versionProps.store(FileOutputStream(versionPropsFile), null)
        println("Version incremented to $nextName ($nextCode)")
    }
}

tasks.matching { it.name.startsWith("assemble") }.all {
    dependsOn("incrementVersion")
}

android {
    namespace = "com.creplaz.newslistener"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.creplaz.newslistener"
        minSdk = 24
        targetSdk = 36
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    @Suppress("UnstableApiUsage")
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val name = "creplaz-${variant.name}.apk"
                (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(name)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.jsoup)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}