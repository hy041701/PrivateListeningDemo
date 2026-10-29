plugins { id("com.android.application") }
android {
    namespace = "com.example.privatelistening.tv"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.privatelistening.tv"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "0.2.2"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    implementation(project(":common"))

    implementation("androidx.activity:activity-ktx:1.13.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    implementation("com.google.zxing:core:3.5.4")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}
