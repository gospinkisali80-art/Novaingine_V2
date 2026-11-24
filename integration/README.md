# Novaingine Remote AI Integration

This directory defines the first, isolated foundation for connecting an external AI client (including a supported ChatGPT custom MCP connection) to Novaingine on Android and Windows.

## Status

**Local Android dispatcher foundation added; remote transport is not connected yet.** This branch contains the protocol contract, an isolated Android command dispatcher, and a GitHub Actions Android debug-build workflow. No network listener, secure pairing UI/storage, gateway, Windows agent, or ChatGPT connector is active. The Android build has not yet been confirmed successful.

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

The current Android dispatcher is `RemoteCommandDispatcher.java`. It is intentionally not wired into app startup and cannot receive network commands yet. It accepts typed protocol-v1 JSON from a future authenticated transport, validates the paired device ID, expiry, replay IDs and operation payload bounds, then queues engine work on the GL render thread. Supported local operations: status, scene inspection, object selection (with existence check), selected-object transform (with selection check), SculptMesh creation, sculpt strokes, undo and redo. Project save and generic primitive creation are intentionally withheld until safe path handling and the engine's primitive enum contract are verified. The renderer queue now cancels commands that have not started when its initial wait times out; an already-started operation receives an additional bounded wait. A GitHub Actions workflow is configured for pull requests, manual dispatch, and pushes to this integration branch. A successful build is still pending.

## Existing engine integration points

The Android app already has useful engine APIs in `EngineSurface` and `NativeBridge`, including object selection/transforms, primitive creation, sculpt mesh creation, sculpt operations, scene inspection, undo/redo, and project save/load. The remote agent should call those existing methods rather than duplicating native engine logic.
