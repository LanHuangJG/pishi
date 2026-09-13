# Pishi（皮实）

[English](README.md) | **中文**

Pishi 是 [美团 Robust](https://github.com/Meituan-Dianping/Robust) 0.4.99 的社区续作 fork，
已为 **AGP 8+** 完成现代化迁移。"皮实"者，耐造扛折腾也——它是 Android 方法级热修复框架，
能把 bug 修复补丁下发给运行中的 App，**无需重新安装**，补丁即时生效、不用重启。

> **状态：0.1.0-alpha。** 全部模块已通过 AGP 8.13 / Gradle 9.5 / JDK 17+ 编译，sample 应用可构建；
> 但「插桩 → 出补丁 → 加载」全链路尚未真机验证，接口可能变动。

## 为什么要 fork

Robust 公开仓库 2020 年起停止维护，其 Gradle 插件依赖的 Transform API 在 **AGP 8 中被彻底移除**，
框架在现代构建下直接不可用（见上游 [issue #434](https://github.com/Meituan-Dianping/Robust/issues/434)）。
社区 fork 最多只适配到 Gradle 7。Pishi 把两个插件迁移到了 AGP 8 真正支持的 API：

| | Robust 0.4.99 | Pishi 0.1.0 |
|---|---|---|
| 字节码插桩 | Transform API（AGP 8 已移除） | **Instrumentation API**（`AsmClassVisitorFactory`） |
| 补丁生成输入 | Transform API | **ScopedArtifacts API**（`ScopedArtifact.CLASSES`） |
| AGP / Gradle | 2.x / jcenter 时代 | **8.13+ / Gradle 8.13–9.5，mavenCentral** |
| Java 工具链 | Java 7 | 插件 Java 17，运行时 Java 8 |
| APK hash 匹配 | 有 | 移除（与主流社区 fork 一致） |
| 出补丁后构建行为 | 抛 `RuntimeException` 终止 | 大声打日志，构建继续 |

其余一切保持原样：同样的 javassist 补丁生成流程、同样的 `changeQuickRedirect` 字段与分发桩
（ASM 路径）、同样的序列化+gzip `methodsMap.robust` 格式、同样的 `robust.xml` 配置项——
你已知的 Robust 经验与工具链可以直接平移。

## 模块

- `autopatchbase` — 运行时 API：`@Modify` / `@Add` 注解、`ChangeQuickRedirect`、反射工具
- `patch` — 运行时加载器：`PatchExecutor`、`Patch`、`PatchManipulate`（构件名 `pishi`）
- `gradle-plugin` — `pishi` 插件：经 Instrumentation API 对 release 构建插桩
- `auto-patch-plugin` — `pishi-autopatch` 插件：为修复后的构建生成 `patch.jar`
- `app` — 示例工程（AGP 8 / AndroidX / compileSdk 36）

## 用法

工作流与 Robust 完全一致：

1. 在 app 模块应用 `pishi` 插件（配置仍在模块目录的 `robust.xml`，`<packname>` 里列出需要插桩的包）。
2. release 构建自动插桩，`build/outputs/robust/` 下生成 `methodsMap.robust`，拷贝到模块的
   `robust/` 目录（与 Robust 相同的手动步骤）。
3. 发补丁时：修改代码，用 `@Modify` / `RobustModify.modify()` 标记改动方法，改用
   `pishi-autopatch` 插件替换 `pishi`，构建即产出 `patch.jar`。

构建示例工程：

```bash
./gradlew publishToMavenLocal          # 一次性：把插件发布到本地仓库
# 然后取消 build.gradle 与 app/build.gradle 中标注处的注释
./gradlew :app:assembleDebug
```

## 环境要求

- JDK 17+（已在 21/25 验证）、AGP **8.13+**、Gradle **8.13–9.5**
- 运行时库要求 `minSdk 21`+

## Roadmap

- [ ] 真机/模拟器验证全链路补丁加载
- [ ] GitHub Actions CI
- [ ] 发布到 Maven Central
- [ ] R8 边界用例矩阵（0.4.99 支持 ProGuard mapping，R8 产物格式大体兼容但未测）
- [ ] 视需要用 AGP 8 artifacts API 恢复 APK-hash 匹配

## 协议与致谢

Apache License 2.0（继承自 Robust）。本项目为独立社区 fork，与美团无隶属或背书关系，
原作品版权归 Meituan-Dianping 所有。
基线 commit：[Meituan-Dianping/Robust@955adcc](https://github.com/Meituan-Dianping/Robust/commit/955adcc21e4fbcb52054a8f7f4bbb11f462aeb2f)。
