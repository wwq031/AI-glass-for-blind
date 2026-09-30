import { Ajv2020 } from "ajv/dist/2020.js";
import * as addFormatsModule from "ajv-formats";
import type { ValidateFunction } from "ajv";
import type { FormatsPlugin } from "ajv-formats";
import type { ArgumentValidator } from "../../../../packages/domain/tools/tool-gateway.ts";
import { CONTRACT_SCHEMAS } from "./contract-schemas.generated.ts";

export type JsonSchema = Record<string, unknown> & { $id: string };

/**
 * `ajv-formats` is CommonJS (`module.exports = formatsPlugin`) with only a default export, so the
 * plugin is reached through `.default` on the namespace the bundler hands back. TypeScript's
 * NodeNext interop types that as the module itself rather than the function, hence the assertion.
 */
const addFormats = addFormatsModule.default as unknown as FormatsPlugin;

/**
 * Browser binding of the repository's contract schemas.
 *
 * The domain ToolGateway validates every ToolCall argument against the schema its registry entry
 * names. On the phone those schemas are compiled into the bundle (see tools/build-mobile-contracts
 * .mjs) instead of being read from disk, so the WebView never needs node:fs — while the schemas
 * themselves stay the single source of truth shared with the Node validator.
 */
export function createBrowserArgumentValidator(
  schemas: Readonly<Record<string, JsonSchema>> = CONTRACT_SCHEMAS,
): ArgumentValidator {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);

  const validators = new Map<string, ValidateFunction>();
  const compiled: Array<[string, JsonSchema]> = Object.entries(schemas);
  for (const [, schema] of compiled) ajv.addSchema(schema);
  for (const [name, schema] of compiled) {
    const validate = ajv.getSchema(schema.$id);
    if (!validate) throw new Error(`contract schema did not compile: ${name}`);
    validators.set(name, validate);
  }

  return (schemaPath, value) => {
    // Registry input schemas are paths relative to the registry file; only the file name matters
    // once the schema is compiled in.
    const name = schemaPath.split("/").pop() ?? "";
    const validate = validators.get(name);
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
