import assert from "node:assert/strict";
import { resolve } from "node:path";
import test from "node:test";

import { createLocalArgumentValidator } from "../../apps/gateway/src/tools/local-schema-validator.ts";

const registryPath = resolve("packages/providers/registry/tool-registry.json");

test("runtime argument validator uses the Tool registry's local schema paths", async () => {
  const validate = await createLocalArgumentValidator(registryPath);
  const schemaPath = "../../contracts/schemas/observation-tool-input.schema.json";

  assert.equal(
    validate(schemaPath, {
      capability_id: "vision.entrance",
      capture_mode: "single_frame",
      consent: "explicit",
    }).valid,
    true,
  );
  const invalid = validate(schemaPath, { capability_id: "vision.entrance" });
  assert.equal(invalid.valid, false);
  assert.ok(invalid.errors?.some((message) => message.includes("capture_mode")));
});
