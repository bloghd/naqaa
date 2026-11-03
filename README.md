# Naqaa (نقاء)

An Android app (Flutter + Kotlin) to download videos and audio from many websites, with an optional on-device feature that removes music and keeps the human voice only.

## Features
- Download video or audio from sites supported by yt-dlp, with quality selection
- Download queue: pause, resume, cancel; jobs run one after another in the background with notifications
- "Wi-Fi only" mode and a default quality setting
- Share a link from any app into Naqaa, or paste it (a link found in the clipboard is suggested automatically)
- Downloads history: play, share, delete
- Remove music: pick any video or audio file; the AI model runs on the phone (no internet needed after installing it); output as video or audio only; optional "stronger removal"
- Manual update of the download engine

## How it works
- Download engine: youtubedl-android (yt-dlp + ffmpeg)
- Music removal: HT-Demucs (vocals stem) exported to ONNX and run with ONNX Runtime on the device. The ~316 MB model is downloaded once from Hugging Face when the user installs the component.
- UI: Flutter (Arabic, right-to-left)

## Build
Requirements: Flutter SDK, Android SDK (platform 37), the NDK/CMake that Flutter asks for, JDK 17 or newer.

Debug build:

    flutter pub get
    flutter build apk --debug

Release signing: the release build reads its keystore settings from the keyPropsFile path in android/app/build.gradle.kts, which points to the author's machine. Create your own keystore and change that path. Never commit keystores or key.properties.

The same file also disables the CMake/CXX Gradle tasks (needed to build on a phone inside ACSIDE). Remove that block if you build on a regular computer and need those tasks.

## Responsible use
Use Naqaa only for content you have the right to download. You are responsible for following the terms of the websites you use and the copyright laws in your country.

## License
GPL-3.0, see the LICENSE file. This is required because the app bundles youtubedl-android (GPL-3.0) and an FFmpeg build that uses GPL options.

## Third-party components
yt-dlp (Unlicense), youtubedl-android (GPL-3.0), FFmpeg (GPL build), ONNX Runtime (MIT), HT-Demucs and its ONNX export (MIT), Flutter (BSD-3-Clause).

---

## بالعربي
نقاء تطبيق أندرويد لتحميل الفيديو والصوت من مواقع كثيرة، مع ميزة اختيارية لإزالة الموسيقى من أي فيديو أو ملف صوتي وإبقاء الصوت البشري فقط، وتعمل على جهازك نفسه بدون إنترنت بعد تثبيت المكوّن مرة واحدة.
