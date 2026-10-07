import type { AgentEvent, ToolResult } from "../../../../packages/domain/agent/types.ts";
import type { DestinationCandidate } from "../../../../packages/providers/navigation/navigation-provider.ts";

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/** Coordinates must be usable by the route provider; a placeholder 0,0 is not a destination. */
function usableCandidate(value: unknown): value is DestinationCandidate {
  if (!isRecord(value) || typeof value.candidate_id !== "string" || !value.candidate_id ||
      typeof value.name !== "string" || !value.name || typeof value.provider !== "string" ||
      !value.provider || !isRecord(value.location)) return false;
  const { lat, lng } = value.location;
  return typeof lat === "number" && typeof lng === "number" &&
    Number.isFinite(lat) && Number.isFinite(lng) && lat > -90 && lat < 90 &&
    lng > -180 && lng < 180 && !(Math.abs(lat) < 0.000001 && Math.abs(lng) < 0.000001);
}

interface PendingSearch {
  queryId: string;
  candidates: DestinationCandidate[];
}

/**
 * Holds the destination candidates a search actually returned, and the confirmation policy that
 * guards starting a route.
 *
 * Which candidate a user meant is decided by the model, which reads the transcript and the
 * candidate list. Nothing here inspects the user's words: matching a spoken string against a place
 * name is not an authorisation, and it misreads a refusal ("不去人民公园") as a confirmation. The
 * policy only checks that the conversation is really at a confirmation point — the turn is the
 * user's own event for this session, the candidate id is one this session's search returned, the
 * candidate still has usable coordinates, and the same candidate of the same search has not already
 * been started.
 */
export class NavigationDestinationPolicy {
  private readonly searches = new Map<string, PendingSearch>();
  private readonly started = new Set<string>();

  recordSearch(result: ToolResult): void {
    if (result.toolId !== "navigation.search_destination" || result.status !== "succeeded") return;
    const list = result.output.candidates;
    const queryId = result.output.query_id;
    if (!Array.isArray(list) || list.length > 5 || !list.every(usableCandidate) ||
        typeof queryId !== "string" || !queryId) {
      throw new Error("navigation search returned invalid candidates");
    }
    this.searches.set(result.sessionId, {
      queryId,
      candidates: structuredClone(list) as DestinationCandidate[],
    });
  }

  /** The candidates this session is currently confirming, for the model to choose from. */
  pending(sessionId: string): DestinationCandidate[] {
    return structuredClone(this.searches.get(sessionId)?.candidates ?? []);
  }

  candidateCount(sessionId: string): number {
    return this.searches.get(sessionId)?.candidates.length ?? 0;
  }

  /** Validates the model's confirmation against this session's live search, never against its words. */
  confirm(
    input: { sessionId: string; toolId: string; candidateId: unknown },
    event: AgentEvent | undefined,
  ): DestinationCandidate {
    if (input.toolId !== "navigation.confirm_destination") {
      throw new Error("destination confirmation requires the confirmation tool");
    }
    if (!event || event.source !== "user" || event.sessionId !== input.sessionId) {
      throw new Error("destination confirmation requires the current user event of this session");
    }
    const pending = this.searches.get(input.sessionId);
    if (!pending) throw new Error("destination confirmation has no current search for this session");
    const candidateId = input.candidateId;
    if (typeof candidateId !== "string" || !candidateId) {
      throw new Error("destination confirmation needs the candidate id the model selected");
    }
    const selected = pending.candidates.find(({ candidate_id }) => candidate_id === candidateId);
    if (!selected) {
      throw new Error("model candidate is not one of this session's pending search results");
    }
    if (this.started.has(this.startKey(input.sessionId, pending.queryId, candidateId))) {
      throw new Error("navigation start already requested for this candidate");
    }
    return structuredClone(selected);
  }

  /** Marks the route of one search's candidate as requested, so a replay cannot start it twice. */
  markStartRequested(sessionId: string, queryId: string, candidateId: string): void {
    const key = this.startKey(sessionId, queryId, candidateId);
    if (this.started.has(key)) throw new Error("navigation start already requested for this candidate");
    this.started.add(key);
  }

  queryIdFor(sessionId: string): string | undefined {
    return this.searches.get(sessionId)?.queryId;
  }

  /** A new destination search retires the candidates it replaces. */
  clearSession(sessionId: string): void {
    this.searches.delete(sessionId);
  }

  private startKey(sessionId: string, queryId: string, candidateId: string): string {
    return `${sessionId}:${queryId}:${candidateId}`;
  }
}
