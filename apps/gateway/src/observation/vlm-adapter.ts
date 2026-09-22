import type {
  Confidence,
  Fact,
  ObservationAnalyzer,
  ObservationRequest,
} from "../../../../packages/providers/observation/observation-provider.ts";

export interface VlmAnalysis {
  summary: string;
  confidence: Confidence;
  facts: Fact[];
  risks?: Fact[];
  limitations?: string[];
}

export interface VlmClient {
  analyze(capabilityId: string, mediaRefs: string[], context?: Record<string, unknown>): Promise<VlmAnalysis>;
}

/** Keeps raw VLM clients behind the shared structured-fact result. */
export class VlmObservationAdapter implements ObservationAnalyzer {
  private readonly client: VlmClient;

  constructor(client: VlmClient) {
    this.client = client;
  }

  async analyze(request: ObservationRequest) {
    const analysis = await this.client.analyze(request.capability_id, request.media_refs, request.context);
    const needsRetake = analysis.facts.length === 0 || analysis.confidence === "low" || analysis.confidence === "unknown";
    return {
      status: needsRetake ? "needs_retake" as const : "succeeded" as const,
      confidence: analysis.confidence,
      needs_retake: needsRetake,
      summary: analysis.summary,
      facts: analysis.facts,
      risks: analysis.risks,
      limitations: analysis.limitations ?? [],
    };
  }
}
