import assert from "node:assert/strict";
import test from "node:test";
import { generateValidatedPlan, parseModelPlanResponse } from "../../apps/phone-companion/src/android/model-plan-response.ts";

const validPlan = {
  schema_version: "1.0",
  plan_id: "plan-1",
  session_id: "session-1",
  event_id: "event-1",
  goal: "确认目的地",
  actions: [{ kind: "speak", text: "请说目的地", priority: "normal" }],
  created_at: "2026-09-30T02:00:00.000Z",
};

test("accepts raw and a single fenced AgentPlan JSON object", () => {
  assert.equal(parseModelPlanResponse(JSON.stringify(validPlan)).planId, "plan-1");
  assert.equal(parseModelPlanResponse(`\`\`\`json\n${JSON.stringify(validPlan)}\n\`\`\``).planId, "plan-1");
});

test("rejects a fenced JSON object that is not an AgentPlan", () => {
  assert.throws(() => parseModelPlanResponse("```json\n{\"ok\":true}\n```"), /agent plan/);
});

test("rejects explanatory text around JSON", () => {
  assert.throws(() => parseModelPlanResponse(`Here is the plan:\n${JSON.stringify(validPlan)}`));
});

test("asks the model once to repair invalid contract output", async () => {
  const prompts: string[] = [];
  const responses = ["{\"schema_version\":1}", JSON.stringify(validPlan)];
  const plan = await generateValidatedPlan("original prompt", async (prompt) => {
    prompts.push(prompt);
    return responses.shift()!;
  });
  assert.equal(plan.planId, "plan-1");
  assert.equal(prompts.length, 2);
  assert.match(prompts[1]!, /schema_version/);
});

test("never loops beyond one repair request", async () => {
  let calls = 0;
  await assert.rejects(generateValidatedPlan("original prompt", async () => {
    calls++;
    return "{\"ok\":true}";
  }), /agent plan/);
  assert.equal(calls, 2);
});

test("runtime owns plan metadata while model supplies only goal and actions", async () => {
  const partial = JSON.stringify({
    goal: "搜索用户说出的地点",
    actions: [{ kind: "tool_call", skill_id: "navigate_to", tool_id: "navigation.search_destination",
      arguments: { query: "地铁站" } }],
  });
  const plan = await generateValidatedPlan("prompt", async () => partial, {
    planId: "model-event-1", sessionId: "session-1", eventId: "event-1",
    createdAt: "2026-09-30T04:00:00.000Z",
  });
  assert.equal(plan.planId, "model-event-1");
  assert.equal(plan.sessionId, "session-1");
  assert.equal(plan.eventId, "event-1");
  assert.equal(plan.createdAt, "2026-09-30T04:00:00.000Z");
  assert.equal(plan.actions[0]?.kind, "tool_call");
});

test("runtime metadata does not make a missing action valid", async () => {
  await assert.rejects(generateValidatedPlan("prompt", async () => '{"goal":"搜索"}', {
    planId: "model-event-1", sessionId: "session-1", eventId: "event-1",
    createdAt: "2026-09-30T04:00:00.000Z",
  }), /actions/);
});
