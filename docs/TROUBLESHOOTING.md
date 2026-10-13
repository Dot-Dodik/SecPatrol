# Troubleshooting

## Minimum supported Gradle version is 9.6.0

Hal ini berarti repository masih memakai Android Gradle Plugin 9.4. Pastikan file root `build.gradle.kts` memakai:

```kotlin
id("com.android.application") version "8.13.2" apply false
```

Workflow final memakai Gradle 8.13. Semua versi harus konsisten.

## Failed to find package platforms;android-37

Workflow lama memakai API 37 preview. Workflow final memakai:

```yaml
sdkmanager 'platforms;android-36' 'build-tools;35.0.0'
```

## Repository memiliki folder ganda

Log seperti `/SecPatrol/SecPatrol/app/` normal untuk nama workspace dan repository. Namun di halaman Code, pastikan langsung terlihat folder `app`, bukan folder pembungkus tambahan.

## Build masih menjalankan file lama

Pastikan perubahan dikomit ke branch `main`, lalu pada Actions jalankan ulang workflow dari commit terbaru.


## Using jvmTarget: String is an error

Kotlin 2.3 tidak lagi menerima konfigurasi lama `kotlinOptions { jvmTarget = "17" }`. Project final memakai DSL baru:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
```
