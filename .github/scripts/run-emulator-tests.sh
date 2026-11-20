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
test_result=1
for attempt in 1 2 3 4; do
  status=0
  set +e
  ./gradlew :editor:connectedDebugAndroidTest :app:connectedDebugAndroidTest --continue --max-workers=1 --stacktrace 2>&1 | tee "$log_dir/gradle-attempt-${attempt}.log"
  status=${PIPESTATUS[0]}
  set -e
  if (( status == 0 )); then test_result=0; break; fi
  if ! grep -Eqi 'HTTP 429|status code 429|Too Many Requests|Could not GET.*repo\.maven\.apache\.org' "$log_dir/gradle-attempt-${attempt}.log"; then
    echo "::error::Instrumentation tests failed for a non-rate-limit reason; not retrying."
    test_result=$status
    break
  fi
  if (( attempt == 4 )); then
    echo "::error::Maven Central continued returning HTTP 429 after 4 attempts."
    test_result=$status
    break
  fi
  delay=$((attempt * 20))
  echo "::warning::Maven Central rate limited Gradle (attempt ${attempt}/4); retrying in ${delay}s."
  sleep "$delay"
done
adb -s emulator-5554 logcat -d -v threadtime > "$log_dir/logcat.txt" 2>&1 || true
adb -s emulator-5554 shell dumpsys activity activities > "$log_dir/activities.txt" 2>&1 || true
adb devices -l > "$log_dir/adb-devices-final.txt" 2>&1 || true

# Gradle can return success even when an instrumentation task discovers no tests.
# Require non-empty XML reports for both modules and a positive test count.
if (( test_result == 0 )); then
  report_guard=0
  python3 - <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

modules = ("editor", "app")
total = 0
for module in modules:
    paths = glob.glob(os.path.join(module, "build", "outputs", "androidTest-results", "connected", "debug", "*.xml"))
    if not paths:
        print(f"::error::No instrumentation XML reports found for :{module}")
        sys.exit(1)
    module_count = 0
    for path in paths:
        try:
            root = ET.parse(path).getroot()
            module_count += int(root.attrib.get("tests", "0"))
        except (ET.ParseError, OSError, ValueError) as exc:
            print(f"::error::Invalid instrumentation report {path}: {exc}")
            sys.exit(1)
    if module_count <= 0:
        print(f"::error::Instrumentation report for :{module} contains zero tests")
        sys.exit(1)
    print(f":{module}: {module_count} instrumentation tests reported")
    total += module_count
print(f"Total instrumentation tests reported: {total}")
PY
  report_guard=$?
  if (( report_guard != 0 )); then
    test_result=$report_guard
  fi
fi

exit "$test_result"
