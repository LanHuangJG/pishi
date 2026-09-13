# Pishi (皮实)

**English** | [中文](README-zh.md)

Pishi is a fork of [Meituan-Dianping/Robust](https://github.com/Meituan-Dianping/Robust) 0.4.99,
modernized for **AGP 8+**. Pishi (皮实, "hardy / takes a beating") is an Android hotfix framework
that lets you fix method-level bugs and ship them to running apps **without reinstalling** —
patches take effect instantly, no restart required.

> **Status: 0.1.0-alpha.** All modules compile against AGP 8.13 / Gradle 9.5 / JDK 17+,
> and the sample app builds, but the full patch round-trip (instrument → patch → load)
> has not been verified on a device yet. Expect breaking changes.

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

1. Apply `pishi` to your app module (config lives in `robust.xml`, same format as Robust —
   list your packages under `<packname>`).
2. Release builds get instrumented automatically; a `methodsMap.robust` is written to
   `build/outputs/robust/`. Copy it to your module's `robust/` directory (same manual step
   as Robust).
3. To ship a fix: apply your patch, mark modified methods with `@Modify` / `RobustModify.modify()`,
   apply `pishi-autopatch` instead of `pishi`, and build — it produces `patch.jar`.

Build the sample:

```bash
./gradlew publishToMavenLocal          # one-time, publishes the plugins
# then uncomment the marked lines in build.gradle + app/build.gradle
./gradlew :app:assembleDebug
```

## Requirements

- JDK 17+ (tested on 21/25), Android Gradle Plugin **8.13+**, Gradle **8.13–9.5**
- `minSdk 21`+ for the runtime artifacts

## Roadmap

- [ ] Verify the full patch round-trip on a device/emulator
- [ ] CI (GitHub Actions) building all modules
- [ ] Publish to Maven Central
- [ ] R8 edge-case matrix (upstream 0.4.99 handles ProGuard maps; R8 output is largely compatible but untested)
- [ ] Consider restoring APK-hash matching via the AGP 8 artifacts API

## License & attribution

Apache License 2.0, inherited from Robust. This project is an independent fork — it is **not**
affiliated with or endorsed by Meituan-Dianping. The original work is Copyright 2017 Meituan-Dianping.
Base commit: [Meituan-Dianping/Robust@955adcc](https://github.com/Meituan-Dianping/Robust/commit/955adcc21e4fbcb52054a8f7f4bbb11f462aeb2f).
