import { SkillRegistry, type SkillDefinition } from "../agent/types.ts";
import manifest from "../../contracts/skills/registry.json" with { type: "json" };

export function createP0SkillRegistry(): SkillRegistry {
  const skills: SkillDefinition[] = manifest.skills.map((skill) => ({
    skillId: skill.skill_id,
    riskLevel: skill.risk_level as SkillDefinition["riskLevel"],
    allowedTools: skill.allowed_tools,
    ...("required_policy" in skill ? { requiredPolicy: skill.required_policy } : {}),
  }));
  return new SkillRegistry(skills);
}
