import type { AgentToolCall, ToolGateway } from "../domain/agent/tool-gateway.ts";
import type { ToolResult } from "../domain/agent/types.ts";

export class RecordedToolGateway implements ToolGateway {
  private readonly results: ToolResult[];
  readonly calls: AgentToolCall[] = [];

  constructor(results: ToolResult[]) {
    this.results = structuredClone(results);
  }

  async execute(input: AgentToolCall): Promise<ToolResult> {
    this.calls.push(structuredClone(input));
    const result = this.results.shift();
    if (!result) throw new Error("Recorded gateway has no remaining result");
    return structuredClone(result);
  }
}
