# Agent Harness P0 Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在模拟器中实现一个以 Event → Plan → Result → Effect 为主数据流的 LLM Agent Harness，使任意目的地导航、导航事件唤起路口检查、通用观察/文字读取和会话追问能够被回放测试。

**Architecture:** LLM Agent 负责从当前 Event 和 SessionState 生成 AgentPlan；Harness 负责构造上下文、验证 Plan、执行逻辑 Tool 并将 ToolResult 作为下一 Event 回流。Provider、地图、视觉和设备均通过注入接口和 RecordedProvider 替身接入；真实 CXR、真实 Android、真实云模型和实际 ProviderRouter 适配器不在本计划范围内。

**Tech Stack:** TypeScript、Node.js node:test、AJV JSON Schema 校验、现有 pnpm 工作区、RecordedProvider 测试替身。

---

## Scope and integration baseline

This plan implements only the shared Agent core. It deliberately does not create a second device protocol, Android application, CXR adapter, map SDK adapter, cloud model adapter, or MCP server.

Before Task 1, reconcile this branch with the teammate slices that add package.json, pnpm-lock.yaml, tsconfig.json, tools/validate-contracts.mjs, and the existing device/navigation/observation test files. If package.json is absent after reconciliation, stop: do not invent a parallel build setup.

The implementation contract source of truth is:

- docs/superpowers/specs/2026-09-22-agent-harness-v2-design.md
- packages/contracts/schemas/event-envelope.schema.json
- packages/contracts/schemas/tool-call.schema.json
- packages/contracts/schemas/tool-result.schema.json
- packages/contracts/schemas/fact.schema.json

### Task 1: Add the Agent-flow contracts and fixtures

**Files:**

- Create: packages/contracts/schemas/agent-plan.schema.json
- Create: packages/contracts/schemas/effect.schema.json
- Create: packages/contracts/schemas/skill-manifest.schema.json
- Create: packages/contracts/skills/registry.json
- Create: packages/contracts/examples/agent-plan-navigate.json
- Create: packages/contracts/examples/effect-speech.json
- Create: packages/contracts/examples/skill-crossing-advisory.json
- Create: tests/contracts/agent-flow-contracts.test.ts
- Modify: packages/contracts/schemas/event-envelope.schema.json
- Modify: packages/contracts/schemas/session-snapshot.schema.json
- Modify: packages/contracts/README.md

- [ ] **Step 1: Write the failing contract-validation test**

~~~ts
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

test("Agent flow schemas and examples validate", () => {
  assert.doesNotThrow(() => {
    execFileSync(process.execPath, ["tools/validate-contracts.mjs"], {
      cwd: process.cwd(),
      stdio: "pipe"
    });
  });
});
~~~

- [ ] **Step 2: Run the test to verify the absent Agent contracts fail validation**

Run:

~~~text
pnpm test -- tests/contracts/agent-flow-contracts.test.ts
~~~

Expected: FAIL because the Agent flow schemas and examples do not exist yet.

- [ ] **Step 3: Define the schemas and fixtures**

Create agent-plan.schema.json with one top-level plan contract. It must require schema_version, plan_id, session_id, event_id, goal, actions, and created_at. Each action must use exactly one of the following shapes:

~~~json
{ "kind": "tool_call", "skill_id": "navigate_to", "tool_id": "navigation.search_destination", "arguments": {} }
{ "kind": "speak", "text": "正在查找目的地。", "priority": "normal" }
{ "kind": "wait", "event_types": ["navigation.approaching_intersection"] }
{ "kind": "complete", "reason": "user_completed" }
~~~

Create effect.schema.json as the common output envelope:

~~~json
{
  "schema_version": "1.0",
  "effect_id": "effect-001",
  "session_id": "session-001",
  "type": "speech",
  "created_at": "2026-09-22T10:00:00.000Z",
  "payload": {
    "text": "正在为你查找人民医院。",
    "priority": "normal",
    "interruptible": true
  }
}
~~~

Create skill-manifest.schema.json requiring skill_id, version, parameters_schema, allowed_tools, result_kinds, risk_level, and optional required_policy. Create registry.json with exactly these P0 Skills:

~~~json
[
  "navigate_to",
  "inspect_scene",
  "read_text",
  "find_target",
  "follow_up",
  "crossing_advisory",
  "obstacle_advisory",
  "menu_structuring"
]
~~~

