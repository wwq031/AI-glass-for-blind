import { readFile, readdir, access } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schemasDirectory = join(root, "packages", "contracts", "schemas");
const capabilitySchema = join(
  root,
  "packages",
  "contracts",
  "capabilities",
  "traffic-signal-facts.schema.json",
);
const capabilityRegistryPath = join(root, "packages", "contracts", "capabilities", "registry.json");
const toolRegistryPath = join(root, "packages", "providers", "registry", "tool-registry.json");

const exampleSchemas = new Map([
  ["speech-input-destination.json", "speech-input.schema.json"],
  ["destination-candidates.json", "destination-candidates.schema.json"],
  ["navigation-intersection-approaching.json", "navigation-event.schema.json"],
  ["device-event-button.json", "device-event.schema.json"],
  ["observation-result-intersection.json", "observation-result.schema.json"],
  ["crossing-advisory-recheck.json", "crossing-advisory.schema.json"],
  ["contract-error-capture-failed.json", "contract-error.schema.json"],
]);

async function loadJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function formatErrors(errors = []) {
  return errors
    .map(({ instancePath, message, params }) =>
      `  ${instancePath || "/"} ${message ?? "validation failed"} ${JSON.stringify(params)}`,
    )
    .join("\n");
}

async function assertReferencedFiles(registry, registryPath, fields) {
  for (const item of registry) {
    for (const field of fields) {
      const reference = item[field];
      if (!reference) continue;
      if (/^[a-z]+:\/\//i.test(reference)) {
        throw new Error(`${relative(root, registryPath)}: ${item.id ?? item.tool_id}.${field} must be a local path`);
      }
      await access(resolve(dirname(registryPath), reference));
    }
  }
}

const schemaFiles = (await readdir(schemasDirectory))
  .filter((name) => name.endsWith(".schema.json"))
  .sort();
const schemaPaths = [...schemaFiles.map((name) => join(schemasDirectory, name)), capabilitySchema];
const schemas = await Promise.all(schemaPaths.map(loadJson));

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

for (const schema of schemas) ajv.addSchema(schema);
for (const schema of schemas) ajv.getSchema(schema.$id);

const capabilityRegistry = await loadJson(capabilityRegistryPath);
const capabilityRegistryValidator = ajv.getSchema(
  "https://leqi-ai-glasses.dev/contracts/capability-registry.schema.json",
);
if (!capabilityRegistryValidator(capabilityRegistry)) {
  throw new Error(`Capability registry is invalid:\n${formatErrors(capabilityRegistryValidator.errors)}`);
}
await assertReferencedFiles(capabilityRegistry.capabilities, capabilityRegistryPath, [
  "request_schema",
  "result_schema",
  "voice_templates",
]);

const toolRegistry = await loadJson(toolRegistryPath);
const toolRegistryValidator = ajv.getSchema(
  "https://leqi-ai-glasses.dev/contracts/tool-registry.schema.json",
);
if (!toolRegistryValidator(toolRegistry)) {
  throw new Error(`Tool registry is invalid:\n${formatErrors(toolRegistryValidator.errors)}`);
}
await assertReferencedFiles(toolRegistry.tools, toolRegistryPath, ["input_schema", "output_schema"]);

for (const [exampleName, schemaName] of exampleSchemas) {
  const examplePath = join(root, "packages", "contracts", "examples", exampleName);
  const example = await loadJson(examplePath);
  const schema = schemas.find(({ $id }) => $id.endsWith(`/${schemaName}`));
  const validate = ajv.getSchema(schema.$id);
  if (!validate(example)) {
    throw new Error(`${exampleName} is invalid:\n${formatErrors(validate.errors)}`);
  }
}

console.log(
  `Validated ${schemas.length} schemas, ${exampleSchemas.size} examples, ` +
    `${capabilityRegistry.capabilities.length} capabilities, and ${toolRegistry.tools.length} tools.`,
);
