---
name: dat-conventions
description: Kotlin patterns, DatResult, session and capability conventions for DAT SDK Android development
---

# DAT SDK Conventions (Android)

## Quick Reference

| Task | Command |
|------|---------|
| Build app | `./gradlew assembleDebug` |
| Run tests | `./gradlew test` |
| Install app | `./gradlew installDebug` |
| Lint app | `./gradlew lint` |

## Architecture

The SDK is organized into four public modules:

- **mwdat-core**: Registration, permissions, devices, and session creation
- **mwdat-camera**: Stream capability, video frames, and photo capture
- **mwdat-display**: Display capability, display UI components, icons, images, buttons, and video
- **mwdat-mockdevice**: MockDeviceKit for testing without hardware

### Initialization and session setup

```kotlin
Wearables.initialize(context)

val session = Wearables.createSession(AutoDeviceSelector()).getOrElse { error ->
    throw IllegalStateException(error.description)
}
session.start()

val camera = session.addCamera(StreamConfiguration()).getOrElse { error ->
    throw IllegalStateException(error.description)
}
camera.stream.start().getOrElse { error ->
    throw IllegalStateException(error.description)
}
```

## Kotlin patterns

- Use `DatResult<T, E>` for typed success and failure handling
- Observe state with `StateFlow` and `Flow`
- Create a `Session` first, then attach capabilities such as `Stream` or `Display`
- Keep frame handling off the main thread when doing heavier processing

## Error handling

```kotlin
Wearables.checkPermissionStatus(Permission.CAMERA)
    .onSuccess { status -> /* handle status */ }
    .onFailure { error, _ -> /* handle error */ }
```

Avoid `getOrThrow()` in user-facing samples. Surface typed errors from `DatResult` instead.

## Naming conventions

| Type | Purpose | Example |
|------|---------|---------|
| `Session` | Device connection lifecycle | `Wearables.createSession(...)` |
| `Stream` | Camera capability on a session | `session.addCamera(...)` → `camera.stream` |
| `Display` | Display capability on a session | `session.addDisplay(...)` |
| `*Selector` | Device targeting | `AutoDeviceSelector` |
| `*Error` | Typed failure surface | `SessionError`, `StreamError` |

## Key types

- `Wearables` — SDK entry point
- `Session` — lifecycle for an interaction with a linked device
- `Stream` — camera capability attached to a session
- `Display` — display capability attached to a session
- `StreamConfiguration` — video quality and frame rate configuration
- `MockDeviceKit` — simulated device environment for testing

## Live docs search

If your editor supports remote MCP servers, connect `https://mcp.developer.meta.com/wearables` and use `search_dat_docs` for current DAT setup, session lifecycle, camera streaming, MockDeviceKit, permissions, and exact API symbols. This public docs server does not require authentication; do not configure tokens, OAuth, or custom authorization headers for it.

Use `llms.txt` when your tool only supports static reference context.

## Testing with MockDeviceKit

```kotlin
val mockDeviceKit = MockDeviceKit.getInstance(context)
mockDeviceKit.enable()
val device = mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
```

Use MockDeviceKit to drive registration, device availability, streaming media, and permission scenarios without physical hardware.

## Common pitfalls

- Do not call SDK APIs before `Wearables.initialize(context)`
- Do not assume a session implies streaming or display access; capabilities are attached separately
- Do not ignore `DatResult` failures from `createSession`, `start`, `addCamera`, `addDisplay`, or `capturePhoto`
- Do not reuse terminally stopped sessions

## Links

- [Android API reference](https://wearables.developer.meta.com/docs/reference/android/dat/latest)
- [Developer documentation](https://wearables.developer.meta.com/docs/develop/)
- [GitHub repository](https://github.com/facebook/meta-wearables-dat-android)
