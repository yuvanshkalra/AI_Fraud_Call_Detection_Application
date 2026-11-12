plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.fraudcalldetector.domain"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // javax.inject: compileOnly so :domain stays framework-free.
    // Hilt (and the standalone javax.inject artifact on the classpath of every
    // consumer module) satisfies this at runtime automatically.
    compileOnly(libs.javax.inject)

    implementation(libs.coroutines.core)
}
