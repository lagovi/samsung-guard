# GOTCHAS — Samsung Guard

Pitfalls encountered during development. English only, per project rule.

## G-01: `setup-android` default package `tools` is removed from the SDK repo
**Symptom:** CI fails with `Warning: Failed to find package 'tools'` inside `sdkmanager tools`.  
**Fix:** In `.github/workflows/build.yml` set `packages: platform-tools` for `actions/setup-android`, then install the rest manually via `sdkmanager --install "platforms;android-34" "build-tools;34.0.0"`.  
**Source:** `docs/ANDROID-CI-BUILDS.md` (same project, same gotcha).

## G-02: `gh` wrapper (`mise`) is slow and lacks `workflow` scope
**Symptom:** `gh auth status` / pushes hang for ~60 s; `git push` rejected with "OAuth App ... without `workflow` scope".  
**Fix:** Use the real binary directly:
`/home/uzzzver/.local/share/mise/installs/gh/2.102.1/gh_2.102.1_linux_amd64/bin/gh`  
It is already logged in as `lagovi`. Run `gh auth refresh -s workflow` once to add the `workflow` scope so workflow files can be pushed.

## G-03: `BroadcastReceiver.goAsync()` window (~30 s) is shorter than one LLM call
**Symptom:** LLM answers in ~11–29 s; plus retry backoff (5 s, 10 s) can exceed 30 s. The receiver process may be killed mid-pipeline.  
**Fix:** First tried `JobScheduler` — it never fired on this ROM (custom kernel / SEPerience suppresses background jobs). Reverted to a single-thread executor running inside the foreground service, which outlives the receiver window.

## G-04: `gemini-flash-lite` wraps JSON in markdown fences
**Symptom:** `JSONObject(content)` throws `JSONException: Value ```json of type java.lang.String cannot be converted to JSONObject`.  
**Fix:** Strip fences before parsing: find first `{` and last `}`, return that substring. Also normalize model vocabulary (`spam` → `delete`, `urgent` → `alert`, etc.).

## G-05: Empty LLM `content`
**Symptom:** Provider occasionally returns HTTP 200 with no `choices[].message.content` → `JSONException: End of input at character 0`.  
**Fix:** Guard with `optJSONArray("choices")`, `optString("content", "")`, blank check; on failure fall back to `alert` verdict with owner notification after 3 retries.

## G-06: Network security config for cleartext HTTP
**Symptom:** `Cleartext HTTP traffic to 192.168.1.55 not permitted` on API 24+ targets.  
**Fix:** Added `res/xml/network_security_config.xml` permitting cleartext for `192.168.1.55` and `api.telegram.org`, plus `android:usesCleartextTraffic="true"`.

## G-07: `/proc/meminfo` lacks `MemAvailable` on this kernel
**Symptom:** Health report printed `RAM: 0/1389 MB free`.  
**Fix:** Fallback: `MemFree + Buffers + Cached` when `MemAvailable` is missing or zero.

## G-08: Local PC disk is 87% full; `/tmp` is tmpfs (in RAM)
**Symptom:** Installing Android SDK locally downloads ~1 GB and fills the disk.  
**Fix:** Do all builds with JDK + SDK on the external disk `/run/media/uzzzver/Main/guardbuild/` (52 GB free), with `GRADLE_USER_HOME` there too. CI handles the actual release builds.

## G-09: `FOREGROUND_SERVICE` permission is normal, not runtime
**Symptom:** `pm grant ... FOREGROUND_SERVICE` fails with "Unknown permission".  
**Fix:** It is granted at install time for targetSdk < 34-style declarations; no runtime grant needed. Only dangerous permissions (`READ_SMS`, `READ_CALL_LOG`, etc.) require `pm grant`.

## G-10: No SIM in the test phone right now
**Symptom:** `gsm.sim.state = ABSENT,ABSENT`; no real SMS or calls arrive.  
**Fix:** `DemoSms` generates 6 test scenarios (bank code, operator spam, MCHS alert, personal, etc.) for LLM/Telegram validation. Real SIM test is pending owner action.
