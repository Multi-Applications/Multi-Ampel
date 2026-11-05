<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Multi Ampel Icon" width="128" />
</p>

<h1 align="center">Multi Ampel</h1>

<p align="center">
  Turn your Android device into a fullscreen traffic light simulation for playing
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B-1D82F5?style=flat&logo=android&logoColor=white" alt="Android 8.0+"></a>
  <a href="https://github.com/Multi-Applications/Multi-Ampel/releases"><img src="https://img.shields.io/badge/Version-26.1-1D82F5?style=flat" alt="Version 26.1"></a>
</p>

> **IMPORTANT NOTICE**
>
> Multi Ampel is just a simulation. It is **not** a traffic control system and must **never** be used to control real road traffic! Always follow local laws and safety regulations.

## Information

Multi Ampel simulates a realistic traffic light with cycle directly on your screen.
Its intended for children to play with, giving them the feeling of a real traffic light.

## Features

- No account, no ads, no tracking, and no internet connection required
- Material You dynamic colors and light/dark theme
- English and german full language support
- Realistic traffic light simulation for playing
- Adjustable phase duration from 3 to 30 seconds
- Double tap lock and unlock to freeze the current phase
- Bluetooth connection with other devices to create traffic light circuits
- Settings bottom sheet
- Maximum screen brightness while the app is open

## Bluetooth Circuits

Several devices can be connected into one synchronized traffic light circuit over Bluetooth Low Energy. The app scans for nearby devices that has the Multi Ampel app and also lists devices that are already paired in the Android Bluetooth settings.

| Circuit type | Devices
| --- | ---:
| Construction site light | 2
| T junction | 3
| Plus crossing | 4

## Building

Requirements:

- JDK 17 or newer
- Android SDK Platform 35
- Android SDK Build-Tools

Clone the repository and run the release build from the repository root:

```bash
./gradlew assembleRelease
```

On Windows use `gradlew.bat assembleRelease` instead. The signed APK is written to:

```
app/build/outputs/apk/release/app-release.apk
```

Release signing is configured through `keystore.properties` in the repository root.