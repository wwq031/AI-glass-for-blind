import type {
  DeviceCommand,
  DeviceEvent,
  DeviceEventListener,
  DeviceTransport,
  MediaTransfer,
  SendResult,
  TransportStatus,
  Unsubscribe,
} from "../providers/device/device-transport.ts";

/** In-memory transport for domain and end-to-end scenario tests. */
export class SimulatorTransport implements DeviceTransport {
  readonly commands: DeviceCommand[] = [];
  readonly transfers: MediaTransfer[] = [];
  private readonly listeners = new Set<DeviceEventListener>();
  private connected = false;

  async connect(): Promise<TransportStatus> {
    this.connected = true;
    return { connected: true, transport: "simulator" };
  }

  async disconnect(): Promise<void> {
    this.connected = false;
  }

  async sendCommand(command: DeviceCommand): Promise<SendResult> {
    this.requireConnection();
    this.commands.push(structuredClone(command));
    return { accepted: true, id: command.command_id };
  }

  async sendBinary(transfer: MediaTransfer): Promise<SendResult> {
    this.requireConnection();
    this.transfers.push(structuredClone(transfer));
    return { accepted: true, id: transfer.media_id };
  }

  subscribe(listener: DeviceEventListener): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  inject(event: DeviceEvent): void {
    for (const listener of this.listeners) listener(structuredClone(event));
  }

  get status(): TransportStatus {
    return { connected: this.connected, transport: "simulator" };
  }

  private requireConnection(): void {
    if (!this.connected) throw new Error("SimulatorTransport is disconnected");
  }
}
