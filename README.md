# VideoEditing-

A Kotlin + Jetpack Compose Android project for choosing a video and managing a persistent custom logo watermark.

## Included in this build

- Uses Android Photo Picker (`PickVisualMedia`) to choose a video and a PNG/JPG logo without broad media-storage permissions.
- Copies the selected logo into the app-private `filesDir/custom-logo` folder and stores its path in `SharedPreferences`.
- Restores the saved logo when the app starts, previews it at the bottom-right of the 9:16 video canvas with edge padding and bounded proportional sizing, and provides **Change Logo** and **Remove Logo** actions.
- Shows the selected video in an embedded Media3 player.

The current APK implements video selection/playback and the persistent logo-watermark preview. It does **not yet render/export** the video with the watermark or implement the other editing effects in the original product brief (9:16 export, mirror/zoom, filter, blur box, audio replacement, and gallery export).

## Build

Open this folder in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
