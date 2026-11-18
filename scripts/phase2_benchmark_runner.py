import subprocess
import time
import json
import os
import re

ADB_PATH = r"C:\Users\msmay\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEVICE_ID = "adb-IZRC6H8LEQCAFYMR-VUlk4u._adb-tls-connect._tcp"
ARTIFACT_DIR = r"C:\Users\msmay\.gemini\antigravity-ide\brain\42b82130-4e56-435b-ac55-55b944cbda77"
SCREENSHOT_DIR = os.path.join(ARTIFACT_DIR, ".tempmediaStorage")

TEST_DATASET = [
    # Single Photos
    ("Single-Photo 1", "Photo", "https://www.instagram.com/p/DUpiJzVjHYP/"),
    ("Single-Photo 2", "Photo", "https://www.instagram.com/p/DV_AEYKjKyR/"),
    ("Single-Photo 3", "Photo", "https://www.instagram.com/p/Dcu9ZC0A_vk/"),
    # Carousels
    ("Carousel 1", "Carousel", "https://www.instagram.com/p/Dcf1nCmjwre/"),
    ("Carousel 2", "Carousel", "https://www.instagram.com/p/DcdhmVkmBps/"),
    ("Carousel 3", "Carousel", "https://www.instagram.com/p/DcaXml7ClY2/"),
    # Reels
    ("Reel 1", "Reel", "https://www.instagram.com/reel/DcWSrBiBieA/"),
    ("Reel 2", "Reel", "https://www.instagram.com/reel/DcY8eF9s4JY/"),
    ("Reel 3", "Reel", "https://www.instagram.com/reel/DcylaursOxC/"),
    # Adversarial / Edge cases
    ("Deleted Post", "Deleted", "https://www.instagram.com/p/ZZZdeletedZZZ/"),
    ("Invalid URL", "Invalid", "https://example.com/not_an_insta_url"),
]

def run_adb(args):
    cmd = [ADB_PATH, "-s", DEVICE_ID] + args
    res = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    return res.stdout.strip()

def clear_logcat():
    run_adb(["logcat", "-c"])

def get_resolver_log(expected_url=None):
    out = run_adb(["logcat", "-s", "RESOLVER_TEST", "-d"])
    for line in reversed(out.splitlines()):
        if "RESULT_JSON:" in line:
            json_str = line.split("RESULT_JSON:", 1)[1].strip()
            try:
                data = json.loads(json_str)
                if expected_url is None or data.get("originalInputUrl") == expected_url:
                    return data
            except Exception as e:
                print(f"Failed to parse JSON line: {e}")
    return None

def capture_screenshot(filename):
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    device_path = f"/sdcard/{filename}"
    local_path = os.path.join(SCREENSHOT_DIR, filename)
    run_adb(["shell", "screencap", "-p", device_path])
    run_adb(["pull", device_path, local_path])
    return local_path

