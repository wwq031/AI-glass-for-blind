import type {
  ObservationAnalyzer,
  ObservationRequest,
} from "../../../../packages/providers/observation/observation-provider.ts";

export interface OcrLine {
  text: string;
  confidence: number;
}

export interface OcrClient {
  recognize(mediaRef: string): Promise<OcrLine[]>;
}

/** Normalizes supplier-specific OCR lines into menu facts and OCR entries. */
export class OcrObservationAdapter implements ObservationAnalyzer {
  private readonly client: OcrClient;

  constructor(client: OcrClient) {
    this.client = client;
  }

  async analyze(request: ObservationRequest) {
    const lines = await this.client.recognize(request.media_refs[0]);
    const readable = lines.filter(({ text }) => text.trim());
    const average = readable.length
      ? readable.reduce((sum, line) => sum + line.confidence, 0) / readable.length
      : 0;
    const confidence = average >= 0.8 ? "high" : average >= 0.5 ? "medium" : "low";

    return {
      status: readable.length ? (average >= 0.5 ? "succeeded" : "partial") : "needs_retake",
      confidence,
      needs_retake: readable.length === 0,
      summary: readable.length ? `识别到${readable.length}行文字。` : "没有识别到清晰文字，请调整角度后重拍。",
      facts: readable.map((line, index) => ({
        name: "menu.text_line",
        value: line.text,
        confidence: line.confidence >= 0.8 ? "high" as const : line.confidence >= 0.5 ? "medium" as const : "low" as const,
        source: "ocr",
        evidence: [`line:${index + 1}`],
      })),
      ocr: readable.map((line) => ({ text: line.text, confidence: line.confidence })),
      limitations: readable.length ? [] : ["text_not_visible"],
    } as const;
  }
}
