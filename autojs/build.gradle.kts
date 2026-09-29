plugins {
    id("com.android.library")
    id("kotlin-android")
//    id("kotlin-android-extensions")
}

android {
    buildToolsVersion = versions.buildTool
    compileSdk = versions.compile

    defaultConfig {
        minSdk = versions.mini
        targetSdk = versions.target
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lintOptions.isAbortOnError = false
    sourceSets {
        named("main") {
//            jniLibs.srcDirs = listOf("src/main/jniLibs")
            res.srcDirs("src/main/res","src/main/res-i18n")
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1-alpha01"){
        exclude(group = "com.android.support",module = "support-annotations")
    }
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.preference:preference-ktx:1.2.0")
    api("org.greenrobot:eventbus:3.3.1")
    api("net.lingala.zip4j:zip4j:1.3.2")
    api("com.afollestad.material-dialogs:core:0.9.2.3"){
        exclude(group = "com.android.support")
    }
    api("com.google.android.material:material:1.9.0-alpha01")
    api("com.github.hyb1996:EnhancedFloaty:0.31")
    api("com.makeramen:roundedimageview:2.3.0")
    // OkHttp
    api("com.squareup.okhttp3:okhttp:4.10.0")
    // JDeferred
    api("org.jdeferred:jdeferred-android-aar:1.2.6")
    // RootShell（JitPack 的 com.github.Stericson:RootShell:1.6 已 404，改用本地 AAR 模块）
    api(project(path = ":LocalRepo:RootShell"))
    // Gson
    api("com.google.code.gson:gson:2.10")
    // log4j
    api(group = "de.mindpipe.android", name = "android-logging-log4j", version = "1.0.3")
    api(group = "log4j", name = "log4j", version = "1.2.17")
    api(project(path = ":common"))
    api(project(path = ":automator"))
    api(project(path = ":LocalRepo:libtermexec"))
    api(project(path = ":LocalRepo:emulatorview"))
    api(project(path = ":LocalRepo:term"))
    api(project(path = ":LocalRepo:p7zip"))
    api(project(path = ":LocalRepo:OpenCV"))
    api(project(":paddleocr"))
    // libs
    api(fileTree("../app/libs"){include("dx.jar", "rhino-1.7.14-jdk7.jar")})
    //【依赖来源说明】此库只发布在 JitPack（Maven Central 上不存在 cz.adaptech 这个 group，
    // 官方 README 也只给 JitPack 的接入方式），且本项目用的 4.1.1 是老版本。
    // JitPack 对久未被拉取的老版本是惰性重建的：首次请求会返回 500（而非 404），
    // 后台重建完成后再请求同一 URL 即可正常下载（实测重建后 4 秒下完，11.9MB）。
    // 所以遇到 500 不要误判为"依赖已下架"，稍等重试，或先查构建状态：
    //   https://jitpack.io/api/builds/cz.adaptech/tesseract4android   （4.1.1 应为 "ok"）
    // 若 JitPack 长期不可用，可手动下载 aar+pom 放进本地 maven 仓库兜底
    // （mavenLocal() 已在仓库列表首位）：
    //   https://jitpack.io/cz/adaptech/tesseract4android/4.1.1/tesseract4android-4.1.1.{aar,pom}
    //   → ~/.m2/repository/cz/adaptech/tesseract4android/4.1.1/
    api("cz.adaptech:tesseract4android:4.1.1")
    api("com.google.mlkit:text-recognition:16.0.0-beta6")
    api("com.google.mlkit:text-recognition-chinese:16.0.0-beta6")
    api("com.google.mlkit:text-recognition-devanagari:16.0.0-beta6")
    api("com.google.mlkit:text-recognition-japanese:16.0.0-beta6")
    api("com.google.mlkit:text-recognition-korean:16.0.0-beta6")
}

