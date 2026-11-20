#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p build/emulator-test-logs
log_dir="build/emulator-test-logs"
adb start-server
if ! timeout 180 adb -s emulator-5554 wait-for-device; then
  adb devices -l > "$log_dir/adb-devices.txt" 2>&1 || true
  echo "::error::Emulator failed to connect to adb within 180 seconds."
  exit 1
fi
for boot_attempt in $(seq 1 60); do
  boot_completed="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" == "1" ]]; then break; fi
  if (( boot_attempt == 60 )); then
    adb -s emulator-5554 shell getprop > "$log_dir/emulator-properties.txt" 2>&1 || true
    adb devices -l > "$log_dir/adb-devices.txt" 2>&1 || true
    echo "::error::Emulator boot did not complete within 120 seconds."
    exit 1
  fi
  sleep 2
done
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global window_animation_scale 0

# Return 0 only when both modules have valid reports and each reports tests.
# Return 2 for a transient empty-report/instrumentation-startup condition.
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
  if (( report_status == 2 )); then
    if (( attempt == 4 )); then
      echo "::error::Instrumentation remained empty after 4 attempts; refusing to accept zero-test reports."
      test_result=1
      break
    fi
    echo "::warning::Instrumentation produced zero tests on attempt ${attempt}/4; reconnecting ADB and retrying."
    adb start-server || true
    if ! timeout 60 adb -s emulator-5554 wait-for-device; then
      echo "::error::Emulator/ADB unavailable during zero-test retry."
      test_result=1
      break
    fi
    for boot_attempt in $(seq 1 30); do
      boot_completed="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
      [[ "$boot_completed" == "1" ]] && break
      sleep 2
    done
    sleep 10
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
