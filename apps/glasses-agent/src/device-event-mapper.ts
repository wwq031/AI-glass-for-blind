import type { DeviceEvent } from "../../../packages/providers/device/device-transport.ts";

export type RawGlassesEvent =
  | { kind: "button"; buttonId: string; pressKind: "short" | "long" | "double" }
  | { kind: "capture_completed"; requestId: string; mediaId: string }
  | { kind: "capture_failed"; requestId: string; errorCode: string; retryable: boolean }
  | { kind: "speech_completed"; effectId: string }
  | { kind: "speech_failed"; effectId: string; errorCode: string; retryable: boolean }
  | { kind: "disconnected"; reason: string; retryable: boolean };

export interface DeviceEventMapperOptions {
  now?: () => Date;
  idFactory?: () => string;
}

/** Keeps JSUI/CXR-shaped events out of the shared business layer. */
export class DeviceEventMapper {
  private readonly now: () => Date;
  private readonly idFactory: () => string;

  constructor(options: DeviceEventMapperOptions = {}) {
    this.now = options.now ?? (() => new Date());
    this.idFactory = options.idFactory ?? (() => crypto.randomUUID());
  }

  map(sessionId: string, raw: RawGlassesEvent): DeviceEvent {
    const base = {
      schema_version: "1.0",
      event_id: this.idFactory(),
      session_id: sessionId,
      occurred_at: this.now().toISOString(),
    };

    switch (raw.kind) {
      case "button":
        return { ...base, type: "button.pressed", payload: { button_id: raw.buttonId, press_kind: raw.pressKind } };
      case "capture_completed":
        return { ...base, type: "capture.completed", payload: { request_id: raw.requestId, media_id: raw.mediaId } };
      case "capture_failed":
        return {
          ...base,
          type: "capture.failed",
          payload: { request_id: raw.requestId, error_code: raw.errorCode, retryable: raw.retryable },
        };
      case "speech_completed":
        return { ...base, type: "speech.playback_completed", payload: { effect_id: raw.effectId } };
      case "speech_failed":
        return {
          ...base,
          type: "speech.playback_failed",
          payload: { effect_id: raw.effectId, error_code: raw.errorCode, retryable: raw.retryable },
        };
      case "disconnected":
        return { ...base, type: "device.disconnected", payload: { reason: raw.reason, retryable: raw.retryable } };
    }
  }
}
