"""Local-first Novaingine controller API.

Run from the repository root:
    uvicorn integration.controller_server:app --host 127.0.0.1 --port 8000

Set DEVICE_TOKEN in the environment (or a .env file in the working directory).
This server intentionally binds to loopback in the documented command. Do not
expose it directly to the public internet. A hosted deployment needs TLS,
separate device/client credentials, OAuth, persistent queue storage and audit.
"""
from __future__ import annotations

import hmac
import os
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator

APP_VERSION = "0.2.0"
MAX_QUEUE = 100
MAX_COMMAND_AGE_SECONDS = 300
ALLOWED_OPERATIONS = {
    "device.get_status",
    "scene.inspect",
    "scene.select_object",
    "object.set_transform",
    "sculpt.create_mesh",
    "sculpt.stroke",
    "scene.undo",
    "scene.redo",
}

app = FastAPI(title="Novaingine Controller", version=APP_VERSION)
_lock = threading.RLock()
_commands: deque[dict[str, Any]] = deque()
_results: dict[str, dict[str, Any]] = {}
_device_status: dict[str, Any] = {}


def _device_token() -> str:
    return os.environ.get("DEVICE_TOKEN", "").strip()


def require_token(authorization: str | None = Header(default=None)) -> None:
    expected = _device_token()
    if len(expected) < 24:
        raise HTTPException(status_code=503, detail="DEVICE_TOKEN is not configured securely.")
    supplied = ""
    if authorization and authorization.startswith("Bearer "):
        supplied = authorization[7:].strip()
    if not supplied or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Invalid controller token.")


class Heartbeat(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocolVersion: int = Field(ge=1, le=1)
    deviceId: str = Field(min_length=8, max_length=128)
    deviceName: str = Field(default="Novaingine Android", max_length=100)
    platform: str = Field(default="android", max_length=30)
    appVersion: str = Field(default="unknown", max_length=30)


class CommandEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocolVersion: int = Field(ge=1, le=1)
    commandId: UUID
    deviceId: str = Field(min_length=8, max_length=128)
    issuedAt: datetime
    expiresAt: datetime
    operation: str
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("operation")
    @classmethod
    def allow_operation(cls, value: str) -> str:
        if value not in ALLOWED_OPERATIONS:
            raise ValueError("Operation is not allowlisted by this controller.")
        return value

    @field_validator("issuedAt", "expiresAt")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("Timestamps must include a timezone.")
        return value


def _clean_expired(now: datetime) -> None:
    kept = deque()
    while _commands:
        item = _commands.popleft()
        if datetime.fromisoformat(item["expiresAt"].replace("Z", "+00:00")) > now:
            kept.append(item)
    _commands.extend(kept)


@app.get("/health")
def health() -> dict[str, str]:
    return {"name": "Novaingine Controller", "status": "running", "version": APP_VERSION}


@app.post("/api/v1/device/heartbeat", dependencies=[Depends(require_token)])
def heartbeat(data: Heartbeat) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    with _lock:
        _device_status[data.deviceId] = {
            "deviceId": data.deviceId,
            "deviceName": data.deviceName,
            "platform": data.platform,
            "appVersion": data.appVersion,
            "protocolVersion": data.protocolVersion,
            "lastSeen": now,
            "online": True,
        }
    return {"ok": True, "deviceId": data.deviceId, "serverTime": now}


@app.get("/api/v1/device/commands/next", dependencies=[Depends(require_token)])
def next_command(deviceId: str = Query(min_length=8, max_length=128)) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    with _lock:
        _clean_expired(now)
        for index, command in enumerate(_commands):
            if command["deviceId"] == deviceId:
                del _commands[index]
                command["deliveredAt"] = now.isoformat()
                _results[command["commandId"]] = {
                    "status": "delivered",
                    "deviceId": deviceId,
                    "deliveredAt": now.isoformat(),
                }
                return {"command": command}
    return {"command": None}


@app.post("/api/v1/device/commands/{command_id}/result", dependencies=[Depends(require_token)])
def command_result(command_id: str, result: dict[str, Any]) -> dict[str, Any]:
    try:
        UUID(command_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid command ID.")
    if len(str(result).encode("utf-8")) > 16384:
        raise HTTPException(status_code=413, detail="Result is too large.")
    if result.get("commandId") != command_id:
        raise HTTPException(status_code=400, detail="Result commandId does not match URL.")
    with _lock:
        previous = _results.get(command_id)
        if previous is None:
            raise HTTPException(status_code=404, detail="Command was not issued by this controller.")
        if result.get("deviceId") != previous.get("deviceId"):
            raise HTTPException(status_code=403, detail="Result came from a different device.")
        _results[command_id] = {
            "status": "completed",
            "deviceId": result.get("deviceId"),
            "result": result,
            "receivedAt": datetime.now(timezone.utc).isoformat(),
        }
    return {"ok": True, "commandId": command_id}


@app.post("/api/v1/commands", dependencies=[Depends(require_token)])
def enqueue_command(data: CommandEnvelope) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    issued = data.issuedAt.astimezone(timezone.utc)
    expires = data.expiresAt.astimezone(timezone.utc)
    if issued > now.replace(microsecond=0) and (issued - now).total_seconds() > 30:
        raise HTTPException(status_code=422, detail="issuedAt is too far in the future.")
    if (now - issued).total_seconds() > MAX_COMMAND_AGE_SECONDS:
        raise HTTPException(status_code=422, detail="Command issue time is too old.")
    if expires <= now or expires > now.timestamp() + MAX_COMMAND_AGE_SECONDS:
        raise HTTPException(status_code=422, detail="Command expiry must be within the next five minutes.")
    if expires <= issued:
        raise HTTPException(status_code=422, detail="expiresAt must be after issuedAt.")
    command = data.model_dump(mode="json")
    command["commandId"] = str(data.commandId)
    with _lock:
        _clean_expired(now)
        if len(_commands) >= MAX_QUEUE:
            raise HTTPException(status_code=429, detail="Controller command queue is full.")
        if command["commandId"] in _results:
            raise HTTPException(status_code=409, detail="Command ID has already been used.")
        _commands.append(command)
        _results[command["commandId"]] = {
            "status": "queued",
            "deviceId": command["deviceId"],
            "queuedAt": now.isoformat(),
        }
    return {"accepted": True, "commandId": command["commandId"]}


@app.get("/api/v1/device/status", dependencies=[Depends(require_token)])
def device_status(deviceId: str = Query(min_length=8, max_length=128)) -> dict[str, Any]:
    with _lock:
        status = _device_status.get(deviceId)
        if status is None:
            return {"deviceId": deviceId, "online": False}
        snapshot = dict(status)
        last_seen = datetime.fromisoformat(snapshot["lastSeen"]).astimezone(timezone.utc)
        snapshot["online"] = (datetime.now(timezone.utc) - last_seen).total_seconds() <= 60
        return snapshot


@app.get("/api/v1/commands/{command_id}", dependencies=[Depends(require_token)])
def get_command_result(command_id: str) -> dict[str, Any]:
    try:
        UUID(command_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid command ID.")
    with _lock:
        item = _results.get(command_id)
        if item is None:
            raise HTTPException(status_code=404, detail="Unknown command ID.")
        return item
