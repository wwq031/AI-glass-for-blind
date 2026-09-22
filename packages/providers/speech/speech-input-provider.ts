/** Normalized speech event matching speech-input.schema.json. */
export interface SpeechInput {
  schema_version: string;
  input_id: string;
  session_id: string;
  occurred_at: string;
  transcript: string;
  confidence: number;
  locale: string;
  source: "phone_mic" | "glasses_mic";
  is_final: boolean;
  intent_hint?:
    | "destination"
    | "confirmation"
    | "query"
    | "repeat"
    | "cancel"
    | "expression_request"
    | "unknown";
}

export type SpeechInputMode = "destination" | "command" | "dictation";

export interface ListeningHandle {
  id: string;
  session_id: string;
}

export type SpeechInputListener = (input: SpeechInput) => void;
export type Unsubscribe = () => void;

/** Provider seam for a phone, glasses, cloud, or fake ASR implementation. */
export interface SpeechInputProvider {
  start(sessionId: string, mode: SpeechInputMode): Promise<ListeningHandle>;
  stop(handle: ListeningHandle): Promise<void>;
  subscribe(listener: SpeechInputListener): Unsubscribe;
}
