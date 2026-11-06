plugins { id("com.android.application") }

android {
    namespace = "com.littyl.knock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.littyl.knock"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
