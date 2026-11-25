plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.audiotv.player"
 compileSdk = 35
 defaultConfig { applicationId = "com.audiotv.player"; minSdk = 23; targetSdk = 32; versionCode = 2; versionName = "0.2" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.media3:media3-exoplayer:1.5.1") }
