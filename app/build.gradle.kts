import java.util.Properties
import java.io.FileInputStream

plugins { id("com.android.application") }

// runway-signing — shared upload key from Watchtower secrets/
val rwKeystoreFile = file("/home/harsha/watchtower/secrets/keystore.properties")
val rwKeystore = Properties().apply { if (rwKeystoreFile.exists()) load(FileInputStream(rwKeystoreFile)) }

android {
    namespace = "com.littyl.knock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.littyl.knock"
        minSdk = 30
        targetSdk = 36
        versionCode = (project.findProperty("RW_CODE") as String?)?.toInt() ?: 6  // runway-versioning
        versionName = (project.findProperty("RW_NAME") as String?) ?: "0.4.0"  // runway-versioning
    }
    signingConfigs {  // runway-signing
        if (rwKeystoreFile.exists()) create("release") {
            storeFile = file(rwKeystore.getProperty("storeFile"))
            storePassword = rwKeystore.getProperty("storePassword")
            keyAlias = rwKeystore.getProperty("keyAlias")
            keyPassword = rwKeystore.getProperty("keyPassword")
        }
    }


    buildTypes {
        release {
            if (rwKeystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
