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

        // qemu-system-aarch64 বাইনারি ও সাপোর্টিং ফাইল ARM64 ডিভাইসের জন্যই
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // v0.1: আলাদা রিলিজ কিস্টোর এখনো বানানো হয়নি, তাই আপাতত
            // অটো-জেনারেটেড debug keystore দিয়েই সাইন করা হচ্ছে —
            // এতে CI-তে বিল্ড হওয়া APK সরাসরি ফোনে ইনস্টল করা যাবে
            // (production/Play Store-এর জন্য পরে আলাদা কিস্টোর লাগবে)
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

    // qemu-system-aarch64, kernel image, ইত্যাদি বাইনারি এসেট এখানে যাবে:
    // app/src/main/assets/qemu/
    // app/src/main/jniLibs/arm64-v8a/  (QEMU-কে .so হিসেবে রিনেম করে রাখলে
    //   Android Play/system স্ক্যানার এক্সিকিউটেবল ব্লক করে না)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
}
