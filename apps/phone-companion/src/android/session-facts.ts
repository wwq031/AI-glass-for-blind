import type { ToolResult } from "../../../../packages/domain/agent/types.ts";

export interface SessionFact {
  /** Identity inside the store: one entry per recorded fact, even when two share a name. */
  id: string;
  /** The observation batch this fact arrived in; a newer batch supersedes the older one. */
  batchId: string;
  name: string;
  value: unknown;
  confidence: "high" | "medium" | "low" | "unknown";
  validUntil?: string;
  source: string;
  /** What the producing adapter pointed at, so two same-name lines stay distinguishable. */
  evidence?: string[];
  recordedAt: string;
}

/** What a caller hands over; identity and time are the store's to assign. */
export type SessionFactInput = Omit<SessionFact, "id" | "batchId" | "recordedAt">;

/** The scopes `facts.query` declares in facts-query-tool-input.schema.json. */
export type FactsQueryScope = "current_session" | "recent_observation" | "navigation_context";

const SCOPES: readonly FactsQueryScope[] = ["current_session", "recent_observation", "navigation_context"];

/** Navigation context is whatever the route reported, not what a camera observed. */
function isNavigationSource(source: string): boolean {
  return source === "navigation" || source === "device.navigation" ||
    source.startsWith("navigation.") || source.startsWith("native.navigation");
}

function isObservationSource(source: string): boolean {
  return source === "vision" || source === "ocr" || source.startsWith("observation");
}

/**
 * The facts a session actually established, kept so a later question can be answered from evidence
 * instead of a guess. Only tool results and navigation reports enter it; nothing here is inferred.
 * A fact is dropped when it expires, and the store is bounded per session, so "what do I know" can
 * never grow into a transcript of everything the device ever saw.
 *
 * Recording is per batch. One observation can legitimately carry several facts of the same name —
 * a menu is read line by line, and every line is a claim with its own evidence — so a batch keeps
 * all of them, while the batch that follows replaces the claims it supersedes. `record` stays what
 * a scalar navigation fact needs: one live claim per name.
 */
export class SessionFactStore {
  private readonly facts = new Map<string, SessionFact[]>();
  /** The newest batch of any kind, and the newest observation batch, per session. */
  private readonly latestBatch = new Map<string, string>();
  private readonly latestObservationBatch = new Map<string, string>();
  private batchSequence = 0;

  private readonly options: { limit?: number; now?: () => Date };

  constructor(options: { limit?: number; now?: () => Date } = {}) {
    this.options = options;
  }

  /** Records one scalar claim: a newer claim of the same name replaces the one it supersedes. */
  record(sessionId: string, fact: SessionFactInput): void {
    const batchId = this.newBatchId();
    this.replace(sessionId, [fact.name]);
    this.append(sessionId, batchId, [fact]);
    this.latestBatch.set(sessionId, batchId);
  }

  /**
   * Records every fact one observation produced, as one batch. Same-name facts of that batch all
   * survive with their own source and evidence; claims of older batches for the same names are
   * replaced. `source` is the capability that produced them (`observation:vision.menu`).
   */
  recordBatch(sessionId: string, facts: readonly SessionFactInput[], source: string): void {
    if (facts.length === 0) return;
    const batchId = this.newBatchId();
    this.replace(sessionId, facts.map((fact) => fact.name));
    this.append(sessionId, batchId, facts, source);
    this.latestBatch.set(sessionId, batchId);
    if (isObservationSource(source)) this.latestObservationBatch.set(sessionId, batchId);
  }

  /** Records every fact a ToolResult carried, once per result, tagged with the tool that produced it. */
  recordResult(result: ToolResult): void {
    if (result.status !== "succeeded" && result.status !== "partial") return;
    if (result.facts.length === 0) return;
    this.recordBatch(
      result.sessionId,
      result.facts.map((fact) => ({
        name: fact.name,
        value: fact.value,
        confidence: fact.confidence,
        ...(fact.validUntil ? { validUntil: fact.validUntil } : {}),
        source: result.toolId,
      })),
      result.toolId,
    );
  }

  /**
   * Names may be exact, or a prefix ending in `*` (`traffic_signal.*`). The scope narrows what is
   * returned without changing what was recorded: the whole live session, the newest observation
   * batch alone, or only what the navigation context reported. An unrecognised scope is read as
   * the default rather than as a licence to return more.
   */
  query(
    sessionId: string,
    names: readonly string[],
    limit: number,
    scope: FactsQueryScope = "current_session",
  ): SessionFact[] {
    const now = (this.options.now ?? (() => new Date()))().getTime();
    const live = (this.facts.get(sessionId) ?? [])
      .filter((fact) => !fact.validUntil || Date.parse(fact.validUntil) > now);
    const scoped = this.withinScope(sessionId, live, SCOPES.includes(scope) ? scope : "current_session");
    return scoped
      .filter((fact) => names.some((name) =>
        name.endsWith("*") ? fact.name.startsWith(name.slice(0, -1)) : fact.name === name))
      .slice(0, limit)
      .map((fact) => structuredClone(fact));
  }

  clear(sessionId: string): void {
    this.facts.delete(sessionId);
    this.latestBatch.delete(sessionId);
    this.latestObservationBatch.delete(sessionId);
  }

  private withinScope(sessionId: string, live: SessionFact[], scope: FactsQueryScope): SessionFact[] {
    if (scope === "navigation_context") return live.filter((fact) => isNavigationSource(fact.source));
    if (scope !== "recent_observation") return live;
    // The newest observation batch; a session that only ever recorded navigation has no such batch.
    const batchId = this.latestObservationBatch.get(sessionId);
    if (!batchId) return [];
    return live.filter((fact) => fact.batchId === batchId);
  }

  /** Drops the claims a new batch supersedes — the same names, from the batches before it. */
  private replace(sessionId: string, names: readonly string[]): void {
    const superseded = new Set(names);
    const list = this.facts.get(sessionId);
    if (!list) return;
    this.facts.set(sessionId, list.filter((item) => !superseded.has(item.name)));
  }

  private append(
    sessionId: string,
    batchId: string,
    facts: readonly SessionFactInput[],
    sourceOverride?: string,
  ): void {
    const recordedAt = (this.options.now ?? (() => new Date()))().toISOString();
    const list = this.facts.get(sessionId) ?? [];
    const entries = facts.map((fact, index): SessionFact => ({
      ...fact,
      ...(sourceOverride ? { source: sourceOverride } : {}),
      id: `${batchId}#${index}`,
      batchId,
      recordedAt,
    }));
    const limit = Math.max(1, this.options.limit ?? 32);
    // A batch is kept whole when it fits; a batch bigger than the store keeps its newest entries, so
    // one oversized observation can never evict itself or the claims that came before it silently.
    this.facts.set(sessionId, [...list, ...entries].slice(-limit));
  }

  private newBatchId(): string {
    return `batch-${++this.batchSequence}`;
  }
}
