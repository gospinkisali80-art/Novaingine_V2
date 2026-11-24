# Novaingine Remote AI Integration

This directory defines the first, isolated foundation for connecting an external AI client (including a supported ChatGPT custom MCP connection) to Novaingine on Android and Windows.

## Status

**Android outbound-agent prototype and local authenticated controller API added.** The Android editor now has an AI Link pairing dialog, Android Keystore-encrypted token storage, a background HTTP poller, and lifecycle cleanup. The local FastAPI controller exposes heartbeat, command polling, command submission, and result endpoints. This is a same-device development bridge only; it is not a hosted gateway or a ChatGPT MCP connector. The Android build and on-device loopback connection still require testing.

## Architecture

```text
ChatGPT / other AI client
        |
        | MCP over HTTPS (OAuth 2.1 + PKCE)
        v
Hosted Novaingine Gateway
        |
        | outbound authenticated device session
        +---------------------+
        |                     |
        v                     v
Android Agent            Windows Agent
        |                     |
        v                     v
EngineSurface / JNI      Novaingine desktop adapter
        |                     |
        v                     v
Android renderer         Desktop editor/runtime
```

The devices initiate outbound connections. Do not expose an Android or Windows editor port directly to the public internet. The gateway must authenticate the AI client and each paired device independently.

## Protocol

- `protocol-v1.json` is the canonical command envelope and payload schema.
- Every command has a unique `commandId`, a paired `deviceId`, an allowlisted `operation`, and an explicit `payload`.
- Agents validate schema, bounds, target device, expiry, and replay/idempotency before dispatch.
- Scene mutations run on the engine's owning thread. On Android, renderer/native operations must be queued through `EngineSurface.queueEvent` or existing methods that already do so.
- The current Android dispatcher returns structured `succeeded` or `failed` results. It does not yet implement asynchronous `accepted`/`running` states. It must never report success before the engine confirms the operation.
- Destructive actions (clear/reset/delete/overwrite) require a separate confirmation step in the UI and are not included in the initial remote command allowlist.
- Credentials and API keys are never placed in command payloads or committed to this repository.

## Initial command surface

The first implementation should expose only bounded, auditable operations:

- `device.get_status`, `scene.inspect`
- `scene.select_object`
- `object.create_primitive`, `object.set_transform`
- `sculpt.create_mesh`, `sculpt.stroke`
- `scene.undo`, `scene.redo`
- `project.save`

Material editing, import/export, physics, animation, visual scripting, and desktop-specific operations can be added after their adapters and result semantics are verified. Do not implement a generic "execute code", shell, arbitrary JNI, or arbitrary file path tool.

## Pairing and authentication requirements

1. Pair devices using a short-lived, single-use pairing code displayed by Novaingine.
2. Store device credentials in Android Keystore / Windows Credential Manager, not source code or plain preferences.
3. Give each device a revocable identity and narrowly scoped permissions.
4. Use TLS for every gateway connection; rotate/revoke credentials and expire sessions.
5. The MCP server must implement the OAuth 2.1 flow expected by the AI client. App-level Firebase sign-in alone is not a substitute for MCP OAuth.
6. Keep audit records of command ID, device ID, operation, timestamps, and outcome. Do not log prompts, secrets, or private project contents by default.

## Compatibility note

A custom MCP connector with write-capable tools is not available in every ChatGPT plan or client. The bridge can also support a separate Novaingine AI client, but that would be a separate chat experience—not this exact ChatGPT conversation.

## Next implementation milestones

1. Complete Android agent lifecycle, secure pairing storage, and local transport tests.
2. Windows agent using the same protocol.
3. Hosted gateway with device pairing, outbound sessions, command queue, and audit log.
4. OAuth-protected MCP server exposing the allowlisted tools.
5. End-to-end tests against a test scene, then opt-in release behind a setting.

The Android transport is `ControllerAgent.java`; the local FastAPI implementation is `controller_server.py`. Tap the viewport status label in the editor to open AI Link pairing. For local testing, the default URL is `http://127.0.0.1:8001`. The token is encrypted with Android Keystore and is not embedded in the APK. The agent sends heartbeats every 20 seconds, polls every 2 seconds, and dispatches received commands through `RemoteCommandDispatcher.java`. It stops when the editor activity pauses or is destroyed. It is not an always-on background service. It accepts typed protocol-v1 JSON from a future authenticated transport, validates the paired device ID, expiry, replay IDs and operation payload bounds, then queues engine work on the GL render thread. Supported local operations: status, scene inspection, object selection (with existence check), selected-object transform (with selection check), SculptMesh creation, sculpt strokes, undo and redo. Project save and generic primitive creation are intentionally withheld until safe path handling and the engine's primitive enum contract are verified. The renderer queue now cancels commands that have not started when its initial wait times out; an already-started operation receives an additional bounded wait. A GitHub Actions workflow is configured for pull requests, manual dispatch, and pushes to this integration branch. A successful build is still pending.

## Existing engine integration points

The Android app already has useful engine APIs in `EngineSurface` and `NativeBridge`, including object selection/transforms, primitive creation, sculpt mesh creation, sculpt operations, scene inspection, undo/redo, and project save/load. The remote agent should call those existing methods rather than duplicating native engine logic.


## Local controller API (development only)

The API contract used by `ControllerAgent` is:

- `POST /api/v1/device/heartbeat` — authenticated device heartbeat.
- `GET /api/v1/device/commands/next?deviceId=...` — authenticated long-poll substitute; returns `{"command": null}` when empty.
- `POST /api/v1/device/commands/{commandId}/result` — authenticated command result.
- `POST /api/v1/commands` — enqueue a protocol-v1 command for a paired device.
- `GET /api/v1/commands/{commandId}` — inspect command delivery/result state.
- `GET /api/v1/device/status?deviceId=...` — inspect device heartbeat state.

The local server implementation is `integration/controller_server.py`. For the existing Termux setup, download this file into `~/NovaingineController` and run it on port 8001 so it does not replace the original server on port 8000:

```sh
cd ~/NovaingineController
curl -L "https://raw.githubusercontent.com/gospinkisali80-art/Novaingine_V2/integration/chatgpt-bridge-foundation/integration/controller_server.py" -o controller_server.py
set -a
. ./.env
set +a
uvicorn controller_server:app --host 127.0.0.1 --port 8001
```

This reads the existing private `DEVICE_TOKEN` from the Termux `.env` file without displaying it. Keep the server bound to loopback only (`127.0.0.1`), and enter the same private token in the Android pairing dialog. It is an in-memory development queue: pending commands/results are lost on server restart. Do not expose it using port forwarding or a public tunnel. A production deployment requires TLS, separate device and AI-client credentials, OAuth 2.1 for MCP, persistent storage, rate limiting, audit retention, pairing/revocation, and hardened authorization.

The initial API uses one development bearer token for local testing. Never paste that token into ChatGPT, GitHub, screenshots, issue comments, or source code. Use a separate token for each device before any multi-device or hosted deployment.
