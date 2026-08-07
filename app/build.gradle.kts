plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.boffin.vmforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.boffin.vmforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // qemu-system-aarch64 binary and supporting files are arm64-only
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // v0.1: no dedicated release keystore yet, so release builds are
            // signed with the auto-generated debug keystore for now — this
            // lets CI-built APKs install directly on a phone.
            // (a proper keystore will be needed for production/Play Store)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // qemu-system-aarch64 must exist as an actual extracted file on disk
    // to be exec'd via ProcessBuilder — AGP's default (uncompressed,
    // mmap'd directly from inside the APK) doesn't leave a real file at
    // nativeLibraryDir, so force legacy (extract-to-disk) packaging:
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // qemu-system-aarch64, kernel image, etc. will go here:
    // app/src/main/assets/qemu/
    // app/src/main/jniLibs/arm64-v8a/  (QEMU renamed to a .so so the
    //   Android/Play system scanner doesn't block the executable)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    // For extracting PRoot rootfs tarballs (tar.gz) picked via the file
    // importer — no bundled `tar` binary, so this is done in Kotlin.
}
