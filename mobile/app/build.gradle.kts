import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置从本地 keystore.properties 读取（该文件不入库，见 README）
val keystoreProps = Properties().apply {
    val f = project.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storePassword") != null

android {
    namespace = "edu.hust.homeworklogistics"
    compileSdk = 37

    defaultConfig {
        applicationId = "edu.hust.homeworklogistics"
        minSdk = 26
        targetSdk = 37
        versionCode = 38
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = project.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
