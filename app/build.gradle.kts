plugins {
    id("com.android.application")
}

android {
    namespace = "com.nerv.magi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nerv.magi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 未配置签名时产出未签名包；签名请用 build.sh 或自行补 signingConfigs
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        // 字体是 9.8MB 的 OTF，压缩收益不大且解压更慢，直接不压缩。
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module")
        }
    }

    lint {
        abortOnError = false
    }
}

// ===========================================================================
// 注意：这里<故意>没有 dependencies { } 块。
//
// 整个 app 只用 Android framework（android.* / java.* / org.json），
// 三贤人面板是自绘 Canvas，设置页用 framework 的 Spinner/CheckBox/EditText，
// 主题继承 @android:style/Theme.Material.NoActionBar。
// 连带的好处是构建完全离线、且没有任何版本冲突的可能。
// ===========================================================================
