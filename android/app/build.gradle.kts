import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Isti ključ za svaki build: Android odbija ažuriranje potpisano drugim ključem.
// GitHub Actions ga dobija iz tajni, a lokalno se čita iz ~/.config/snimac-poziva.
val potpis = Properties().apply {
    val fajl = File(System.getProperty("user.home"), ".config/snimac-poziva/potpis.properties")
    if (fajl.exists()) fajl.inputStream().use { load(it) }
}
fun potpisVrednost(env: String, kljuc: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: potpis.getProperty(kljuc)

val potpisFajl = potpisVrednost("POTPIS_FAJL", "fajl")
val potpisLozinka = potpisVrednost("POTPIS_LOZINKA", "lozinka")

// Broj izdanja dolazi iz GitHub Actions (run_number); lokalni build je 1.0.
val verzijaKod = (findProperty("verzijaKod") as String?)?.toIntOrNull() ?: 1
val verzijaIme = (findProperty("verzijaIme") as String?) ?: "1.0"

android {
    namespace = "studio.room211.pozivi"
    compileSdk = 34

    defaultConfig {
        applicationId = "studio.room211.pozivi"
        minSdk = 29
        targetSdk = 34
        versionCode = verzijaKod
        versionName = verzijaIme
    }

    signingConfigs {
        if (potpisFajl != null && potpisLozinka != null && File(potpisFajl).exists()) {
            create("izdanje") {
                storeFile = File(potpisFajl)
                storePassword = potpisLozinka
                keyAlias = "pozivi"
                keyPassword = potpisLozinka
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("izdanje") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
