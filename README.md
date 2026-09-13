# Pishi (皮实)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lanhuangjg/pishi-gradle-plugin?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.lanhuangjg/pishi-gradle-plugin)

**English** | [中文](README-zh.md)

Pishi is a fork of [Meituan-Dianping/Robust](https://github.com/Meituan-Dianping/Robust) 0.4.99,
modernized for **AGP 8+**. Pishi (皮实, "hardy / takes a beating") is an Android hotfix framework
that lets you fix method-level bugs and ship them to running apps **without reinstalling** —
patches take effect instantly, no restart required.

> **Status: 0.2.0.** Both the instrumented release build and one-command patch generation
> are verified end-to-end in real builds (methodsMap auto-archived per version, patch.jar
> produced from `@Modify`-marked fixes). On-device patch loading is the next milestone.

## Why a fork

Robust's public repository stopped receiving updates in 2020. Its Gradle plugins rely on the
Transform API, which **AGP 8 removed entirely**, so the framework stopped working with modern
builds (see upstream [issue #434](https://github.com/Meituan-Dianping/Robust/issues/434)).
Community forks patched Gradle 7 at best. Pishi migrates both plugins to the APIs AGP 8
actually supports:

| | Robust 0.4.99 | Pishi 0.1.0 |
|---|---|---|
| Bytecode injection | Transform API (removed in AGP 8) | **Instrumentation API** (`AsmClassVisitorFactory`) |
| Patch generation input | Transform API | **ScopedArtifacts API** (`ScopedArtifact.CLASSES`) |
| AGP / Gradle | 2.x / jcenter era | **8.13+ / Gradle 8.13–9.5, mavenCentral** |
| Java toolchain | Java 7 | Java 17 (plugin), Java 8 (runtime) |
| APK hash matching | yes | dropped (like most forks) |
| Patch build behavior | throws `RuntimeException` on success | logs loudly, build continues |

Everything else is intentionally unchanged: the same javassist-based patch generator, the same
`changeQuickRedirect` field and dispatch stubs (ASM path), the same serialized+gzipped
`methodsMap.robust` format, and the same `robust.xml` config surface — so existing Robust
knowledge and tooling transfer directly.

## Modules

- `autopatchbase` — runtime API: `@Modify` / `@Add` annotations, `ChangeQuickRedirect`, reflection utils
- `patch` — runtime loader: `PatchExecutor`, `Patch`, `PatchManipulate` (artifact name `pishi`)
- `gradle-plugin` — the `pishi` plugin: instruments release builds via the Instrumentation API
- `auto-patch-plugin` — the `pishi-autopatch` plugin: generates `patch.jar` for a patched build
- `app` — sample (AGP 8 / AndroidX / compileSdk 36)

## Usage

The workflow is identical to Robust's:

1. Apply `io.github.lanhuangjg.pishi` to your app module and configure it:

```groovy
pishi {
    hotfixPackages = ['com.example.app']   // robust.xml still works for Robust migrants
}
```

2. Release builds get instrumented automatically; `methodsMap.jsonl` and the R8
   `mapping.txt` are **auto-archived to `robust/<versionName>/`** — nothing to remember.
3. Ship a fix: fix the code, mark each changed method with `@Modify` / `RobustModify.modify()`,
   then run **one command**:

```bash
./gradlew assembleRelease -Ppishi.patch=true
```

→ `build/outputs/robust/patch.jar`. Distribute it with your own manifest (the sample's
`PatchManipulateImp` shows the client side: fetch → verify → apply).

Coordinates: `io.github.lanhuangjg:{pishi-gradle-plugin, pishi-autopatch, pishi-api, pishi-core}`.
Plugin ids: `io.github.lanhuangjg.pishi` / `io.github.lanhuangjg.pishi.autopatch`.

Build the sample:

```bash
./gradlew publishToMavenLocal   # one-time: install the plugins locally
./gradlew :app:assembleRelease  # instrumented build
./gradlew :app:assembleRelease -Ppishi.patch=true   # generate a patch instead
```

## Requirements

- JDK 17+ (tested on 21/25), Android Gradle Plugin **8.13+**, Gradle **8.13–9.5**
- `minSdk 21`+ for the runtime artifacts

## Roadmap

- [ ] Verify patch generation + on-device patch loading (instrumentation itself is verified end-to-end in real builds)
- [ ] GitHub Actions CI
- [x] Published to Maven Central (since 0.1.1)
- [x] R8 mapping compatibility (parses R8's `#` JSON metadata comment lines)
- [ ] Built-in SimpleHttpPatchManipulator + patch signing task
- [ ] Optionally restore APK-hash matching via the AGP 8 artifacts API

## License & attribution

Apache License 2.0, inherited from Robust. This project is an independent fork — it is **not**
affiliated with or endorsed by Meituan-Dianping. The original work is Copyright 2017 Meituan-Dianping.
Base commit: [Meituan-Dianping/Robust@955adcc](https://github.com/Meituan-Dianping/Robust/commit/955adcc21e4fbcb52054a8f7f4bbb11f462aeb2f).
