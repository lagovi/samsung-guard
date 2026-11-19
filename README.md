# Samsung Guard

Resident Android companion: monitors SMS/calls/device health, triages SMS via LLM, reports to Telegram.

- Phase 1: Kotlin prototype APK (minSdk 23, target 34, ARMv7 + ARM64)
- Phase 2: Rust musl armv7 daemon (root, low RSS)

Owner: Telegram chat 000000000 (@Cyber_Miller_bot)
LLM: http://192.168.1.55:20128/v1 (gemini-flash-lite)
