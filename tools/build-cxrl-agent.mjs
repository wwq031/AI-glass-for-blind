import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
if (existsSync(resolve(root, ".env"))) process.loadEnvFile(resolve(root, ".env"));
const { values } = parseArgs({ options: {
  "code-only": { type: "boolean", default: false },
  "model-apk": { type: "string" }, "model-manifest": { type: "string" },
} });
const gradle = process.env.GRADLE_EXECUTABLE || "gradle";
const args = ["-p", resolve(root, "apps/device-experiment/android"),
  "-PcxrlProbeOnly=true", `-PleqiSourceRevision=${spawnSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).stdout.trim()}`];
if (values["code-only"]) args.push("-PcxrlCodeOnly=true");
else args.push(
  `-PcxrlModelApk=${resolve(root, values["model-apk"] || "工作区/phone-l-speech-v0.4.2.apk")}`,
  `-PcxrlModelManifest=${resolve(root, values["model-manifest"] || "工作区/manifest-v0.4.2.json")}`,
);
if (process.env.AMAP_ANDROID_KEY) args.push(`-PamapAndroidKey=${process.env.AMAP_ANDROID_KEY}`);
args.push(":phone-l:testDebugUnitTest", ":glasses:testDebugUnitTest", ":phone-l:assembleDebug", ":glasses:assembleDebug", "--console=plain");
// Windows 的 Gradle 启动器是 .bat：拒绝 shell 元字符，只允许作为单个引用参数传递。
const quote = value => {
  if (/["&|<>^%!\r\n]/.test(value)) throw new Error("构建参数含不支持的字符");
  return `"${value}"`;
};
const result = process.platform === "win32"
  ? spawnSync(`${quote(gradle)} ${args.map(quote).join(" ")}`, { cwd: root, shell: true, stdio: "inherit" })
  : spawnSync(gradle, args, { cwd: root, stdio: "inherit" });
if (result.error) throw new Error("无法启动 Gradle，请检查 .env 中的 GRADLE_EXECUTABLE");
process.exitCode = result.status ?? 1;
