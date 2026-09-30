import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
await build({
  entryPoints: [join(root, "apps/phone-companion/src/android/agent-browser.ts")],
  outfile: join(root, "apps/phone-companion/android/src/main/assets/agent/agent.js"),
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "chrome120",
});
