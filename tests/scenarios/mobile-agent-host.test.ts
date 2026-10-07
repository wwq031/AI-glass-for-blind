import assert from "node:assert/strict";
import test from "node:test";

import { MobileAgentHost } from "../../apps/phone-companion/src/android/mobile-agent-host.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { RecordedToolGateway } from "../../packages/testkit/recorded-tool-gateway.ts";

test("mobile host submits a contract event to the repository Agent and emits its contract Effect", async () => {
  const occurredAt = "2026-09-30T02:00:00.000Z";
  const published: unknown[] = [];
  const host = new MobileAgentHost({
    agent: new RecordedLlmAgent([{
      planId: "plan-1",
      sessionId: "session-1",
      eventId: "event-1",
      goal: "播报导航状态",
      actions: [{ kind: "speak", text: "前方右转。", priority: "normal" }],
      createdAt: occurredAt,
    }]),
    tools: new RecordedToolGateway([]),
    skills: createP0SkillRegistry(),
    publishContractEffect: (effect) => { published.push(effect); },
    now: () => occurredAt,
  });

  const output = await host.accept({
    schema_version: "1.0",
    event_id: "event-1",
    session_id: "session-1",
    sequence: 1,
    occurred_at: occurredAt,
    source: "navigation",
    type: "navigation.approaching_maneuver",
    payload: { instruction: "前方右转" },
  });

  assert.equal(output.rejection, undefined);
  assert.deepEqual(published, [{
    schema_version: "1.0",
    effect_id: "plan-1:0",
    session_id: "session-1",
    type: "speech",
    created_at: occurredAt,
    payload: { text: "前方右转。", priority: "normal" },
  }]);
});
