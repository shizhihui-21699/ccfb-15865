plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.tv.paygate"
    compileSdk = 35

    defaultConfig {
        minSdk = 23 // 覆盖主流存量电视盒子
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 加密存储信任窗口
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 网络层与二维码（二维码在 TV 本地渲染，内容为一次性深链）
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
