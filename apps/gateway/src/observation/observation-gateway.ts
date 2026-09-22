import type {
  ObservationAnalyzer,
  ObservationProvider,
  ObservationRequest,
  ObservationResult,
} from "../../../../packages/providers/observation/observation-provider.ts";

export interface CapabilityRegistration {
  id: string;
  provider: string;
  consent: "explicit" | "preauthorized" | "not_applicable";
}

/** Registry-driven observation router; it contains no scenario state machine. */
export class ObservationGateway implements ObservationProvider {
  private readonly capabilities: Map<string, CapabilityRegistration>;
  private readonly analyzers: ReadonlyMap<string, ObservationAnalyzer>;

  constructor(
    registrations: CapabilityRegistration[],
    analyzers: ReadonlyMap<string, ObservationAnalyzer>,
  ) {
    this.capabilities = new Map(registrations.map((registration) => [registration.id, registration]));
    this.analyzers = analyzers;
  }

  supports(capabilityId: string): boolean {
    return this.capabilities.has(capabilityId) && this.analyzers.has(capabilityId);
  }

  async observe(request: ObservationRequest): Promise<ObservationResult> {
    const registration = this.capabilities.get(request.capability_id);
    const analyzer = this.analyzers.get(request.capability_id);
    if (!registration || !analyzer) return this.unsupported(request);

    if (registration.consent === "explicit" && request.consent !== "explicit") {
      return {
        ...this.identity(request),
        status: "failed",
        confidence: "unknown",
        needs_retake: false,
        summary: "缺少本次观察所需的明确授权。",
        facts: [],
        limitations: ["explicit_consent_required"],
      };
    }

    const result = await analyzer.analyze(request);
    return { ...this.identity(request), ...result, facts: result.facts ?? [] };
  }

  private identity(request: ObservationRequest) {
    return {
      schema_version: "1.0",
      session_id: request.session_id,
      request_id: request.request_id,
      capability_id: request.capability_id,
    };
  }

  private unsupported(request: ObservationRequest): ObservationResult {
    return {
      ...this.identity(request),
      status: "unsupported",
      confidence: "unknown",
      needs_retake: false,
      summary: "当前不支持此观察能力。",
      facts: [],
      limitations: ["capability_not_registered"],
    };
  }
}
