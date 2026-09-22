export type Confidence = "high" | "medium" | "low" | "unknown";

export interface Fact {
  name: string;
  value: unknown;
  confidence: Confidence;
  valid_until?: string;
  source?: string;
  evidence?: string[];
}

export interface ObservationRequest {
  schema_version: string;
  session_id: string;
  request_id: string;
  capability_id: string;
  trigger: "user_button" | "reminder_confirmed" | "future_authorized_policy";
  media_refs: string[];
  consent: "explicit" | "preauthorized" | "not_applicable";
  user_intent?: string;
  context?: Record<string, unknown>;
  policy?: Record<string, unknown>;
}

export interface ObservationResult {
  schema_version: string;
  session_id: string;
  request_id: string;
  capability_id: string;
  status: "succeeded" | "partial" | "needs_retake" | "failed" | "unsupported";
  confidence: Confidence;
  needs_retake: boolean;
  summary: string;
  facts: Fact[];
  risks?: Fact[];
  entities?: Record<string, unknown>[];
  ocr?: Record<string, unknown>[];
  limitations?: string[];
  fresh_until?: string;
}

export interface ObservationProvider {
  supports(capabilityId: string): boolean;
  observe(request: ObservationRequest): Promise<ObservationResult>;
}

export interface ObservationAnalyzer {
  analyze(request: ObservationRequest): Promise<Omit<ObservationResult, "schema_version" | "session_id" | "request_id" | "capability_id">>;
}
