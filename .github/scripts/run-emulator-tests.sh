#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p build/emulator-test-logs
log_dir="build/emulator-test-logs"
adb start-server

expected_sdk="${EXPECTED_API_LEVEL:-}"
if [[ -z "$expected_sdk" ]]; then
  echo "::error::EXPECTED_API_LEVEL must be set by the Android emulator matrix."
  exit 1
fi

wait_for_emulator_ready() {
  local timeout_seconds="${1:-240}"
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local stable_probes=0
  while (( stable_probes < 5 )); do
    local adb_state actual_sdk fingerprint
    adb_state="$(adb -s emulator-5554 get-state 2>/dev/null || true)"
    actual_sdk="$(adb -s emulator-5554 shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
    fingerprint="$(adb -s emulator-5554 shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r' || true)"
    local boot_completed
    boot_completed="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$adb_state" == "device" && "$actual_sdk" == "$expected_sdk" &&
          -n "$fingerprint" && "$boot_completed" == "1" ]] &&
       adb -s emulator-5554 shell service check activity 2>/dev/null | grep -q "found" &&
       adb -s emulator-5554 shell service check package 2>/dev/null | grep -q "found"; then
      stable_probes=$((stable_probes + 1))
    else
      stable_probes=0
      echo "Waiting for stable emulator: adb=$adb_state sdk=$actual_sdk expected=$expected_sdk"
    fi
    if (( $(date +%s) >= deadline )); then
      adb devices -l > "$log_dir/adb-devices-timeout.txt" 2>&1 || true
      adb -s emulator-5554 shell getprop > "$log_dir/emulator-properties-timeout.txt" 2>&1 || true
      echo "::error::Emulator did not remain ready at API $expected_sdk within ${timeout_seconds}s."
      return 1
    fi
    sleep 3
  done
  echo "Emulator is stable and reports API $expected_sdk."
}

if ! timeout 180 adb -s emulator-5554 wait-for-device; then
  adb devices -l > "$log_dir/adb-devices.txt" 2>&1 || true
  echo "::error::Emulator failed to connect to adb within 180 seconds."
  exit 1
fi
wait_for_emulator_ready 300
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global window_animation_scale 0

# Return 0 only when both modules have valid reports and each reports tests.
# Return 2 for empty reports, 3 for real test failures.
validate_reports() {
  python3 - <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

modules = ("editor", "app")
total = 0
empty_modules = []
for module in modules:
    paths = glob.glob(os.path.join(module, "build", "outputs", "androidTest-results", "connected", "debug", "*.xml"))
    if not paths:
        print(f"::warning::No instrumentation XML reports found for :{module}")
        empty_modules.append(module)
        continue
    module_count = 0
    for path in paths:
        try:
            root = ET.parse(path).getroot()
            module_count += int(root.attrib.get("tests", "0"))
            failures = int(root.attrib.get("failures", "0")) + int(root.attrib.get("errors", "0"))
            if failures:
                print(f"::error::Instrumentation report {path} contains {failures} failed/error test(s)")
                sys.exit(3)
        except (ET.ParseError, OSError, ValueError) as exc:
            print(f"::error::Invalid instrumentation report {path}: {exc}")
            sys.exit(1)
    if module_count <= 0:
        print(f"::warning::Instrumentation report for :{module} contains zero tests")
        empty_modules.append(module)
    else:
        print(f":{module}: {module_count} instrumentation tests reported")
    total += module_count
if empty_modules:
    print("Empty instrumentation reports: " + ", ".join(empty_modules))
    sys.exit(2)
print(f"Total instrumentation tests reported: {total}")
PY
}

test_result=1
for attempt in 1 2 3 4; do
  status=0
  # Prevent reports from a previous attempt from being mistaken for this run.
  rm -rf editor/build/outputs/androidTest-results/connected/debug app/build/outputs/androidTest-results/connected/debug
  set +e
  ./gradlew :editor:connectedDebugAndroidTest :app:connectedDebugAndroidTest --continue --max-workers=1 --stacktrace 2>&1 | tee "$log_dir/gradle-attempt-${attempt}.log"
  status=${PIPESTATUS[0]}
  set -e

  report_status=0
  validate_reports || report_status=$?

  if (( status == 0 && report_status == 0 )); then
    test_result=0
    break
  fi

  if grep -Eqi 'HTTP 429|status code 429|Too Many Requests|Could not GET.*repo\.maven\.apache\.org' "$log_dir/gradle-attempt-${attempt}.log"; then
    if (( attempt == 4 )); then
      echo "::error::Maven Central continued returning HTTP 429 after 4 attempts."
      test_result=$status
      break
    fi
    delay=$((attempt * 20))
    echo "::warning::Maven Central rate limited Gradle (attempt ${attempt}/4); retrying in ${delay}s."
    sleep "$delay"
    continue
  fi

  # A zero-test report means the Android test runner did not actually execute
  # tests (often emulator/ADB/Instrumentation startup instability). Retry only
  # this infrastructure condition; real test failures are never retried here.
  if (( report_status == 3 )); then
    echo "::error::Instrumentation reports contain real test failures; not retrying."
    test_result=1
    break
  fi

  if (( report_status == 2 )) && grep -Eqi 'adb: device offline|no devices/emulators found|device not found|Failed to start Emulator console|Broken pipe|INSTRUMENTATION_FAILED|Unable to connect to adb' "$log_dir/gradle-attempt-${attempt}.log"; then
    if (( attempt == 4 )); then
      echo "::error::Instrumentation remained empty after 4 attempts; refusing to accept zero-test reports."
      test_result=1
      break
    fi
    echo "::warning::Instrumentation produced zero tests on attempt ${attempt}/4; reconnecting ADB and retrying."
    adb reconnect offline >/dev/null 2>&1 || true
    adb kill-server >/dev/null 2>&1 || true
    adb start-server || true
    if ! timeout 90 adb -s emulator-5554 wait-for-device; then
      echo "::error::Emulator/ADB unavailable during instrumentation retry."
      test_result=1
      break
    fi
    if ! wait_for_emulator_ready 180; then
      echo "::error::Emulator failed readiness probes during instrumentation retry."
      test_result=1
      break
    fi
    sleep 5
    continue
  fi

  echo "::error::Instrumentation tests failed with executed tests; not retrying."
  test_result=$status
  break
done

adb -s emulator-5554 logcat -d -v threadtime > "$log_dir/logcat.txt" 2>&1 || true
adb -s emulator-5554 shell dumpsys activity activities > "$log_dir/activities.txt" 2>&1 || true
adb devices -l > "$log_dir/adb-devices-final.txt" 2>&1 || true

# Final strict gate: both modules must have non-empty reports and positive counts.
if (( test_result == 0 )); then
  validate_reports || test_result=$?
fi
exit "$test_result"
