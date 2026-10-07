#!/usr/bin/env node
/**
 * Run the offline parser test for `GemmaOutput.transcript`.
 *
 * The phone runtime is Kotlin, but `GemmaOutput.kt` is deliberately Android-free so the parser
 * can be compiled and executed here: this script compiles that one file together with
 * `tests/kotlin/SpeechTranscriptParserTest.kt` and runs the resulting class on the local JVM.
 * No Android SDK, no Gradle project and no device are involved, and no APK is built.
 *
 * `GemmaBridgeException` is declared in `GemmaLocalBridge.kt`, which does import Android, so the
 * runner generates a throwaway declaration with the same shape in a temp directory. That stub is
 * only there to let the Android-free parser compile off-device; the real class is untouched.
 *
 * The Kotlin compiler is taken from an existing Gradle cache (no download). Point
 * LEQI_GRADLE_CACHE at a `.../caches/modules-2/files-2.1` directory to use a different one.
 *
 * The JVM is LEQI_JAVA (an absolute path to the java executable) when it is set, otherwise
 * $JAVA_HOME/bin/java, otherwise `java` from PATH.
 *
 * Usage: node tools/run-speech-parser-tests.mjs
 */
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const PARSER = join(ROOT, "apps/phone-companion/android/src/main/java/com/leqi/experiment/phonebt/GemmaOutput.kt");
const HARNESS = join(ROOT, "tests/kotlin/SpeechTranscriptParserTest.kt");

/** Artifacts the embeddable compiler needs; missing ones are simply skipped. */
const COMPILER_ARTIFACTS = [
  ["org.jetbrains.kotlin", "kotlin-compiler-embeddable"],
  ["org.jetbrains.kotlin", "kotlin-stdlib"],
  ["org.jetbrains.kotlin", "kotlin-reflect"],
  ["org.jetbrains.kotlin", "kotlin-script-runtime"],
  ["org.jetbrains.kotlin", "kotlin-daemon-embeddable"],
  ["org.jetbrains.intellij.deps", "trove4j"],
  ["org.jetbrains", "annotations"],
  // kotlin-compiler-embeddable's IntelliJ core (CoreApplicationEnvironment) needs coroutines at
  // class-load time; without it the compiler dies with NoClassDefFoundError before parsing anything.
  ["org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"],
];

function cacheRoots() {
  const home = homedir();
  return [
    process.env.LEQI_GRADLE_CACHE,
    join(home, ".gradle/caches/modules-2/files-2.1"),
    "D:/leqi-device-experiment/gradle-home/caches/modules-2/files-2.1",
    "D:/leqi-device-experiment/build/gradle-home/caches/modules-2/files-2.1",
  ].filter((root) => root && existsSync(root));
}

/** Version-aware compare so 2.4.20 sorts after 2.2.20. */
function byVersionDescending(a, b) {
  const pa = a.split(/[.-]/);
  const pb = b.split(/[.-]/);
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const na = Number.parseInt(pa[i] ?? "0", 10);
    const nb = Number.parseInt(pb[i] ?? "0", 10);
    if (Number.isNaN(na) || Number.isNaN(nb)) {
      const diff = (pb[i] ?? "").localeCompare(pa[i] ?? "");
      if (diff !== 0) return diff;
    } else if (na !== nb) {
      return nb - na;
    }
  }
  return 0;
}

function jarsFor(group, artifact) {
  const found = [];
  for (const root of cacheRoots()) {
    const artifactDir = join(root, group, artifact);
    if (!existsSync(artifactDir)) continue;
    const versions = readdirSync(artifactDir).sort(byVersionDescending);
    for (const version of versions) {
      const versionDir = join(artifactDir, version);
      for (const hash of readdirSync(versionDir)) {
        const hashDir = join(versionDir, hash);
        for (const name of readdirSync(hashDir)) {
          if (name.endsWith(".jar") && !name.endsWith("-sources.jar")) {
            found.push(join(hashDir, name));
          }
        }
      }
      if (found.length > 0) break;
    }
  }
  return found;
}

const separator = process.platform === "win32" ? ";" : ":";
const compilerJars = [];
let stdlib = null;
for (const [group, artifact] of COMPILER_ARTIFACTS) {
  const jars = jarsFor(group, artifact);
  if (jars.length === 0) continue;
  compilerJars.push(jars[0]);
  if (artifact === "kotlin-stdlib") stdlib = jars[0];
}

if (compilerJars.length === 0 || !stdlib) {
  console.error("找不到 Kotlin 编译器（kotlin-compiler-embeddable / kotlin-stdlib）。");
  console.error("已查找的 Gradle 缓存目录：" + (cacheRoots().join("、") || "（一个都没有）"));
  console.error("可以用 LEQI_GRADLE_CACHE 指向 caches/modules-2/files-2.1 目录。");
  process.exit(2);
}

/**
 * The JVM to compile and run with.
 *
 * Written as statements, not as one nested `??`/`?:` expression: `a ?? b ? c : d` parses as
 * `(a ?? b) ? c : d`, which silently evaluates the JAVA_HOME branch even when LEQI_JAVA is set
 * (and throws when JAVA_HOME is empty). An explicitly configured path must win outright.
 */
function javaExecutable() {
  const explicit = (process.env.LEQI_JAVA ?? "").trim();
  if (explicit.length > 0) return explicit;
  const home = (process.env.JAVA_HOME ?? "").trim();
  return home.length > 0 ? join(home, "bin", "java") : "java";
}

const java = javaExecutable();
const workDir = mkdtempSync(join(tmpdir(), "leqi-speech-parser-"));
const outDir = join(workDir, "classes");
mkdirSync(outDir);

const stub = join(workDir, "GemmaBridgeExceptionStub.kt");
writeFileSync(
  stub,
  [
    "package com.leqi.experiment.phonebt",
    "",
    "// 由 tools/run-speech-parser-tests.mjs 生成：真身声明在依赖 Android 的 GemmaLocalBridge.kt 里。",
    "class GemmaBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)",
    "",
  ].join("\n"),
);

try {
  const compile = spawnSync(
    java,
    [
      "-cp",
      compilerJars.join(separator),
      "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
      "-no-stdlib",
      "-nowarn",
      "-classpath",
      stdlib,
      "-d",
      outDir,
      PARSER,
      stub,
      HARNESS,
    ],
    { encoding: "utf8" },
  );
  if (compile.error) throw compile.error;
  if (compile.status !== 0) {
    console.error(compile.stdout ?? "");
    console.error(compile.stderr ?? "");
    console.error("Kotlin 编译失败，测试没有运行。");
    process.exit(1);
  }

  const run = spawnSync(
    java,
    ["-Dfile.encoding=UTF-8", "-cp", [outDir, stdlib].join(separator), "com.leqi.experiment.phonebt.SpeechTranscriptParserTestKt"],
    { encoding: "utf8" },
  );
  if (run.error) throw run.error;
  for (const line of (run.stdout ?? "").split(/\r?\n/)) {
    if (line.trim().length > 0) console.log(line);
  }
  if ((run.stderr ?? "").trim().length > 0) console.error(run.stderr);
  if (run.status !== 0) {
    console.error("语音转写解析器测试失败。");
    process.exit(1);
  }
  console.log("语音转写解析器测试全部通过。");
} finally {
  rmSync(workDir, { recursive: true, force: true });
}
