export type DeviceCommand = {
  schema_version: string;
  command_id: string;
  session_id: string;
  type: "capture.requested" | "capture.cancelled" | "speech.play" | "speech.cancel" | "haptic.prompt";
  issued_at: string;
  expires_at?: string;
  payload: Record<string, unknown>;
};

export type DeviceEvent = {
  schema_version: string;
  event_id: string;
  session_id: string;
  occurred_at: string;
  type:
    | "device.connected"
    | "device.disconnected"
    | "button.pressed"
    | "capture.completed"
    | "capture.failed"
    | "speech.playback_completed"
    | "speech.playback_failed";
  payload: Record<string, unknown>;
};

export interface MediaTransfer {
  schema_version: string;
  media_id: string;
  session_id: string;
  kind: "image" | "audio";
  content_type: string;
  size_bytes: number;
  sha256: string;
  captured_at: string;
  expires_at: string;
  ref: string;
  width?: number;
  height?: number;
}

export interface TransportStatus {
  connected: boolean;
  transport: string;
}

export interface SendResult {
  accepted: boolean;
  id: string;
}

export type DeviceEventListener = (event: DeviceEvent) => void;
export type Unsubscribe = () => void;

/** The only business-layer seam between phone and glasses. */
export interface DeviceTransport {
  connect(): Promise<TransportStatus>;
  disconnect(): Promise<void>;
  sendCommand(command: DeviceCommand): Promise<SendResult>;
  sendBinary(transfer: MediaTransfer): Promise<SendResult>;
  subscribe(listener: DeviceEventListener): Unsubscribe;
}
