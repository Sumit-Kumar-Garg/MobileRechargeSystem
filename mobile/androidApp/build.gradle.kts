plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Firebase is optional so the project builds and runs without a google-services.json.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Where the debug build finds the backend. Default: the emulator's alias for this computer.
// A real phone: run `adb reverse tcp:8080 tcp:8080` and build with -PapiBaseUrl=http://localhost:8080/
val apiBaseUrl: String = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:8080/"

android {
    namespace = "com.app.recharge.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.app.recharge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "BASE_URL", "\"https://api.example.com/\"")   // set the real API host
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))          // all screens and logic
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
