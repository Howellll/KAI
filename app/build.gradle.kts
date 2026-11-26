plugins {
    id("com.android.application")
}

/** Ключ подписи релизов: из переменных окружения (GitHub Actions) или из ~/.gradle/gradle.properties. */
fun signing(env: String, prop: String): String? =
    System.getenv(env) ?: project.findProperty(prop) as String?

val releaseKeystore = signing("KLAVA_KEYSTORE", "klava.keystore")

android {
    namespace = "com.kai.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kai.keyboard"
        minSdk = 26
        targetSdk = 36
        // В GitHub Actions версия берётся из тега (v1.2 → 1.2), номер сборки — из номера запуска.
        // Локальные сборки: 1.0.2 — текущая версия приложения.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3
        versionName = System.getenv("GITHUB_REF_NAME")?.removePrefix("v") ?: "1.0.3"
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = signing("KLAVA_KEYSTORE_PASSWORD", "klava.keystorePassword")
            keyAlias = signing("KLAVA_KEY_ALIAS", "klava.keyAlias") ?: "klava"
            keyPassword = signing("KLAVA_KEY_PASSWORD", "klava.keyPassword") ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Есть ключ релизов — подписываем им. Нет (локальная сборка) — debug-ключом,
            // чтобы APK можно было сразу поставить; публичные релизы так не собираются (см. release.yml).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("dict")
        // Маскот в разделе «О приложении»: без сжатия декодируется сразу и не тратит память на распаковку.
        noCompress += "png"
    }
}

dependencies {
    // Единственная сторонняя библиотека: HTTP-клиент для необязательных функций ИИ.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // В юнит-тестах заглушка android.jar не разбирает JSON — подключаем настоящий org.json (только для тестов).
    testImplementation("org.json:json:20240303")
}

// Тесты читают словари и эмодзи прямо из assets — пусть Gradle перезапускает их, когда данные меняются.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets")
    inputs.files(fileTree("../tools") { include("*_50k.txt") }).optional()
}
