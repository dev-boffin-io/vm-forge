plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.boffin.vmforge"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.boffin.vmforge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // qemu-system-aarch64 binary and supporting files are arm64-only
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Dedicated release keystore — apps signed with the shared,
            // publicly-known debug key appear to get stricter sandboxing
            // (including exec restrictions) on some hardened ROMs (MIUI
            // and similar). This is a throwaway key for personal/dev use —
            // replace with a real keystore before ever publishing anywhere.
            storeFile = file("release.keystore")
            storePassword = "vmforge123"
            keyAlias = "vmforge"
            keyPassword = "vmforge123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // qemu-system-aarch64 must exist as an actual extracted file on disk
    // to be exec'd via ProcessBuilder — AGP's default (uncompressed,
    // mmap'd directly from inside the APK) doesn't leave a real file at
    // nativeLibraryDir, so force legacy (extract-to-disk) packaging. The
    // PRoot core (libproot.so/libloader.so) is exec'd from the same dir, so
    // this also guarantees the PRoot loaders are real files on disk.
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
    implementation(project(":core:main"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}