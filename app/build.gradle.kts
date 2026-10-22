plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.selfpilot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.selfpilot"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    
    // Room
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    
    // Navigation
    implementation(libs.nav.fragment)
    implementation(libs.nav.ui)
    
    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    
    // WorkManager
    implementation(libs.work.runtime)
    
    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // MPAndroidChart
    implementation(libs.mpandroidchart)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}