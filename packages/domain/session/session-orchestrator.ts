import type { DeviceEvent } from "../../providers/device/device-transport.ts";
import type { NavigationEvent } from "../../providers/navigation/navigation-provider.ts";
import type { ObservationResult } from "../../providers/observation/observation-provider.ts";
import type { SpeechInput } from "../../providers/speech/speech-input-provider.ts";
import {
  NavigationReminderPolicy,
  type SpeechEffect,
} from "../reminder/navigation-reminder-policy.ts";

export type SessionState =
  | "idle"
  | "destination_input"
  | "destination_confirm"
  | "navigating"
  | "intersection_check"
  | "approaching_destination"
  | "entrance_check"
  | "inside_restaurant"
  | "menu_reading"
  | "conversation_assist"
  | "completed"
  | "cancelled"
  | "device_disconnected"
  | "navigation_unavailable"
  | "vision_timeout";

export interface DestinationCandidateSummary {
  candidate_id: string;
  name: string;
}

export type SessionEvent =
  | { type: "destination.input_requested"; session_id: string }
  | { type: "destination.candidates_listed"; session_id: string; candidates: DestinationCandidateSummary[] }
  | { type: "destination.confirmed"; session_id: string; destination: DestinationCandidateSummary }
  | { type: "observation.prompted"; session_id: string }
  | { type: "observation.menu_requested"; session_id: string }
  | { type: "session.completed"; session_id: string }
  | NavigationEvent
  | DeviceEvent
  | SpeechInput
  | ObservationResult;

export type DomainEffect =
  | { type: "speech"; effect: SpeechEffect }
  | { type: "search_destination"; transcript: string; locale: string }
  | { type: "confirm_destination"; candidate_id: string }
  | { type: "start_navigation"; destination: DestinationCandidateSummary }
  | { type: "request_observation"; request_id: string; capability_id: string; consent: "explicit" }
  | { type: "wait"; reason: string };

export interface SessionSnapshot {
  schema_version: string;
  session_id: string;
  state: SessionState;
  last_sequence: number;
  updated_at: string;
  destination?: DestinationCandidateSummary;
  pending_observation?: { request_id: string; capability_id: string };
}

export interface SessionOrchestratorOptions {
  now?: () => Date;
  idFactory?: () => string;
  reminderPolicy?: NavigationReminderPolicy;
}

/** Device- and supplier-independent entry point for a single user session. */
export class SessionOrchestrator {
  private current: SessionSnapshot;
  private candidates: DestinationCandidateSummary[] = [];
  private readonly now: () => Date;
  private readonly idFactory: () => string;
  private readonly reminders: NavigationReminderPolicy;

  constructor(sessionId: string, options: SessionOrchestratorOptions = {}) {
    this.now = options.now ?? (() => new Date());
    this.idFactory = options.idFactory ?? (() => crypto.randomUUID());
    this.reminders = options.reminderPolicy ?? new NavigationReminderPolicy({ now: this.now, idFactory: this.idFactory });
    this.current = {
      schema_version: "1.0",
      session_id: sessionId,
      state: "idle",
      last_sequence: 0,
      updated_at: this.now().toISOString(),
    };
  }

