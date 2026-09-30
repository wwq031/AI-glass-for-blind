/**
 * Device window bookkeeping for one live conversation.
 *
 * This is deliberately not a task state machine. What it owns is only what the hardware owns: the
 * live conversation's identity, whether a recorder window or a capture window is open right now,
 * the fresh tag each window is armed under, and the generation a cancel retires. Which step the
 * user's task is on is the Agent's business, and nothing here knows or decides it.
 *
 * The glasses expose one window at a time — arming the camera closes the recorder and the other way
 * round — so both are modelled as a single slot. A tag is never reused: a confirmation, a photo or a
 * recording that arrives naming a retired tag authorises nothing, even if a new window is open.
 */
export class GlassesTaskFlow {
  private session: string | undefined;
  private windowGeneration = 0;
  private sequence = 0;
  private voice: string | undefined;
  private capture: { tag: string; capabilityId: string; confirmed: boolean } | undefined;

  get sessionId(): string | undefined { return this.session; }
  /** True while a conversation is live; it survives a finished task and ends only on cancel. */
  get live(): boolean { return this.session !== undefined; }
  /** Bumped by every cancel; a native operation carrying an older generation is retired. */
  get generation(): number { return this.windowGeneration; }

  start(sessionId: string): string {
    if (this.session !== undefined || !sessionId) throw new Error("a glasses session is already live");
    this.session = sessionId;
    return sessionId;
  }

  /** Ends the conversation and revokes every window and every captured consent with it. */
  cancel(): void {
    this.windowGeneration++;
    this.session = undefined;
    this.voice = undefined;
    this.capture = undefined;
  }

  // ---- recorder window ----

  /** Opens a fresh recorder window and retires whatever window was open before it. */
  armVoice(): string {
    this.sequence++;
    this.capture = undefined;
    this.voice = `voice:${this.sequence}`;
    return this.voice;
  }

  armedVoicePurpose(): string | undefined { return this.voice; }

  /** The recorder window is open for this exact purpose; anything else authorises nothing. */
  matchesVoice(purpose: string): boolean {
    return this.voice !== undefined && purpose === this.voice;
  }

  /** Consumes the window: audio recorded under this purpose is accepted exactly once. */
  acceptSpeech(purpose: string): boolean {
    if (!this.matchesVoice(purpose)) return false;
    this.voice = undefined;
    return true;
  }

  /**
   * A recorder window expired, was denied, or produced nothing usable. The old purpose is retired,
   * so late audio and a late failure from that attempt can never be counted, and a fresh window is
   * handed out. It still needs its own physical press before anything is recorded.
   */
  rearmVoice(): string | undefined {
    if (this.voice === undefined) return undefined;
    return this.armVoice();
  }

  closeVoice(): void { this.voice = undefined; }

  // ---- capture window ----

  /**
   * Opens a single-capture window for one capability. Which capability the user is consenting to is
   * fixed here, by the request that was on the table, and the model cannot change it afterwards.
   */
  armCapture(capabilityId: string): string {
    if (!capabilityId) throw new Error("a capture window needs the capability it authorises");
    this.sequence++;
    this.voice = undefined;
    this.capture = { tag: `capture:${this.sequence}`, capabilityId, confirmed: false };
    return this.capture.tag;
  }

  armedCaptureTag(): string | undefined { return this.capture?.tag; }
  armedCaptureCapability(): string | undefined { return this.capture?.capabilityId; }

  /**
   * The press that authorises the capture window that is open right now, and only that one.
   * A confirmation naming a retired tag authorises nothing — not even once that tag's photo shows up.
   */
  confirmCapture(tag: string): boolean {
    if (this.capture === undefined || tag !== this.capture.tag) return false;
    this.capture.confirmed = true;
    return true;
  }

  /**
   * Consumes the window. One explicit confirmation authorises exactly one photo of exactly the
   * capability the window was opened for; the next observation needs a new press and a new window.
   */
  acceptPhoto(tag: string): { capabilityId: string } | undefined {
    if (this.capture === undefined || !this.capture.confirmed || tag !== this.capture.tag) return undefined;
    const { capabilityId } = this.capture;
    this.capture = undefined;
    return { capabilityId };
  }

  /** A capture window expired or its camera failed; the old tag is retired and a new one handed out. */
  rearmCapture(): string | undefined {
    if (this.capture === undefined) return undefined;
    return this.armCapture(this.capture.capabilityId);
  }

  closeCapture(): void { this.capture = undefined; }
}
