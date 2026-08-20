---
name: camera-streaming
description: Session and Stream capability setup, video frames, photo capture, resolution and frame rate configuration
---

# Camera Streaming (Android)

Use a `Session` and attached `Stream` to receive frames and capture photos.

## Key concepts

- **Session**: Device connection lifecycle created through `Wearables.createSession(...)`
- **Stream**: Camera stream accessed via `camera.stream` after attaching the camera with `session.addCamera(...)`
- **StreamConfiguration**: Resolution and frame rate configuration for the stream
- **PhotoData**: Still image captured from glasses while streaming

## Create a session and attach a stream

```kotlin
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector

val session = Wearables.createSession(AutoDeviceSelector()).getOrElse { error ->
    throw IllegalStateException(error.description)
}
session.start()

val camera: Camera = session.addCamera(
    StreamConfiguration(
        videoQuality = VideoQuality.MEDIUM,
        frameRate = 24,
    ),
).getOrElse { error ->
    throw IllegalStateException(error.description)
}

camera.stream.start().getOrElse { error ->
    throw IllegalStateException(error.description)
}
```

### Resolution options

| Quality | Size |
|---------|------|
| `VideoQuality.HIGH` | 720 x 1280 |
| `VideoQuality.MEDIUM` | 504 x 896 |
| `VideoQuality.LOW` | 360 x 640 |

### Frame rate options

Valid values: `2`, `7`, `15`, `24`, `30` FPS.

Lower resolution and frame rate usually produce better visual quality per frame over Bluetooth.

## Observe stream state

`StreamState` transitions: `STOPPED` -> `STARTING` -> `STARTED` -> `STREAMING` -> `STOPPING` -> `STOPPED` -> `CLOSED`

```kotlin
lifecycleScope.launch {
    camera.stream.state.collect { state ->
        when (state) {
            StreamState.STREAMING -> {
                // Frames are flowing
            }
            StreamState.STOPPED -> {
                // Streaming ended
            }
            StreamState.CLOSED -> {
                // Stream fully closed
            }
            else -> Unit
        }
    }
}
```

## Receive frames

```kotlin
lifecycleScope.launch {
    camera.stream.videoStream.collect { frame ->
        updatePreview(frame)
    }
}
```

## Capture a photo

```kotlin
lifecycleScope.launch {
    camera.stream.capturePhoto()
        .onSuccess { photoData ->
            val imageBytes = photoData.data
            savePhoto(imageBytes)
        }
        .onFailure { error, _ ->
            showCaptureError(error.description)
        }
}
```

## Clean up

Stop the stream when you no longer need camera data, then stop the parent session if the device interaction is finished.

```kotlin
camera.stop()
session.stop()
```

If you want to remove the capability entirely before re-adding it, call `session.removeCamera()`.

## Links

- [Android API reference](https://wearables.developer.meta.com/docs/reference/android/dat/latest)
- [Integration guide](https://wearables.developer.meta.com/docs/build-integration-android)
