import { SkillRegistry, type SkillDefinition } from "../agent/types.ts";

const P0_SKILLS: SkillDefinition[] = [
  {
    skillId: "navigate_to",
    riskLevel: "low",
    allowedTools: [
      "navigation.search_destination",
      "navigation.confirm_destination",
      "navigation.start",
      "speech.ask_user",
    ],
  },
  {
    skillId: "inspect_scene",
    riskLevel: "low",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
  },
  {
    skillId: "read_text",
    riskLevel: "low",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
  },
  {
    skillId: "find_target",
    riskLevel: "low",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
  },
  {
    skillId: "follow_up",
    riskLevel: "low",
    allowedTools: ["facts.query", "speech.ask_user", "session.cancel"],
  },
  {
    skillId: "crossing_advisory",
    riskLevel: "high",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
    requiredPolicy: "crossing-advisory",
  },
  {
    skillId: "obstacle_advisory",
    riskLevel: "high",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
    requiredPolicy: "obstacle-advisory",
  },
  {
    skillId: "menu_structuring",
    riskLevel: "medium",
    allowedTools: ["observation.request", "facts.query", "speech.ask_user"],
    requiredPolicy: "menu-structuring",
  },
];

export function createP0SkillRegistry(): SkillRegistry {
  return new SkillRegistry(P0_SKILLS);
}