def main():
    print("================================================================================")
    print("PHASE 2 EMPIRICAL RESOLVER BENCHMARK ON PHYSICAL ANDROID 16 DEVICE")
    print(f"Device: {DEVICE_ID}")
    print(f"Total Test Cases: {len(TEST_DATASET)}")
    print("================================================================================\n")

    results = []

    for idx, (label, category, url) in enumerate(TEST_DATASET, start=1):
        print(f"--------------------------------------------------------------------------------")
        print(f"[{idx}/{len(TEST_DATASET)}] Testing {label} ({category}): {url}")
        
        # Fresh activity launch per test
        run_adb(["shell", "am", "force-stop", "com.instadown.assist"])
        time.sleep(0.3)
        clear_logcat()

        # Launch via ADB intent
        run_adb([
            "shell", "am", "start",
            "-n", "com.instadown.assist/.ui.ResolverTestActivity",
            "--es", "TEST_URL", url,
            "--ez", "VALIDATE_CDN", "true"
        ])

        # Poll logcat for result
        start_wait = time.time()
        telemetry = None
        while time.time() - start_wait < 16.0:
            time.sleep(0.4)
            telemetry = get_resolver_log(expected_url=url)
            if telemetry:
                break

        if not telemetry:
            print("  FAILED: Timed out waiting for resolver telemetry in logcat")
            record = {
                "test_num": idx,
                "label": label,
                "category": category,
                "input_url": url,
                "success": False,
                "timeout": True,
                "error": "Timeout waiting for logcat telemetry"
            }
        else:
            success = telemetry.get("success", False)
            is_partial = telemetry.get("isPartial", False)
            shortcode = telemetry.get("shortcode")
            post_type = telemetry.get("postType")
            method = telemetry.get("methodUsed")
            total_ms = telemetry.get("totalResolutionTimeMs", 0)
            stageA = telemetry.get("stageA") or {}
            stageB = telemetry.get("stageB") or {}
            items = telemetry.get("items", [])
            err = telemetry.get("error")

            # Verify shortcode match
            expected_shortcode = None
            m = re.search(r'/(p|reel|reels|tv)/([^/?#&]+)', url)
            if m:
                expected_shortcode = m.group(2)
            shortcode_matched = (expected_shortcode == shortcode) if expected_shortcode else False

            # Verify path type preservation
            expected_path = m.group(1).lower() if m else None
            actual_path = telemetry.get("sourcePathType")
            path_preserved = (expected_path == actual_path) if expected_path else False

            print(f"  Result: {'SUCCESS' if success and not is_partial else ('PARTIAL' if is_partial else 'FAILED')}")
            print(f"  Canonical URL: {telemetry.get('canonicalUrl')} (Path Preserved: {path_preserved})")
            print(f"  Shortcode: requested='{expected_shortcode}' == resolved='{shortcode}' -> Match: {shortcode_matched}")
            print(f"  Identity Verification: {telemetry.get('identityVerificationMethod')}")
            print(f"  Post Type: {post_type} | Items Count: {len(items)}")
            print(f"  Stage A (Extraction): Success={stageA.get('success')}, Latency={stageA.get('extractionTimeMs')}ms")
            print(f"  Stage B (CDN Check): Success={stageB.get('success')}, Accessible={stageB.get('allAccessible')}, Items={stageB.get('itemsValidated')}, Latency={stageB.get('validationTimeMs')}ms")
            print(f"  Total Latency: {total_ms}ms")

            if items:
                print("  Media Items Summary:")
                for item in items:
                    cdn = item.get("cdnValidation") or {}
                    cdn_status = f"HTTP {cdn.get('httpStatusCode')}, {cdn.get('contentType')}, Len={cdn.get('contentLength')}" if cdn else "No CDN check"
                    print(f"    Slide [{item.get('index')}] - {item.get('mediaType')} | {item.get('width')}x{item.get('height')} | Quality: {item.get('qualityClassification')} | Reason: {item.get('selectionReason')}")
                    print(f"      Candidates: {item.get('candidatesCount')} | CDN Status: {cdn_status}")

            if err:
                print(f"  Error: {err.get('code')} - {err.get('message')}")

            screenshot_name = f"phase2_test_{idx}_{category.lower()}.png"
            capture_screenshot(screenshot_name)

            record = {
                "test_num": idx,
                "label": label,
                "category": category,
                "input_url": url,
                "canonical_url": telemetry.get("canonicalUrl"),
                "expected_shortcode": expected_shortcode,
                "resolved_shortcode": shortcode,
                "shortcode_matched": shortcode_matched,
                "expected_path_type": expected_path,
                "resolved_path_type": actual_path,
                "path_preserved": path_preserved,
                "success": success,
                "is_partial": is_partial,
                "post_type": post_type,
                "identity_method": telemetry.get("identityVerificationMethod"),
                "method_used": method,
                "items_count": len(items),
                "items": items,
                "stage_a": stageA,
                "stage_b": stageB,
                "total_resolution_time_ms": total_ms,
                "error": err,
                "screenshot": screenshot_name
            }

        results.append(record)
        time.sleep(1.0)

    # Save complete benchmark dataset
    out_file = os.path.join(ARTIFACT_DIR, "phase2_benchmark_results.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    print("\n================================================================================")
    print(f"BENCHMARK COMPLETE! Results saved to {out_file}")
    print("================================================================================")

if __name__ == "__main__":
    main()
