# Upload ke GitHub dan Build APK

## Struktur yang benar

File `settings.gradle.kts`, `build.gradle.kts`, folder `app`, `.github`, `docs`, dan `backend-apps-script` harus langsung berada di root repository.

Jangan membuat struktur ganda seperti `SecPatrol/SecPatrol/app`.

## Upload melalui web

1. Ekstrak ZIP.
2. Buka folder `SecPatrol-Final`.
3. Pilih seluruh isi folder, termasuk `.github` dan `.gitignore`.
4. Drag ke halaman `Add file > Upload files` pada repository.
5. Commit ke branch `main`.

## Upload melalui Git

```bash
git init
git branch -M main
git remote add origin https://github.com/Dodik-Dot/SecPatrol.git
git add .
git commit -m "Install SecPatrol trial 0.1.1"
git push -u origin main
```

## Build

Push ke `main` otomatis menjalankan workflow. APK tersedia sebagai artifact `SecPatrol-debug-apk`.
