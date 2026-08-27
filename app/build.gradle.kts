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

    signingConfigs {
        create("release") {
            // Dedicated release keystore — apps signed with the shared,
            // publicly-known debug key appear to get stricter sandboxing
            // (including exec restrictions) on some hardened ROMs (MIUI
            // and similar), which may be the actual cause of PRoot's
            // execve() failures persisting even in "release" builds that
            // were still debug-signed. This is a throwaway key for
            // personal/dev use — replace with a real keystore before ever
            // publishing anywhere.
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
    // Used to parse and re-stream PRoot rootfs tarballs (tar.gz), filtering
    // out device/FIFO entries before handing off to the bundled busybox
    // `tar` for actual extraction — see RootfsImporter for why.
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9") // commons-compress needs this for some compression formats
}