  handle(event: SessionEvent): DomainEffect[] {
    if (event.session_id !== this.current.session_id) throw new Error("Event belongs to a different session");
    this.current.last_sequence = "sequence" in event ? Math.max(this.current.last_sequence, event.sequence) : this.current.last_sequence + 1;
    this.current.updated_at = this.now().toISOString();

    if (event.type === "device.disconnected") {
      this.transition("device_disconnected");
      return [this.speak("眼镜连接中断，请检查设备连接。", "critical", "system")];
    }
    if (this.isSpeechInput(event) && event.intent_hint === "cancel") {
      this.transition("cancelled");
      return [this.speak("当前任务已取消。", "high", "system")];
    }

    switch (this.current.state) {
      case "idle":
        if (event.type === "destination.input_requested") {
          this.transition("destination_input");
          return [this.speak("请说目的地。", "normal", "system")];
        }
        break;
      case "destination_input":
        if (this.isSpeechInput(event) && event.is_final) {
          return [{ type: "search_destination", transcript: event.transcript, locale: event.locale }];
        }
        if (event.type === "destination.candidates_listed") {
          this.candidates = event.candidates;
          this.transition("destination_confirm");
          const first = event.candidates[0];
          return first
            ? [this.speak(`找到${first.name}。确认请说确认。`, "normal", "system")]
            : [this.speak("没有找到候选地点，请换一个目的地。", "normal", "system")];
        }
        break;
      case "destination_confirm":
        if (this.isSpeechInput(event) && event.is_final && this.candidates[0]) {
          return [{ type: "confirm_destination", candidate_id: this.candidates[0].candidate_id }];
        }
        if (event.type === "destination.confirmed") {
          this.current.destination = event.destination;
          return [{ type: "start_navigation", destination: event.destination }];
        }
        if (event.type === "navigation.started") {
          this.transition("navigating");
          return this.navigationSpeech(event);
        }
        break;
      case "navigating":
        if (event.type === "navigation.intersection_approaching" || event.type === "navigation.crosswalk_approaching") {
          this.transition("intersection_check");
          return this.navigationSpeech(event);
        }
        if (event.type === "navigation.arrived") {
          this.transition("approaching_destination");
          return this.navigationSpeech(event);
        }
        if (this.isNavigation(event)) return this.navigationSpeech(event);
        break;
      case "intersection_check":
        if (event.type === "button.pressed" || (this.isSpeechInput(event) && /检查/.test(event.transcript))) {
          return [this.requestObservation("vision.traffic_signal")];
        }
        if (this.isObservation(event) && event.capability_id === "vision.traffic_signal") {
          this.current.pending_observation = undefined;
          this.transition("navigating");
          return [this.speak(event.summary, "critical", event.risks?.length ? "risk" : "observation")];
        }
        break;
      case "approaching_destination":
        if (event.type === "observation.prompted") {
          this.transition("entrance_check");
          return [this.speak("请按键观察入口。", "high", "system")];
        }
        break;
      case "entrance_check":
        if (event.type === "button.pressed") return [this.requestObservation("vision.entrance")];
        if (this.isObservation(event) && event.capability_id === "vision.entrance") {
          if (event.needs_retake) return [this.speak(`${event.summary} 请调整方向后重拍。`, "high", "observation")];
          this.current.pending_observation = undefined;
          this.transition("inside_restaurant");
          return [this.speak(event.summary, "normal", "observation")];
        }
        break;
      case "inside_restaurant":
        if (event.type === "observation.menu_requested" || event.type === "button.pressed") {
          this.transition("menu_reading");
          return [this.requestObservation("vision.menu")];
        }
        break;
      case "menu_reading":
        if (this.isObservation(event) && event.capability_id === "vision.menu") {
          this.current.pending_observation = undefined;
          return [this.speak(event.summary, "normal", "observation")];
        }
        if (this.isSpeechInput(event) && event.intent_hint === "expression_request") {
          this.transition("conversation_assist");
          return [this.requestObservation("vision.expression")];
        }
        break;
      case "conversation_assist":
        if (this.isObservation(event) && event.capability_id === "vision.expression") {
          this.current.pending_observation = undefined;
          this.transition("completed");
          return [this.speak(event.summary, "normal", "observation")];
        }
        break;
    }

    return [{ type: "wait", reason: `event_not_applicable_in_${this.current.state}` }];
  }

  snapshot(): SessionSnapshot {
    return structuredClone(this.current);
  }

  restore(snapshot: SessionSnapshot): void {
    if (snapshot.session_id !== this.current.session_id) throw new Error("Snapshot belongs to a different session");
    this.current = structuredClone(snapshot);
  }

  private transition(state: SessionState): void {
    this.current.state = state;
  }

  private requestObservation(capability_id: string): DomainEffect {
    const request_id = this.idFactory();
    this.current.pending_observation = { request_id, capability_id };
    return { type: "request_observation", request_id, capability_id, consent: "explicit" };
  }

  private navigationSpeech(event: NavigationEvent): DomainEffect[] {
    const effect = this.reminders.create(event);
    return effect ? [{ type: "speech", effect }] : [];
  }

  private speak(text: string, priority: SpeechEffect["priority"], source: SpeechEffect["source"]): DomainEffect {
    return {
      type: "speech",
      effect: {
        schema_version: "1.0",
        effect_id: this.idFactory(),
        session_id: this.current.session_id,
        text,
        priority,
        source,
        interruptible: priority !== "critical",
        expires_at: new Date(this.now().getTime() + 30_000).toISOString(),
        repeatable: true,
      },
    };
  }

  private isSpeechInput(event: SessionEvent): event is SpeechInput {
    return "input_id" in event;
  }

  private isObservation(event: SessionEvent): event is ObservationResult {
    return "request_id" in event && "capability_id" in event && "status" in event;
  }

  private isNavigation(event: SessionEvent): event is NavigationEvent {
    return "source" in event && event.source === "navigation" && "sequence" in event;
  }
}
