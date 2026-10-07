import { DeviceEventMapper, type RawGlassesEvent } from "../../../glasses-agent/src/device-event-mapper.ts";
import type { DeviceEvent } from "../../../../packages/providers/device/device-transport.ts";

/** The raw envelope the glasses transport forwards; the phone must not read it as business. */
export interface NativeDevicePayload {
  kind?: string;
  type?: string;
  payload?: string;
  reason?: string;
  weak?: boolean;
}

/**
 * Translates the bare transport events the glasses sends into the repository's DeviceEvent contract.
 *
 * The phone adapter is the only place that reads native field names: the eye keeps sending raw
 * transport events and knows nothing about sessions, orders or consent. Everything the shared
 * mapper can express goes through it, so a press, a failed capture and a disconnect mean the same
 * thing here as they do anywhere else in the repository. The voice/capture *window* tags are not
 * device events — they are the adapter's own bookkeeping and never reach the Agent as events.
 */
export function mapNativeDeviceEvent(
  mapper: DeviceEventMapper,
  sessionId: string,
  native: NativeDevicePayload,
): DeviceEvent | undefined {
  const raw = toRawGlassesEvent(native);
  return raw ? mapper.map(sessionId, raw) : undefined;
}

function toRawGlassesEvent(native: NativeDevicePayload): RawGlassesEvent | undefined {
  switch (native.type) {
    case "probe.glasses":
      // A press that the glasses did not turn into a recording or a capture. Long presses arrive as
      // user.cancel instead, so what is left here is the button the user pressed once.
      if (native.payload !== "sprite-button-click" && native.payload !== "screen-button") return undefined;
      return { kind: "button", buttonId: "glasses-primary", pressKind: "short" };
    case "photo.failed":
      return { kind: "capture_failed", requestId: "", errorCode: native.reason ?? "capture_failed", retryable: true };
    case "entrance.expired":
      return { kind: "capture_failed", requestId: "", errorCode: "capture_window_expired", retryable: true };
    case "audio.failed":
      return { kind: "speech_failed", effectId: "", errorCode: native.reason ?? "audio_failed", retryable: true };
    case "voice.expired":
      return { kind: "speech_failed", effectId: "", errorCode: "recorder_window_expired", retryable: true };
    default:
      return undefined;
  }
}

export function createDeviceEventMapper(options: { now?: () => Date; idFactory?: () => string } = {}): DeviceEventMapper {
  return new DeviceEventMapper(options);
}
