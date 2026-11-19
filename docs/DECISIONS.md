# DECISIONS — Samsung Guard

Record of architectural decisions and their rationale. English only, per project rule.

## D-01: Phase 1 = Kotlin prototype; Phase 2 = Rust musl armv7 daemon
**Decision:** Build a Kotlin APK first to validate the product loop on the live phone, then rewrite the resident core in Rust for minimal RAM.
**Rationale:** The phone is a 2015 Galaxy J5 (1.42 GB RAM, ARMv7). A Kotlin/ART process costs ~24–31 MB PSS just for the runtime; a static Rust ELF can run at ~2–5 MB PSS. Prototype first lets us validate SMS/LLM/Telegram flows without fighting the NDK toolchain; Rust final satisfies the "maximally economical" requirement.

## D-02: Foreground Service as the resident anchor
**Decision:** `GuardService` runs as a foreground service with a persistent notification (`isForeground=true`).
**Rationale:** Android 7.1.1 aggressively kills background processes, especially on this custom SEPerience ROM. Foreground service + whitelist is the only reliable way to keep the SMS/call monitor alive. Battery cost is negligible because the service sleeps most of the time.

## D-03: Owner confirmation before any SMS mutation
**Decision:** Initially LLM only *recommends* keep/delete/alert; the owner executes via Telegram inline buttons. Auto-delete/archive comes later after debugging.
**Rationale:** SMS contains personal/financial data. An erroneous LLM verdict deleting a bank code or MCHS alert is unacceptable. The button flow (keep:N / del:N) gives the owner full control while the prototype is being validated.

## D-04: SQLite store for SMS, verdicts, and state
**Decision:** All received SMS are persisted in `guard.db` with `verdict`, `llm_reply`, `handled` columns.
**Rationale:** Survives process restarts; gives an audit trail; enables the Telegram poller to apply owner decisions to the correct row by ID. Durable queue beats fire-and-forget.

## D-05: Long-poll Telegram `getUpdates` for callback queries
**Decision:** `TelegramPoller` runs a background thread doing long-poll (timeout=50 s) to receive `callback_query` updates from the owner.
**Rationale:** Simplest way to implement buttons without running a webhook server on the phone. The phone has no public IP, so inbound webhooks are impossible; polling outbound HTTPS works fine on Wi-Fi.

## D-06: JobScheduler abandoned; single-thread executor used instead
**Decision:** First tried `JobScheduler` to outlive the `BroadcastReceiver.goAsync()` window (~30 s). It never fired on this ROM. Reverted to a single-thread executor in the app process.
**Rationale:** The custom kernel / SEPerience ROM appears to suppress background jobs. The executor thread in the foreground service is good enough: LLM + Telegram calls take <60 s, well within the service lifetime.

## D-07: GitHub Actions for builds; local machine has no Java/SDK
**Decision:** CI builds the APK on `ubuntu-latest` via `actions/setup-java` + `actions/setup-android` + `sdkmanager`. APK artifact downloaded back to the Linux host for `adb install`.
**Rationale:** The local PC disk is 87% full and lacks Java/Android SDK. The CI runner has them preinstalled. Workflow pinned to `packages: platform-tools` (the `tools` package was removed from the SDK repo — see GOTCHAS).

## D-08: LLM triage vocabulary normalized to keep/delete/alert
**Decision:** The model may answer `spam`, `junk`, `important`, `urgent`, etc. All are mapped to the three buckets.
**Rationale:** `gemini-flash-lite` is not constrained to our schema; normalization prevents surprises when applying actions.

## D-09: Telegram owner = chat_id 000000000 (Igor); bot @Cyber_Miller_bot
**Decision:** All alerts, health reports, and button prompts go to this chat.
**Rationale:** Single owner, single channel. Viber monitoring is explicitly deferred to the backlog per user request.

---

## Open questions
- Auto-execution: when the owner approves, should LLM gain unsupervised delete/archive rights? (deferred)
- Viber monitoring: explicitly not started; user will ask when needed.