Extend event-envelope.schema.json source with motion and provider. Extend session-snapshot.schema.json with optional goal, active_plan_id, pending_question, facts, and active_skills, while retaining the existing fields for compatibility.

Add one valid JSON example for each new schema. The crossing Skill example must name crossing-advisory as required_policy and observation.request as its only allowed observation Tool.

- [ ] **Step 4: Validate schemas and run the contract test**

Run:

~~~text
pnpm validate:contracts
pnpm test -- tests/contracts/agent-flow-contracts.test.ts
~~~

Expected: both commands PASS and all three valid fixtures are accepted. Invalid Skill and Tool actions are covered by the runtime validator in Task 4.

- [ ] **Step 5: Document the four-object contract and commit**

Add a Contract flow section to packages/contracts/README.md:

~~~text
Event enters the Agent.
AgentPlan is produced by the LLM.
ToolResult is returned as a later Event.
Effect leaves the Agent after validation.
~~~

Commit:

~~~text
git add packages/contracts tests/contracts
git commit -m "feat: add agent flow contracts"
~~~

### Task 2: Define typed runtime objects and the Skill Registry

**Files:**

- Create: packages/domain/agent/types.ts
- Create: packages/domain/skills/skill-registry.ts
- Create: tests/domain/skill-registry.test.ts

- [ ] **Step 1: Write the failing Skill Registry tests**

~~~ts
import assert from "node:assert/strict";
import { test } from "node:test";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";

test("P0 registry exposes generic and high-risk skills", () => {
  const registry = createP0SkillRegistry();
  assert.equal(registry.get("navigate_to")?.riskLevel, "low");
  assert.equal(registry.get("crossing_advisory")?.requiredPolicy, "crossing-advisory");
});

test("crossing advisory only allows traffic-signal observation", () => {
  const registry = createP0SkillRegistry();
  assert.deepEqual(
    registry.get("crossing_advisory")?.allowedTools,
    ["observation.request", "facts.query", "speech.ask_user"]
  );
});
~~~

- [ ] **Step 2: Run the tests to verify they fail**

Run:

~~~text
pnpm test -- tests/domain/skill-registry.test.ts
~~~

Expected: FAIL because skill-registry.ts does not exist.

- [ ] **Step 3: Add the typed runtime vocabulary**

In packages/domain/agent/types.ts define the exact exported shapes below. These are normalized camelCase runtime objects; EventNormalizer and contract adapters translate JSON Schema field names at the process boundary.

~~~ts
export type EventSource =
  | "user"
  | "system"
  | "navigation"
  | "motion"
  | "provider"
  | "device";

export interface AgentEvent {
  eventId: string;
  sessionId: string;
  sequence: number;
  source: EventSource;
  type: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export type PlanAction =
  | { kind: "tool_call"; skillId: string; toolId: string; arguments: Record<string, unknown> }
  | { kind: "speak"; text: string; priority: "critical" | "high" | "normal" | "detail" }
  | { kind: "wait"; eventTypes: string[] }
  | { kind: "complete"; reason: string };

export interface AgentPlan {
  planId: string;
  sessionId: string;
  eventId: string;
  goal: string;
  actions: PlanAction[];
  responseDraft?: string;
  createdAt: string;
}

export interface SkillDefinition {
  skillId: string;
  riskLevel: "low" | "medium" | "high";
  allowedTools: string[];
  requiredPolicy?: string;
}

export class SkillRegistry {
  private readonly byId: ReadonlyMap<string, SkillDefinition>;

  constructor(skills: readonly SkillDefinition[]) {
    this.byId = new Map(skills.map((skill) => [skill.skillId, skill]));
  }

  get(skillId: string): SkillDefinition | undefined {
    return this.byId.get(skillId);
  }

