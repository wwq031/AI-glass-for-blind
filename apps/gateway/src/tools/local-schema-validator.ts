import { readFile, readdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join, normalize, resolve } from "node:path";

import type { ValidateFunction } from "ajv";

import type { ArgumentValidator } from "../../../../packages/domain/tools/tool-gateway.ts";

const require = createRequire(import.meta.url);
const Ajv2020 = require("ajv/dist/2020").default as new (options: object) => {
  addSchema(schema: object): void;
  getSchema(id: string): ValidateFunction | undefined;
};
const addFormats = require("ajv-formats").default as (ajv: object) => void;

/** Builds a runtime validator for the local schema paths stored in a Tool registry. */
export async function createLocalArgumentValidator(registryPath: string): Promise<ArgumentValidator> {
  const contractsDirectory = resolve(dirname(registryPath), "../../contracts");
  const schemaDirectory = join(contractsDirectory, "schemas");
  const schemaNames = (await readdir(schemaDirectory)).filter((name) => name.endsWith(".schema.json"));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);

  const validators = new Map<string, ValidateFunction>();
  const loaded: Array<{ path: string; schema: { $id: string } }> = [];
  for (const name of schemaNames) {
    const path = join(schemaDirectory, name);
    const schema = JSON.parse(await readFile(path, "utf8")) as { $id: string };
    ajv.addSchema(schema);
    loaded.push({ path: normalize(path), schema });
  }
  for (const entry of loaded) validators.set(entry.path, ajv.getSchema(entry.schema.$id)!);

  return (schemaPath, value) => {
    const absolutePath = normalize(resolve(dirname(registryPath), schemaPath));
    const validate = validators.get(absolutePath);
    if (!validate) return { valid: false, errors: [`Schema is not loaded: ${schemaPath}`] };
    const valid = validate(value);
    return {
      valid: Boolean(valid),
      errors: (validate.errors ?? []).map(
        ({ instancePath, message }) => `${instancePath || "/"} ${message ?? "is invalid"}`,
      ),
    };
  };
}
