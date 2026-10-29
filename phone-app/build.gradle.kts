plugins { id("com.android.application") }
android {
    namespace = "com.example.privatelistening.phone"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.privatelistening.phone"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "0.2.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":common"))

    implementation("androidx.activity:activity-ktx:1.13.0")
    // 手机端使用与电视端相同版本的 Media3 Sonic，在 PCM 写入 AudioTrack 前做保持音调的微量时间伸缩。
    implementation("androidx.media3:media3-common:1.11.0")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")

    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}