  list(): SkillDefinition[] {
    return [...this.byId.values()];
  }
}

export interface ToolResult {
  callId: string;
  sessionId: string;
  toolId: string;
  status: "succeeded" | "partial" | "failed" | "denied" | "expired" | "cancelled";
  completedAt: string;
  output: Record<string, unknown>;
  facts: Array<{ name: string; value: unknown; confidence: "high" | "medium" | "low" | "unknown"; validUntil?: string }>;
}

export interface Effect {
  effectId: string;
  sessionId: string;
  type: "speech" | "haptic" | "device_command" | "navigation" | "session";
  createdAt: string;
  payload: Record<string, unknown>;
}
~~~

Implement createP0SkillRegistry in skill-registry.ts by constructing the exported SkillRegistry with the eight P0 definitions from Task 1, including the exact crossing tool list asserted by the test.

- [ ] **Step 4: Run the focused tests and type check**

Run:

~~~text
pnpm test -- tests/domain/skill-registry.test.ts
pnpm typecheck
~~~

Expected: PASS.

- [ ] **Step 5: Commit the runtime vocabulary**

~~~text
git add packages/domain/agent/types.ts packages/domain/skills/skill-registry.ts tests/domain/skill-registry.test.ts
git commit -m "feat: add agent skill registry"
~~~

### Task 3: Add the LLM Agent port and a recorded Agent for replay

**Files:**

- Create: packages/domain/agent/context-builder.ts
- Create: packages/domain/agent/llm-agent.ts
- Create: packages/testkit/recorded-llm-agent.ts
- Create: tests/domain/llm-agent-contract.test.ts

- [ ] **Step 1: Write the failing Agent port tests**

~~~ts
import assert from "node:assert/strict";
import { test } from "node:test";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { buildAgentTurnInput } from "../../packages/domain/agent/context-builder.ts";

test("recorded LLM receives normalized Event and returns its next plan", async () => {
  const agent = new RecordedLlmAgent([{
    planId: "plan-1",
    sessionId: "s-1",
    eventId: "e-1",
    goal: "navigate",
    actions: [{ kind: "speak", text: "请说目的地。", priority: "normal" }],
    createdAt: "2026-09-22T10:00:00.000Z"
  }]);

  const plan = await agent.plan({
    event: {
      eventId: "e-1", sessionId: "s-1", sequence: 1,
      source: "user", type: "button.short_press",
      occurredAt: "2026-09-22T10:00:00.000Z", payload: {}
    },
    session: { sessionId: "s-1", lastSequence: 0, activeSkills: [] },
    skills: []
  });

  assert.equal(plan.actions[0].kind, "speak");
});
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run:

~~~text
pnpm test -- tests/domain/llm-agent-contract.test.ts
~~~

Expected: FAIL because RecordedLlmAgent does not exist.

- [ ] **Step 3: Implement the Agent port, context builder, and recorded implementation**

In llm-agent.ts export:

~~~ts
import type { AgentEvent, AgentPlan, SkillDefinition } from "./types.ts";

export interface AgentSessionView {
  sessionId: string;
  lastSequence: number;
  goal?: string;
  activePlanId?: string;
  activeSkills: string[];
  urgentSkillId?: string;
  pendingQuestion?: string;
}

export interface AgentTurnInput {
  event: AgentEvent;
  session: AgentSessionView;
  skills: SkillDefinition[];
  recentResults?: unknown[];
}

export interface LlmAgent {
  plan(input: AgentTurnInput): Promise<AgentPlan>;
}
~~~

ContextBuilder must accept normalized Event, SessionState, visible Skills, recent ToolResult values, and an optional urgentSkillId. It must omit raw media bytes, raw Bluetooth packets, and fields named token, secret, or authorization from the returned AgentTurnInput.

RecordedLlmAgent must accept an array of AgentPlan values and return them in order. If the plan list is empty, it must throw Error with message Recorded LLM has no remaining plan.

- [ ] **Step 4: Add the raw-data filtering assertion and run tests**

Append this test:

~~~ts
test("context builder removes raw media and secrets", () => {
  const context = buildAgentTurnInput({
    event: {
      eventId: "e-2", sessionId: "s-1", sequence: 2,
      source: "provider", type: "observation.completed",
      occurredAt: "2026-09-22T10:00:01.000Z",
      payload: { mediaBytes: "raw", token: "secret", summary: "入口在右前方" }
    },
    session: { sessionId: "s-1", lastSequence: 1, activeSkills: [] },
    skills: [],
    recentResults: []
  });

  assert.deepEqual(context.event.payload, { summary: "入口在右前方" });
});
~~~

Run:

~~~text
pnpm test -- tests/domain/llm-agent-contract.test.ts
pnpm typecheck
~~~

Expected: PASS.

- [ ] **Step 5: Commit the LLM seam**

~~~text
git add packages/domain/agent/context-builder.ts packages/domain/agent/llm-agent.ts packages/testkit/recorded-llm-agent.ts tests/domain/llm-agent-contract.test.ts
git commit -m "feat: add llm agent planning seam"
~~~

### Task 4: Validate AgentPlan without hardcoding user journeys

**Files:**

- Create: packages/domain/agent/plan-validator.ts
- Create: tests/domain/plan-validator.test.ts

- [ ] **Step 1: Write failing validation tests**

~~~ts
import assert from "node:assert/strict";
import { test } from "node:test";
import { validatePlan } from "../../packages/domain/agent/plan-validator.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import type { AgentPlan, SkillRegistry } from "../../packages/domain/agent/types.ts";

const registry = createP0SkillRegistry();

test("rejects a plan that references an unknown Skill", () => {
  const result = validatePlan(registry, {
    planId: "p", sessionId: "s", eventId: "e", goal: "x", createdAt: "2026-09-22T10:00:00.000Z",
    actions: [{ kind: "tool_call", skillId: "invented_skill", toolId: "facts.query", arguments: {} }]
  }, { observationConsent: "explicit" });

  assert.deepEqual(result, { ok: false, code: "unknown_skill", actionIndex: 0 });
});

test("requires consent for observation requests", () => {
  const result = validatePlan(registry, {
    planId: "p", sessionId: "s", eventId: "e", goal: "crossing", createdAt: "2026-09-22T10:00:00.000Z",
    actions: [{
      kind: "tool_call", skillId: "crossing_advisory",
      toolId: "observation.request",
      arguments: { capability_id: "vision.traffic_signal" }
    }]
  }, { observationConsent: "none" });

  assert.equal(result.ok, false);
  assert.equal(result.code, "consent_required");
});

test("accepts a generic text-reading observation plan", () => {
  const result = validatePlan(registry, {
    planId: "p", sessionId: "s", eventId: "e", goal: "read_sign", createdAt: "2026-09-22T10:00:00.000Z",
    actions: [{
      kind: "tool_call", skillId: "read_text",
      toolId: "observation.request",
      arguments: { capability_id: "vision.menu" }
    }]
  }, { observationConsent: "explicit" });

  assert.deepEqual(result, { ok: true });
});
~~~

- [ ] **Step 2: Run tests to verify they fail**

Run:

~~~text
pnpm test -- tests/domain/plan-validator.test.ts
~~~

Expected: FAIL because validatePlan does not exist.

- [ ] **Step 3: Implement the minimal validator**

Export these types and function:

~~~ts
export type PlanValidation =
  | { ok: true }
  | { ok: false; code: "unknown_skill" | "tool_not_allowed" | "consent_required" | "policy_required" | "too_many_actions"; actionIndex: number };

export interface ExecutionPermissions {
  observationConsent: "none" | "explicit" | "preauthorized";
}

export function validatePlan(
  registry: SkillRegistry,
  plan: AgentPlan,
  permissions: ExecutionPermissions
): PlanValidation
~~~

Validate every tool_call action in order. Reject an unknown Skill, a Tool outside the Skill allowedTools list, a fifth action, and observation.request with observationConsent equal to none. For crossing_advisory, require capability_id equal to vision.traffic_signal and return policy_required if it is absent or different. Configure read_text to allow observation.request with any registered text-reading capability_id. Speak, wait, and complete actions are valid without a Skill lookup.

The Skill allowedTools list expresses a Skill dependency, not model permission. The Tool Registry marks observation.request as exposure=policy. PlanValidator must reject any model-authored policy-only Tool except observation.request, which is a proposal that still requires explicit or preauthorized observation consent and the Skill-specific guard. navigation.start must never be accepted from an LLM Plan; a confirmed destination is started by the navigation policy through the existing ToolGateway. Add a negative test for navigation.start and an authorized observation test. Neither the LLM Plan nor its arguments may set ToolCall.origin.

- [ ] **Step 4: Run validator and full type checks**

Run:

~~~text
pnpm test -- tests/domain/plan-validator.test.ts
pnpm typecheck
~~~

Expected: PASS.

- [ ] **Step 5: Commit plan validation**

~~~text
git add packages/domain/agent/plan-validator.ts tests/domain/plan-validator.test.ts
git commit -m "feat: validate llm agent plans"
~~~

### Task 5: Implement the TaskRunner and SessionOrchestrator Event loop

**Files:**

- Create: packages/domain/agent/tool-gateway.ts
- Create: packages/domain/agent/task-runner.ts
- Create: packages/domain/agent/session-orchestrator.ts
- Create: packages/testkit/recorded-tool-gateway.ts
- Create: tests/domain/session-orchestrator.test.ts

- [ ] **Step 1: Write the failing Event → Plan → Effect test**

~~~ts
import assert from "node:assert/strict";
import { test } from "node:test";
import { SessionOrchestrator } from "../../packages/domain/agent/session-orchestrator.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { RecordedToolGateway } from "../../packages/testkit/recorded-tool-gateway.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";

test("button Event becomes a planned SpeechEffect", async () => {
  const orchestrator = new SessionOrchestrator({
    agent: new RecordedLlmAgent([{
      planId: "p-1", sessionId: "s-1", eventId: "e-1", goal: "get_destination",
      actions: [{ kind: "speak", text: "请说目的地。", priority: "normal" }],
      createdAt: "2026-09-22T10:00:00.000Z"
    }]),
    tools: new RecordedToolGateway([]),
    skills: createP0SkillRegistry(),
    now: () => "2026-09-22T10:00:00.000Z"
  });

  const output = await orchestrator.handle({
    eventId: "e-1", sessionId: "s-1", sequence: 1,
    source: "user", type: "button.short_press",
    occurredAt: "2026-09-22T10:00:00.000Z", payload: {}
  });

  assert.equal(output.effects[0]?.type, "speech");
  assert.equal(output.effects[0]?.payload.text, "请说目的地。");
});
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run:

~~~text
pnpm test -- tests/domain/session-orchestrator.test.ts
~~~

Expected: FAIL because SessionOrchestrator does not exist.

- [ ] **Step 3: Implement the execution interfaces and loop**

tool-gateway.ts must export:

~~~ts
import type { AgentPlan, ToolResult } from "./types.ts";

export interface ToolGateway {
  execute(input: {
    sessionId: string;
    plan: AgentPlan;
    actionIndex: number;
    toolId: string;
    arguments: Record<string, unknown>;
    origin: "agent" | "policy";
    consent: "none" | "explicit" | "preauthorized";
  }): Promise<ToolResult>;
}
~~~

RecordedToolGateway must consume ToolResult values in order and throw Error with message Recorded gateway has no remaining result when a ToolCall is not preloaded.

TaskRunner must:

1. Convert every speak action into one Effect with type speech.
2. Execute tool_call actions sequentially through ToolGateway.
3. Return each ToolResult without fabricating a success result.
4. Convert complete action into one session Effect.
5. Leave wait actions without an Effect.

TaskRunner receives only a validated Plan and its execution permissions. For ordinary model-exposed Tools it passes origin=agent. For an approved observation.request it passes origin=policy only after PlanValidator has accepted the Skill-specific conditions and the observation consent; the model cannot supply origin. An adapter to the existing packages/domain/tools/tool-gateway.ts must preserve this origin when constructing a concrete ToolCall. Add a test that an LLM Plan for navigation.start produces a rejection and never reaches the gateway.

SessionOrchestrator.handle must:

1. Reject an Event whose sequence is not greater than SessionState.lastSequence.
2. Build AgentTurnInput.
3. Await LlmAgent.plan.
4. Validate the AgentPlan.
5. Return a PlanRejection Result when validation fails.
6. Otherwise run TaskRunner, persist lastSequence and activePlanId, and return Effects plus ToolResults.

Export this output contract from session-orchestrator.ts:

~~~ts
export interface AgentHandleOutput {
  effects: Effect[];
  results: ToolResult[];
  rejection?: {
    code:
      | "stale_event"
      | "unknown_skill"
      | "tool_not_allowed"
      | "consent_required"
      | "policy_required"
      | "too_many_actions";
  };
}
~~~

- [ ] **Step 4: Add duplicate-event coverage and run tests**

Append:

~~~ts
function event(eventId: string, sequence: number) {
  return {
    eventId, sequence, sessionId: "s-1", source: "user" as const,
    type: "button.short_press",
    occurredAt: "2026-09-22T10:00:00.000Z", payload: {}
  };
}

function createOrchestratorWithOneSpeechPlan() {
  return new SessionOrchestrator({
    agent: new RecordedLlmAgent([{
      planId: "p-1", sessionId: "s-1", eventId: "e-1", goal: "wake",
      actions: [{ kind: "speak", text: "请说目的地。", priority: "normal" }],
      createdAt: "2026-09-22T10:00:00.000Z"
    }]),
    tools: new RecordedToolGateway([]),
    skills: createP0SkillRegistry(),
    now: () => "2026-09-22T10:00:00.000Z"
  });
}

test("duplicate Event sequence is rejected before reaching the LLM", async () => {
  const orchestrator = createOrchestratorWithOneSpeechPlan();
  await orchestrator.handle(event("e-1", 1));
  const output = await orchestrator.handle(event("e-dup", 1));

  assert.equal(output.rejection?.code, "stale_event");
});
~~~

Run:

~~~text
pnpm test -- tests/domain/session-orchestrator.test.ts
pnpm typecheck
~~~

Expected: PASS.

- [ ] **Step 5: Commit the Event loop**

~~~text
git add packages/domain/agent packages/testkit/recorded-tool-gateway.ts tests/domain/session-orchestrator.test.ts
git commit -m "feat: add agent event plan result effect loop"
~~~

### Task 6: Wire navigation events to the crossing Skill and Policy

**Files:**

- Create: packages/domain/policies/crossing-advisory.ts
- Create: packages/domain/agent/navigation-trigger.ts
- Create: tests/domain/crossing-advisory.test.ts
- Create: tests/scenarios/navigation-crossing-replay.test.ts

- [ ] **Step 1: Write the failing crossing Policy tests**

~~~ts
import assert from "node:assert/strict";
import { test } from "node:test";
import { adviseCrossing } from "../../packages/domain/policies/crossing-advisory.ts";

test("unknown traffic facts produce cannot_determine", () => {
  const advisory = adviseCrossing({
    trafficSignal: "unknown",
    directionMatch: "unknown",
    vehicleApproaching: "unknown",
    confidence: "unknown",
    now: "2026-09-22T10:00:10.000Z"
  });

  assert.equal(advisory.action, "cannot_determine");
});

test("green signal with matching direction and no approaching vehicle is cautious only", () => {
  const advisory = adviseCrossing({
    trafficSignal: "green",
    directionMatch: "yes",
    vehicleApproaching: "no",
    confidence: "high",
    now: "2026-09-22T10:00:10.000Z"
  });

  assert.equal(advisory.action, "proceed_with_caution");
});
~~~

- [ ] **Step 2: Run the tests to verify they fail**

Run:

~~~text
pnpm test -- tests/domain/crossing-advisory.test.ts
~~~

Expected: FAIL because crossing-advisory.ts does not exist.

- [ ] **Step 3: Implement the navigation-trigger and crossing Policy**

navigation-trigger.ts must export:

~~~ts
export function skillContextFromNavigationEvent(event: AgentEvent): {
  urgentSkillId?: "crossing_advisory";
  navigation?: {
    intersectionId?: string;
    distanceM?: number;
    travelHeadingDeg?: number;
  };
}
~~~

Return urgentSkillId equal to crossing_advisory only when event.type equals navigation.intersection_approaching or navigation.crosswalk_approaching. Copy intersection_id, distance_m, and travel_heading_deg from the normalized payload. Do not create an observation ToolCall in this module.

Modify session-orchestrator.ts after its Task 5 context-builder call: when skillContextFromNavigationEvent returns urgentSkillId, pass that value as AgentSessionView.urgentSkillId into LlmAgent.plan. The trigger only contributes navigation context; it does not produce observation.request itself.

crossing-advisory.ts must return exactly one action:

~~~ts
export type CrossingAction = "wait" | "recheck" | "proceed_with_caution" | "cannot_determine";
~~~

Define CrossingEvidence with trafficSignal, directionMatch, vehicleApproaching, confidence, validUntil, and now. Return wait for red or an approaching vehicle, recheck for low-confidence but fresh facts, cannot_determine for unknown, expired, or direction-mismatch facts, and proceed_with_caution only for green plus direction yes plus vehicle no. The returned speech text must state that it is an aid, not a safety guarantee.

- [ ] **Step 4: Write and run the Event-driven replay**

Create a replay test that performs:

~~~text
1. navigation.started Event
2. navigation.intersection_approaching Event with intersection_id and travel_heading_deg
3. recorded LLM Plan containing crossing_advisory plus observation.request for vision.traffic_signal
4. recorded ToolResult with traffic_signal.state=unknown
5. CrossingPolicy output cannot_determine
6. SpeechEffect containing 请先停下
~~~

The assertion must prove that navigation-trigger created the crossing context and that navigate_to itself did not issue an observation request.

Run:

~~~text
pnpm test -- tests/domain/crossing-advisory.test.ts tests/scenarios/navigation-crossing-replay.test.ts
~~~

Expected: PASS.

- [ ] **Step 5: Commit the safety vertical slice**

~~~text
git add packages/domain/policies/crossing-advisory.ts packages/domain/agent/navigation-trigger.ts tests/domain/crossing-advisory.test.ts tests/scenarios/navigation-crossing-replay.test.ts
git commit -m "feat: trigger crossing advisory from navigation events"
~~~

### Task 7: Publish the Agent-core integration boundary and run acceptance checks

**Files:**

- Modify: packages/domain/agent/README.md
- Modify: packages/contracts/interfaces.md
- Modify: docs/team/file-delivery-matrix.md
- Modify: docs/scenarios/golden-path-navigation-restaurant.md

- [ ] **Step 1: Document the implemented entrypoint**

Add this exact responsibility statement to packages/domain/agent/README.md:

~~~text
SessionOrchestrator.handle(Event) is the only Agent-core entrypoint.
It returns validated Effects, ToolResults, and a rejection when a Plan cannot execute.
It never imports Android, JSUI, CXR, map SDK, or model SDK code.
~~~

- [ ] **Step 2: Document provider and team ownership**

In packages/contracts/interfaces.md, state that ToolGateway is a logical contract and that LocalProvider, RemoteProvider, McpProviderAdapter, and RecordedProvider remain ProviderRouter implementations outside the Agent core.

In docs/team/file-delivery-matrix.md, assign Agent-core owners the files under packages/domain/agent and tests/domain; assign device, map, speech, and vision owners only the corresponding injected adapters and contract fixtures.

- [ ] **Step 3: Update the golden scenario language**

Modify docs/scenarios/golden-path-navigation-restaurant.md so that restaurant remains an acceptance example. Replace any statement that implies a permanent restaurant workflow with:

~~~text
The scenario is a parameterized TaskPlan: navigate_to(target), then optional find_target(target=entrance), then user-requested read_text(mode=menu).
~~~

- [ ] **Step 4: Run the complete P0 acceptance suite**

Run:

~~~text
pnpm validate:contracts
pnpm test -- tests/contracts/agent-flow-contracts.test.ts tests/domain/skill-registry.test.ts tests/domain/llm-agent-contract.test.ts tests/domain/plan-validator.test.ts tests/domain/session-orchestrator.test.ts tests/domain/crossing-advisory.test.ts tests/scenarios/navigation-crossing-replay.test.ts
pnpm typecheck
~~~

Expected: all commands PASS. The replay must prove Event → Plan → Result → Effect and must not call a real device, network service, or model.

- [ ] **Step 5: Commit the documented P0 core**

~~~text
git add packages/domain/agent/README.md packages/contracts/interfaces.md docs/team/file-delivery-matrix.md docs/scenarios/golden-path-navigation-restaurant.md
git commit -m "docs: document agent harness p0 boundary"
~~~

## Self-review checklist

- [ ] The plan implements all four principal objects in the approved design: Event, Plan, Result, and Effect.
- [ ] The plan keeps LLM planning in the LlmAgent port and does not replace it with a hardcoded journey state machine.
- [ ] The plan allows dynamic composition of registered Skills and does not create destination-specific Skills.
- [ ] The plan makes navigation events, not navigate_to itself, create crossing-advisory context.
- [ ] The plan treats crossing facts as Provider Results and sends high-risk conclusions through CrossingPolicy.
- [ ] The plan uses only RecordedProvider-style test doubles and leaves real CXR, Android, maps, cloud models, MCP, and ProviderRouter adapters to separate plans.
- [ ] Every new runtime module has a focused node:test test and every contract fixture is validated.
