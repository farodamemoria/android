"""HTTP API for the LifeSense AI mobile companion."""

from __future__ import annotations

import base64
import binascii
import os
from collections.abc import Iterable

import httpx
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel, Field

MAX_IMAGE_BYTES = 5 * 1024 * 1024
OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
DEFAULT_MODEL = "gpt-4.1-mini"

app = FastAPI(title="LifeSense AI API", version="0.1.0")


class HealthResponse(BaseModel):
    status: str


class AnalyzeImageRequest(BaseModel):
    image_base64: str = Field(min_length=1, description="JPEG or PNG image encoded as base64.")
    prompt: str = Field(
        default="Describe what is immediately in front of me.",
        min_length=1,
        max_length=500,
    )


class AnalyzeImageResponse(BaseModel):
    answer: str


def decode_image(image_base64: str) -> bytes:
    try:
        image = base64.b64decode(image_base64, validate=True)
    except binascii.Error as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="image_base64 must be valid base64.",
        ) from error

    if not image:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The image cannot be empty.",
        )
    if len(image) > MAX_IMAGE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="The image exceeds the 5 MiB limit.",
        )
    return image


def output_text(output: Iterable[object]) -> str:
    for item in output:
        if not isinstance(item, dict):
            continue
        for content in item.get("content", []):
            if isinstance(content, dict) and content.get("type") == "output_text":
                text = content.get("text")
                if isinstance(text, str) and text.strip():
                    return text.strip()
    raise HTTPException(
        status_code=status.HTTP_502_BAD_GATEWAY,
        detail="The AI service returned no text response.",
    )


@app.get("/healthz", response_model=HealthResponse)
async def healthz() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/v1/vision/analyze", response_model=AnalyzeImageResponse)
async def analyze_image(request: AnalyzeImageRequest) -> AnalyzeImageResponse:
    image = decode_image(request.image_base64)
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Visual analysis is not configured.",
        )

    encoded_image = base64.b64encode(image).decode("ascii")
    payload = {
        "model": os.environ.get("OPENAI_MODEL", DEFAULT_MODEL),
        "instructions": (
            "You are LifeSense, a calm assistant for a person with memory difficulties. "
            "Describe the visible scene in simple, reassuring language. Do not identify people, "
            "make medical claims, or give emergency instructions. If uncertain, say so plainly."
        ),
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": request.prompt},
                    {
                        "type": "input_image",
                        "image_url": f"data:image/jpeg;base64,{encoded_image}",
                    },
                ],
            }
        ],
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(OPENAI_RESPONSES_URL, headers=headers, json=payload)
    except httpx.RequestError as error:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI service could not be reached.",
        ) from error

    if response.status_code >= 400:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI service rejected the request.",
        )

    response_body = response.json()
    output = response_body.get("output")
    if not isinstance(output, list):
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI service returned an invalid response.",
        )
    return AnalyzeImageResponse(answer=output_text(output))
