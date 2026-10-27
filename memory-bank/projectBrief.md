 # Project Brief

 ## Project
 DDPlayTV is a modular Android TV-first media app built with Kotlin, XML views, MVVM, ARouter, and Gradle multi-module structure.

 ## Core Goals
 - Provide a TV-friendly media experience controlled primarily by DPAD/remote.
 - Compose feature modules through `:app` while keeping reusable foundations in `core_*` modules.
 - Keep feature modules independent; cross-feature collaboration goes through `:core_contract_component` contracts/routing.
 - Support local media, storage/casting, player UI/capabilities, anime search, user/settings, and Bilibili integration.
 - Refactor accumulated feature additions to reduce unclear ownership and maintenance cost while preserving existing functionality and TV behavior (user clarified this priority on 2026-10-27). Judge new layers by the old responsibilities they replace, using comparable source projects as references rather than templates to copy.

 ## Scope
 Main build modules are defined in `settings.gradle.kts`: `:app`, feature modules, Bilibili infra, core modules, data module, and bundled `repository:*` AAR wrappers.

 ## Non-negotiables
 - TV/DPAD UX first: focus must be reachable, visible, restorable, and returnable.
 - Preserve module dependency layering; run `./gradlew verifyModuleDependencies` when dependency edges change.
 - Bilibili media/library work must first consult `E:\Project\PiliPlus` as behavior reference, then adapt to DDPlayTV architecture.
 - Never hard-code secrets; use local/Gradle properties.
 - Do not revert user changes without explicit approval.

 ## Verification Baseline
 - Use Gradle from repo root.
 - Always inspect Gradle output tail and report explicit `BUILD SUCCESSFUL` or `BUILD FAILED`.
 - For TV UI changes, prefer emulator/ADB DPAD smoke verification when feasible.
