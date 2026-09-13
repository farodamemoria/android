# CONTEXTO — App de gafas (Faro) · Meta Wearables DAT

> Documento de contexto para asistentes de IA (opencode / DeepSeek) y desarrolladores.
> **Lee este archivo completo antes de actuar.** Repo: https://github.com/farodamemoria/android
> **NO contiene secretos**: solo indica dónde están.

---

## 1. Qué es este repositorio

Contiene la **app Android de las gafas Meta (Ray-Ban)** del proyecto **Faro da Memoria**, construida sobre el **Meta Wearables Device Access Toolkit (DAT) para Android**.

> ⚠️ **Ramas importantes:**
> - **`main`** = SDK de Meta (upstream) + apps de ejemplo (`CameraAccess`, `DisplayAccess`).
> - **`farodamemoria-replace-repository-content`** = **la app Faro real** (el sample `CameraAccess` customizado) **+ el backend LifeSense AI**. **Aquí está el trabajo de Faro.**

---

## 2. Componentes

- **`samples/CameraAccess`** = **app Faro (gafas)**: registro con Meta AI, sesión, streaming de cámara (HEVC), captura de foto/vídeo, micrófono (`stream/AudioInputHandler.kt`) y análisis de imagen (`assistant/AssistantApi.kt` → LifeSense).
- **`samples/DisplayAccess`** = ejemplo de pantalla para gafas con display.
- **`backend/`** = **LifeSense AI** (FastAPI): `GET /healthz` y `POST /v1/vision/analyze` (envía la foto a la **OpenAI Responses API**; la clave vive en el servidor).
- **`plugins/`, `.cursor/`, `.github/`, `AGENTS.md`, `CHANGELOG.md`** = ayuda para asistentes de IA y documentación del SDK.

---

## 3. Relación con el resto del proyecto

- El portal **Faro Familia** y el backend **AURA Care** están en el repo **`aura-care`** (ver su `CONTEXT.md`).
- Portal en producción: https://d2n7ih9kfxbzvd.cloudfront.net
- La app de gafas **reconoce personas**; la familia las gestiona en Faro Familia (**Personas conocidas / Por aclarar**).
- **Faro Móvil** (KAN-44): versión para conversar con cámara/micrófono del teléfono sin gafas.

---

## 4. Enlaces

- Repo: https://github.com/farodamemoria/android
- Guía DAT: https://wearables.developer.meta.com/docs/develop/
- Referencia API Android: https://wearables.developer.meta.com/docs/reference/android/dat/latest
- Docs DAT (MCP, sin auth): https://mcp.developer.meta.com/wearables
- App de Meta: **farodamemoria** (App ID `1592113852645672`)
- Jira (proyecto KAN): https://farodamemoria.atlassian.net

---

## 5. Build y pruebas

- Requisitos: **JDK 17**, **Android Studio**, Android SDK 36+.
- `local.properties`: `github_token=<PAT con scope read:packages>` (repositorio Maven de Meta) y `assistant_api_base_url=https://<lifesense-api>` para el análisis de imagen.
- Comandos: `./gradlew assembleDebug`, `./gradlew test`, `./gradlew lint`.
- **Sin gafas**: usar **MockDeviceKit** (menú **Debug** del sample `CameraAccess`) para simular dispositivo, permisos y cámara.
- En `AndroidManifest.xml`: `mwdat_application_id` y `mwdat_client_token` (en Developer Mode pueden ser `0`).

---

## 6. Estado actual

- La app de gafas es un **MVP** basado en el sample `CameraAccess` + LifeSense.
- Tareas Jira relacionadas (ver tablero KAN): KAN-30 (segundo plano/reconexión de gafas), KAN-44 (Faro Móvil), KAN-68 (incidencia cámara Faro Móvil), KAN-71 (calidad del preview), etc.
- **Pendiente**: confirmar/asegurar la integración de la app de gafas con el backend **AURA Care** (reconocimientos → Faro Familia).

---

## 7. Secretos (dónde están — nunca en Git)

- Token de GitHub (`read:packages`): en `local.properties` (local) o variable de entorno `GITHUB_TOKEN`.
- Claves de OpenAI: en la EC2 (`/etc/faro/realtime.env`, `/etc/aura-backend/openai.env`).
- El `.gitignore` excluye `local.properties`, keystores y builds.

---

## 8. Cómo arrancar (para la IA)

> "Lee `CONTEXT.md` para tener el contexto de la app de gafas."

Y para el contexto global del producto (portal, backend, plataformas y tareas), lee el `CONTEXT.md` del repo **`aura-care`**.
