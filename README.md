# VideoEditing-

Native Android video editor built with Kotlin, Jetpack Compose and AndroidX Media3. Supports Android 10+ (API 29+) and uses the Android Photo Picker/Open Document picker rather than broad gallery permissions.

## Implemented

- Import and preview a video in a vertical 9:16 editor canvas that stays visible above the scrollable settings panel.
- Export H.264/MP4 at portrait 720×1280 using Media3 Transformer, targeting 1.2 Mbps video and 96 kbps encoded audio. Exported videos are saved to `Movies/VideoEditing` through `MediaStore`.
- Horizontal mirror, adjustable zoom, warm color, colored frame border and scrolling caption.
- Custom PNG/JPG/GIF logo, crop, persistent app-private storage, movable/resizable placement, animated GIF loop and reset controls.
- Separate original-video and added-audio play/mute/volume controls; added audio can loop and mix with the video audio.
- Draggable/resizable subtitle-cover box with adjustable opacity. Note: this is a translucent cover, not optical region-only blur.
- Paste/edit timed subtitle lines directly in the app or import `.txt`/`.srt`. Plain-text format: `[start - end] subtitle text`, for example `[00:00 - 00:02.5] မြစ်ကမ်းဘေးမှာ လမ်းလျှောက်လာတဲ့`. Supports `mm:ss`, `hh:mm:ss` and decimal seconds. Apply the lines and the app places each subtitle at its specified time in preview and exported MP4.
- Adjust all subtitle timing with a global delay control, and change subtitle size and vertical position. To edit individual words or timestamps, edit the corresponding line in the text field and apply again.

Automatic speech-to-text/model downloading has been removed. Subtitles are supplied by the user as editable timed text or an SRT file.

## Build

Open the repository in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

The build creates one universal debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
