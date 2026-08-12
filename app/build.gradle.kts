plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nathanhanapps.appdual"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nathanhanapps.appdual"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            aidl.directories.add("src/main/aidl")
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.shimmer)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
