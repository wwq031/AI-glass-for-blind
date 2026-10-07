import { SpeechPriorityPolicy } from "../../../../packages/domain/policies/speech-priority-policy.ts";
import type { SpeechEffect } from "../../../../packages/domain/reminder/navigation-reminder-policy.ts";

export interface SpeechQueueOptions {
  /** Speaks one utterance on the device and resolves once playback finished. */
  speak(effect: SpeechEffect): Promise<boolean>;
  now?: () => Date;
  ttlMs?: number;
  idFactory?: () => string;
}

interface Queued extends SpeechEffect {
  settle(finished: boolean): void;
}

/**
 * One speech channel for the whole session.
 *
 * The device can only play one utterance at a time, so every announcement — a model reply, a
 * deterministic navigation reminder, a prompt that reopens the voice window — is ordered here by
 * the repository's SpeechPriorityPolicy instead of racing the others onto the wire. Ordering,
 * expiry and priority come from that policy; this class only adds the serialisation, the
 * per-utterance completion the caller may await, and the two ways an utterance can stop mattering:
 * it expires while it waits, or the session it belonged to is cancelled.
 *
 * Either way the promise the caller is awaiting is settled — `false` — rather than left pending,
 * because a caller that awaits a dropped utterance would otherwise wait forever. An utterance the
 * device is already speaking is not this queue's to revoke: its session's native owner revokes it,
 * and the utterance settles false through the playback result.
 */
export class SpeechQueue {
  private readonly policy = new SpeechPriorityPolicy();
  private readonly pending = new Map<string, Queued>();
  private draining = false;

  private readonly options: SpeechQueueOptions;

  constructor(options: SpeechQueueOptions) {
    this.options = options;
  }

  enqueue(text: string, priority: SpeechEffect["priority"], sessionId: string): Promise<boolean> {
    const now = this.options.now ?? (() => new Date());
    const idFactory = this.options.idFactory ?? (() => crypto.randomUUID());
    const effect: SpeechEffect = {
      schema_version: "1.0",
      effect_id: idFactory(),
      session_id: sessionId,
      text,
      priority,
      source: priority === "critical" || priority === "high" ? "risk" : "system",
      interruptible: priority !== "critical",
      expires_at: new Date(now().getTime() + (this.options.ttlMs ?? 120_000)).toISOString(),
      repeatable: true,
    };
    return new Promise<boolean>((resolve) => {
      this.pending.set(effect.effect_id, { ...effect, settle: resolve });
      this.policy.enqueue(effect);
      void this.drain();
    });
  }

  /**
   * The session is over: nothing it queued may still be spoken. Every utterance of that session
   * still waiting settles `false`, and the policy's copy of it is left behind unreplayable — the
   * drain that follows finds no pending entry for it and moves on. An utterance already on the
   * device is not settled here: the native owner revokes it, and it settles through its playback
   * result, so the queue never claims a stop it did not perform.
   */
  cancelSession(sessionId: string): void {
    for (const [effectId, queued] of [...this.pending]) {
      if (queued.session_id !== sessionId) continue;
      this.pending.delete(effectId);
      queued.settle(false);
    }
    // Purge what the policy still holds for this session, so a cancelled utterance is never spoken
    // on a later drain and never keeps the queue looking busy.
    void this.drain();
  }

  /** Waits for the queue to go quiet; used by tests and by shutdown paths. */
  async idle(): Promise<void> {
    for (;;) {
      this.settleExpired();
      if (!this.draining && this.policy.size === 0) return;
      if (!this.draining) void this.drain();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  }

  get size(): number {
    return this.policy.size + (this.draining ? 1 : 0);
  }

  /**
   * Settles every queued utterance whose own expiry has passed. The policy drops expired entries
   * when it is asked for the next one; a promise whose utterance was dropped that way must be
   * settled here, or its caller would await an utterance that will never be spoken.
   */
  private settleExpired(): void {
    const now = (this.options.now?.() ?? new Date()).getTime();
    for (const [effectId, queued] of [...this.pending]) {
      const expiry = Date.parse(queued.expires_at);
      if (!Number.isFinite(expiry) || expiry > now) continue;
      this.pending.delete(effectId);
      queued.settle(false);
    }
  }

  private async drain(): Promise<void> {
    if (this.draining) return;
    this.draining = true;
    try {
      for (;;) {
        // Expiry is settled before the policy is asked what to speak next, and again when it has
        // nothing left to give. The policy drops expired entries the moment it is asked for the
        // next one; a queue that only settled expiry afterwards would leave the promise of the
        // last expired utterance pending with nothing left to trigger another drain.
        this.settleExpired();
        const next = this.policy.next(this.options.now?.() ?? new Date());
        if (!next) {
          this.settleExpired();
          break;
        }
        const queued = this.pending.get(next.effect_id);
        this.pending.delete(next.effect_id);
        // No pending entry means this utterance stopped mattering — cancelled or expired while it
        // waited. It is consumed and dropped, never spoken and never replayed.
        if (!queued) continue;
        let finished = false;
        try {
          finished = await this.options.speak(next);
        } catch {
          finished = false;
        }
        queued.settle(finished);
      }
    } finally {
      this.draining = false;
    }
  }
}
