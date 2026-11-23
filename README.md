# VideoEditing-

Native Android video editor built with Kotlin, Jetpack Compose and AndroidX Media3. Supports Android 10+ (API 29+) and uses the Android Photo Picker/Open Document picker rather than broad gallery permissions.

## Implemented

- Import and preview a video in a vertical 9:16 editor canvas that stays visible above the scrollable settings panel.
- Export H.264/MP4 at portrait 720×1280 using Media3 Transformer, targeting 1.2 Mbps video and 96 kbps encoded audio. Exported videos are saved to `Movies/VideoEditing` through `MediaStore`.
- Horizontal mirror, adjustable zoom, warm color and colored frame border. A dedicated saved scrolling-text card lets the user edit or clear the caption independently; the message is saved in app settings and restored when the app reopens.
- Named reusable editing presets: tap **Add**, enter a name, and save the current visual/audio adjustments, blur and logo placement, scrolling caption, and subtitle text/style. Choose a saved name to restore those settings for another edit. Presets are stored on the device; selected source video and audio files are not included.
- Includes the circular China AI logo as a built-in APK resource with transparent outside corners. It is applied by default; users can drag it on the preview (including partly past the frame edge), pinch with two fingers to resize, or use X/Y and size sliders or corner presets. Users may choose another PNG/JPG/GIF instead; custom logos persist in app-private storage and can be cleared to restore the built-in logo. Logo position and size are saved on-device and reloaded at startup; GIF logos loop in exports.
- Separate original-video and added-audio play/mute/volume controls; added audio can loop and mix with the video audio.
- Burmese auto-subtitles: after installing the APK, download the Nara SAR INT8 ONNX engine from Hugging Face inside the app. The selected added audio is decoded to 16 kHz mono on-device, transcribed offline, and inserted as timed editable subtitle cues. The engine is not bundled in the APK; the first download is about 366 MB and requires internet, while later transcription runs offline. Generated text should be reviewed before export.
- Draggable/resizable subtitle-cover box with adjustable opacity. Note: this is a translucent cover, not optical region-only blur.
- Paste/edit timed subtitle lines directly in the app or import `.txt`/`.srt`. Plain-text format: `[start - end] subtitle text`, for example `[00:00 - 00:02.5] မြစ်ကမ်းဘေးမှာ လမ်းလျှောက်လာတဲ့`. Supports `mm:ss`, `hh:mm:ss` and decimal seconds. Applying subtitles pauses and seeks the preview to zero so the first `00:00` cue is visible; the app uses every cue's own time in preview and export.
- Subtitles use clean text with no box or frame in a compact, centered, maximum two-line style. Drag the text directly on the preview to move it. Sliders provide size control from 8sp upward, saved color choices including black, an automatic contrasting text outline for readability, X/Y position and global timing delay. Export scales the selected font, color, line breaks and position from the measured preview canvas to 720p.

The app also supports editable timed text and `.txt`/`.srt` import. Nara SAR model source and attribution: https://huggingface.co/yelin201875/nara-sar (Apache-2.0; powered by Nara SAR and based on Meta's omniASR-CTC-300M).

## Build

Open the repository in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

The build creates one universal debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
