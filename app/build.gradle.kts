import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pagefold.reader"
    compileSdk = 35
    buildToolsVersion = "34.0.4"

    defaultConfig {
        applicationId = "com.pagefold.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 120
        versionName = "4.0"
    }

    /*
     * 签名参数不进仓库。
     *
     * 优先读根目录 keystore.properties（已在 .gitignore 里），其次读 -P 传进来的同名属性。
     * 两处都没有就不注册 release 签名 —— 那样的 release 包是未签名的，装不上，
     * 但配置阶段不会因为少一个属性就整棵树构建失败。别人的机器上 clone 下来能编。
     */
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(name: String): String? =
        (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: keyProps.getProperty(name)?.takeIf { it.isNotBlank() }

    val storePath = secret("PF_STORE_FILE")
    signingConfigs {
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = secret("PF_STORE_PASSWORD")
                keyAlias = secret("PF_KEY_ALIAS")
                keyPassword = secret("PF_KEY_PASSWORD")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}
