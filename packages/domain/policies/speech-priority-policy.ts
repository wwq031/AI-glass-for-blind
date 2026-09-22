import type { SpeechEffect } from "../reminder/navigation-reminder-policy.ts";

const priorityRank: Record<SpeechEffect["priority"], number> = {
  critical: 4,
  high: 3,
  normal: 2,
  detail: 1,
};

interface QueuedSpeech {
  effect: SpeechEffect;
  sequence: number;
}

/** Stable, expiring speech queue. Higher risk/priority effects are spoken first. */
export class SpeechPriorityPolicy {
  private readonly queue: QueuedSpeech[] = [];
  private sequence = 0;

  enqueue(effect: SpeechEffect): void {
    this.queue.push({ effect, sequence: this.sequence++ });
  }

  next(now = new Date()): SpeechEffect | undefined {
    this.removeExpired(now);
    this.queue.sort(
      (a, b) => priorityRank[b.effect.priority] - priorityRank[a.effect.priority] || a.sequence - b.sequence,
    );
    return this.queue.shift()?.effect;
  }

  peek(now = new Date()): SpeechEffect | undefined {
    this.removeExpired(now);
    return [...this.queue].sort(
      (a, b) => priorityRank[b.effect.priority] - priorityRank[a.effect.priority] || a.sequence - b.sequence,
    )[0]?.effect;
  }

  get size(): number {
    return this.queue.length;
  }

  private removeExpired(now: Date): void {
    for (let index = this.queue.length - 1; index >= 0; index--) {
      if (Date.parse(this.queue[index].effect.expires_at) <= now.getTime()) this.queue.splice(index, 1);
    }
  }
}
