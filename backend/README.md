# LifeSense AI backend

This service accepts an intentional image capture from the Android app and sends it to OpenAI's
Responses API. The OpenAI key stays on the server; it is never embedded in the Android app.

## Local run

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

Set `OPENAI_API_KEY` in the environment before starting the API:

```powershell
$env:OPENAI_API_KEY = "<your key>"
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

The liveness probe is `GET /healthz`. `POST /v1/vision/analyze` accepts a JSON body with
`image_base64` and an optional `prompt`. Images are limited to 5 MiB. The production deployment
must sit behind HTTPS and authenticate requests from the mobile app before exposing this endpoint.
