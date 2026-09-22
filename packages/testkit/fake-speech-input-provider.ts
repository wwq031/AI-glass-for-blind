import type {
  ListeningHandle,
  SpeechInput,
  SpeechInputListener,
  SpeechInputMode,
  SpeechInputProvider,
  Unsubscribe,
} from "../providers/speech/speech-input-provider.ts";

export interface FakeSpeechInputOptions {
  transcript: string;
  confidence?: number;
  locale?: string;
  source?: SpeechInput["source"];
  now?: () => Date;
  idFactory?: () => string;
}

/** Deterministic ASR replacement for demos and scenario tests. */
export class FakeSpeechInputProvider implements SpeechInputProvider {
  private readonly listeners = new Set<SpeechInputListener>();
  private readonly options: Required<FakeSpeechInputOptions>;
  private activeHandle?: ListeningHandle;

  constructor(options: FakeSpeechInputOptions) {
    const transcript = options.transcript.trim();
    if (!transcript) throw new Error("Fake speech input requires a non-empty transcript");

    this.options = {
      transcript,
      confidence: options.confidence ?? 1,
      locale: options.locale ?? "zh-CN",
      source: options.source ?? "phone_mic",
      now: options.now ?? (() => new Date()),
      idFactory: options.idFactory ?? (() => crypto.randomUUID()),
    };
  }

  async start(sessionId: string, mode: SpeechInputMode): Promise<ListeningHandle> {
    if (this.activeHandle) throw new Error("Speech input is already listening");

    const handle = { id: this.options.idFactory(), session_id: sessionId };
    this.activeHandle = handle;
    const intent_hint = mode === "destination" ? "destination" : "unknown";

    queueMicrotask(() => {
      if (this.activeHandle !== handle) return;
      const input: SpeechInput = {
        schema_version: "1.0",
        input_id: this.options.idFactory(),
        session_id: sessionId,
        occurred_at: this.options.now().toISOString(),
        transcript: this.options.transcript,
        confidence: this.options.confidence,
        locale: this.options.locale,
        source: this.options.source,
        is_final: true,
        intent_hint,
      };
      for (const listener of this.listeners) listener(input);
    });

    return handle;
  }

  async stop(handle: ListeningHandle): Promise<void> {
    if (this.activeHandle?.id === handle.id) this.activeHandle = undefined;
  }

  subscribe(listener: SpeechInputListener): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}
