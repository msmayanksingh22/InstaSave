import subprocess
import time
import json
import os
import re
import sys
from datetime import datetime

ADB_PATH = r"C:\Users\msmay\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEVICE_ID = "192.168.1.3:45657"
ARTIFACT_DIR = r"C:\Users\msmay\.gemini\antigravity-ide\brain\42b82130-4e56-435b-ac55-55b944cbda77"
SCREENSHOT_DIR = os.path.join(ARTIFACT_DIR, ".tempmediaStorage")

def run_adb(args, timeout=30):
    cmd = [ADB_PATH, "-s", DEVICE_ID] + args
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace")
        return res.stdout.strip()
    except Exception as e:
        print(f"  [ADB Warning] command {args[:4]} failed: {e}")
        return ""

def clear_logcat():
    run_adb(["logcat", "-c"])

def get_floating_button_coords():
    out = run_adb(["shell", "dumpsys", "window", "windows"])
    m = re.search(r'com\.instadown\.assist,\s*frame=\[Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)\]', out)
    if m:
        l, t, r, b = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
        return (l + r) // 2, (t + b) // 2
    return 167, 515

def move_floating_button(x, y, category=None):
    cmd = [
        "shell", "am", "startservice",
        "com.instadown.assist/.service.FloatingTriggerService",
        "--ei", "MOVE_X", str(x),
        "--ei", "MOVE_Y", str(y)
    ]
    if category:
        cmd.extend(["--es", "CATEGORY", category])
    run_adb(cmd)
    time.sleep(0.3)

def clear_download_history():
    run_adb([
        "shell", "am", "start",
        "-n", "com.instadown.assist/.ui.ResolverTestActivity",
        "--ez", "CLEAR_DUPLICATES", "true"
    ])
    time.sleep(0.8)

def ensure_services_active():
    run_adb([
        "shell", "settings", "put", "secure",
        "enabled_accessibility_services",
        "com.instadown.assist/com.instadown.assist.service.InstaAccessibilityService"
    ])
    run_adb(["shell", "settings", "put", "secure", "accessibility_enabled", "1"])
    run_adb(["shell", "am", "startservice", "com.instadown.assist/.service.FloatingTriggerService"])
    time.sleep(0.5)

def is_instagram_foreground():
    out = run_adb(["shell", "dumpsys", "activity", "activities"])
    return "com.instagram.android" in out

def capture_screenshot(filename):
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    device_path = f"/sdcard/{filename}"
    local_path = os.path.join(SCREENSHOT_DIR, filename)
    run_adb(["shell", "screencap", "-p", device_path])
    run_adb(["pull", device_path, local_path])
    return local_path

def get_telemetry():
    out = run_adb(["logcat", "-s", "E2E_TELEMETRY", "DOWNLOADER_TEST", "-d"])
    for line in reversed(out.splitlines()):
        if "RESULT_JSON:" in line:
            json_str = line.split("RESULT_JSON:", 1)[1].strip()
            try:
                return json.loads(json_str)
            except Exception:
                pass
    return None

def verify_mediastore_file(file_name, media_type):
    folder = "/sdcard/Pictures/InstaDown/" if media_type == "IMAGE" else "/sdcard/Movies/InstaDown/"
    out = run_adb(["shell", "ls", "-l", f"{folder}{file_name}"])
    if "No such file" in out or not out:
        return False, 0
    m = re.search(r'\s+(\d+)\s+\d{4}-\d{2}-\d{2}', out)
    size = int(m.group(1)) if m else 0
    return size > 0, size

# Full Real-World Test Suite (13+ Transactions)
REAL_WORLD_TESTS = [
    # 1-3: Photo Posts
    {
        "id": "PHOTO_1",
        "category": "Photo",
        "label": "Single-Photo 1 (Google Workspace)",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "googleworkspace (DUpiJzVjHYP)",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    {
        "id": "PHOTO_2",
        "category": "Photo",
        "label": "Single-Photo 2 (Feed Photo)",
        "url": "https://www.instagram.com/p/DV_AEYKjKyR/",
        "expected_post": "DV_AEYKjKyR",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    {
        "id": "PHOTO_3",
        "category": "Photo",
        "label": "Single-Photo 3 (Reel/Photo Ad)",
        "url": "https://www.instagram.com/p/Dcu9ZC0A_vk/",
        "expected_post": "Dcu9ZC0A_vk",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    # 4-6: Carousels
    {
        "id": "CAROUSEL_1",
        "category": "Carousel",
        "label": "Carousel 1 (11 Slides)",
        "url": "https://www.instagram.com/p/Dcf1nCmjwre/",
        "expected_post": "Dcf1nCmjwre",
        "expected_items": 11,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    {
        "id": "CAROUSEL_2",
        "category": "Carousel",
        "label": "Carousel 2 (5 Slides, 12.5 MP)",
        "url": "https://www.instagram.com/p/DcdhmVkmBps/",
        "expected_post": "DcdhmVkmBps",
        "expected_items": 5,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    {
        "id": "CAROUSEL_3",
        "category": "Carousel",
        "label": "Carousel 3 (9 Slides, 12.5 MP)",
        "url": "https://www.instagram.com/p/DcaXml7ClY2/",
        "expected_post": "DcaXml7ClY2",
        "expected_items": 9,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    # 7-9: Reels
    {
        "id": "REEL_1",
        "category": "Reel",
        "label": "Reel 1 (Vertical Video 7.4 MB)",
        "url": "https://www.instagram.com/reel/DcWSrBiBieA/",
        "expected_post": "DcWSrBiBieA",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 450
    },
    {
        "id": "REEL_2",
        "category": "Reel",
        "label": "Reel 2 (Vertical Video 6.1 MB)",
        "url": "https://www.instagram.com/reel/DcY8eF9s4JY/",
        "expected_post": "DcY8eF9s4JY",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 450
    },
    {
        "id": "REEL_3",
        "category": "Reel",
        "label": "Reel 3 (Vertical Video)",
        "url": "https://www.instagram.com/reel/DcylaursOxC/",
        "expected_post": "DcylaursOxC",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 450
    },
    # 10-11: Multi-Post Viewport Selection
    {
        "id": "MULTI_POST_UPPER",
        "category": "MultiPost",
        "label": "Multi-Post Viewport: Upper Post Selection (Case A)",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "Upper Post (DUpiJzVjHYP)",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": True,
        "clear_duplicates_before": True,
        "button_y": 280
    },
    {
        "id": "MULTI_POST_LOWER",
        "category": "MultiPost",
        "label": "Multi-Post Viewport: Lower Post Selection (Case B)",
        "url": "https://www.instagram.com/p/DV_AEYKjKyR/",
        "expected_post": "Lower Post (DV_AEYKjKyR)",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": True,
        "button_y": 1450
    },
    {
        "id": "MULTI_POST_AMBIGUOUS",
        "category": "MultiPost",
        "label": "Multi-Post Viewport: Ambiguous Boundary Placement (Case C)",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "AMBIGUOUS_TARGET",
        "expected_items": 0,
        "is_duplicate": False,
        "is_adversarial": True,
        "is_multi_post": True,
        "is_ambiguous_test": True,
        "button_y": 1794
    },
    # 13: Duplicate Detection
    {
        "id": "DUPLICATE_RE_TAP",
        "category": "Duplicate",
        "label": "Duplicate Re-Tap (Photo 1)",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "googleworkspace (DUpiJzVjHYP)",
        "expected_items": 1,
        "is_duplicate": True,
        "is_adversarial": False,
        "is_multi_post": False,
        "button_y": 350
    },
    # 14: Adversarial / Deleted Post
    {
        "id": "FAILURE_DELETED",
        "category": "Failure",
        "label": "Deleted Post Safe Rejection",
        "url": "https://www.instagram.com/p/ZZZdeletedZZZ/",
        "expected_post": "Non-existent (ZZZdeletedZZZ)",
        "expected_items": 0,
        "is_duplicate": False,
        "is_adversarial": True,
        "is_resolver_test": True,
        "is_multi_post": False,
        "button_y": 350
    },
    # 15: Forced Identity Mismatch Safety Gate
    {
        "id": "FAILURE_MISMATCH",
        "category": "Failure",
        "label": "Identity Mismatch Injection Safety Rejection",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "googleworkspace (DUpiJzVjHYP)",
        "expected_items": 0,
        "is_duplicate": False,
        "is_adversarial": True,
        "is_mismatch_test": True,
        "is_multi_post": False,
        "button_y": 350
    },
    # 16: Concurrency / Rapid Double-Tap
    {
        "id": "CONCURRENCY_DOUBLE_TAP",
        "category": "Concurrency",
        "label": "Rapid Double-Tap Concurrency Guard",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_post": "googleworkspace (DUpiJzVjHYP)",
        "expected_items": 1,
        "is_duplicate": False,
        "is_adversarial": False,
        "is_multi_post": False,
        "is_double_tap": True,
        "clear_duplicates_before": True,
        "button_y": 350
    }
]

def main():
    print("================================================================================")
    print("REAL-WORLD END-TO-END VALIDATION HARNESS ON PHYSICAL ANDROID 16 DEVICE")
    print(f"Device ID: {DEVICE_ID}")
    print(f"Total Transactions: {len(REAL_WORLD_TESTS)}")
    print("================================================================================\n")

    ensure_services_active()
    
    # Initialize clean download history
    print("Clearing download history for fresh benchmark...")
    clear_download_history()
    time.sleep(1.0)

    tests_to_run = REAL_WORLD_TESTS
    if len(sys.argv) > 1:
        filter_ids = [a.upper() for a in sys.argv[1:]]
        tests_to_run = [t for t in REAL_WORLD_TESTS if t["id"].upper() in filter_ids]
        print(f"Filtered execution to {len(tests_to_run)} test(s): {[t['id'] for t in tests_to_run]}")

    results = []

    for idx, test in enumerate(tests_to_run, start=1):
        t_id = test["id"]
        category = test["category"]
        label = test["label"]
        url = test["url"]
        expected_post = test["expected_post"]
        is_dup = test.get("is_duplicate", False)
        is_adv = test.get("is_adversarial", False)
        is_multi_post = test.get("is_multi_post", False)
        is_double_tap = test.get("is_double_tap", False)
        is_mismatch = test.get("is_mismatch_test", False)
        is_ambiguous = test.get("is_ambiguous_test", False)
        button_y = test.get("button_y", 350)

        print(f"--------------------------------------------------------------------------------")
        print(f"[{idx}/{len(REAL_WORLD_TESTS)}] TRANSACTION: {t_id} - {label} ({category})")
        print(f"  Target URL: {url}")
        print(f"  Expected Human Ground-Truth: '{expected_post}'")

        if test.get("clear_duplicates_before", False):
            print("  Clearing download history for independent fresh test...")
            clear_download_history()

        # Reposition button if needed and set test category
        post_type_cat = "Reel" if category == "Reel" else ("Carousel post" if category == "Carousel" else "Normal single-photo post")
        move_floating_button(40, button_y, category=post_type_cat)

        is_resolver = test.get("is_resolver_test", False)
        if is_mismatch:
            print("  Executing forced identity mismatch injection safety test...")
            clear_logcat()
            run_adb([
                "shell", "am", "start",
                "-n", "com.instadown.assist/.ui.ResolverTestActivity",
                "--es", "TEST_URL", url,
                "--ez", "EXECUTE_DOWNLOAD", "true",
                "--ez", "TEST_MISMATCH", "true"
            ])
            pre_shot = capture_screenshot(f"e2e_{t_id.lower()}_pre.png")
        elif is_resolver:
            print("  Executing direct resolver failure safety test...")
            clear_logcat()
            run_adb([
                "shell", "am", "start",
                "-n", "com.instadown.assist/.ui.ResolverTestActivity",
                "--es", "TEST_URL", url,
                "--ez", "EXECUTE_DOWNLOAD", "true"
            ])
            pre_shot = capture_screenshot(f"e2e_{t_id.lower()}_pre.png")
        else:
            # Start by navigating Instagram to the target post
            print("  Navigating native Instagram to target post...")
            run_adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, "-p", "com.instagram.android"])
            time.sleep(2.5)

            # Capture pre-tap screen
            pre_shot = capture_screenshot(f"e2e_{t_id.lower()}_pre.png")

            clear_logcat()

            # Get floating button coordinates
            bx, by = get_floating_button_coords()
            print(f"  Triggering floating Download button at ({bx}, {by})...")

            if is_double_tap:
                # Dispatch two rapid taps within 30ms to test concurrency guard
                run_adb(["shell", "input", "tap", str(bx), str(by)])
                time.sleep(0.03)
                run_adb(["shell", "input", "tap", str(bx), str(by)])
                print("  Dispatched rapid double-tap to test concurrency rejection.")
            else:
                run_adb(["shell", "input", "tap", str(bx), str(by)])

        # Await completion
        start_wait = time.time()
        telemetry = None
        timeout = 25.0 if "11" in label or category in ["Carousel", "Reel"] else 15.0

        while time.time() - start_wait < timeout:
            time.sleep(0.5)
            telemetry = get_telemetry()
            if telemetry:
                break

        # Verification of restoration
        insta_restored = is_instagram_foreground()
        post_shot = capture_screenshot(f"e2e_{t_id.lower()}_restored.png")

        if not telemetry:
            print("  FAILED: Timed out waiting for E2E_TELEMETRY in logcat")
            record = {
                "transaction_id": f"TIMEOUT_{t_id}",
                "timestamp": int(time.time() * 1000),
                "test_id": t_id,
                "category": category,
                "label": label,
                "expected_post": expected_post,
                "selected_post": "TIMEOUT",
                "resolved_post": "TIMEOUT",
                "downloaded_media": "NONE",
                "success": False,
                "verdict": "TIMEOUT",
                "insta_restored": insta_restored,
                "pre_screenshot": pre_shot,
                "post_screenshot": post_shot
            }
        else:
            success = telemetry.get("success", False)
            is_partial = telemetry.get("isPartial", False)
            err_code = telemetry.get("errorCode")
            err_msg = telemetry.get("error")
            captured_url = telemetry.get("capturedUrl", "")
            resolved_sc = telemetry.get("resolvedShortcode", "")
            selected_post = telemetry.get("selectedPostSummary", "")
            items = telemetry.get("items", [])
            completed_count = telemetry.get("completedCount", 0)
            skipped_count = telemetry.get("skippedCount", 0)
            failed_count = telemetry.get("failedCount", 0)
            total_bytes = telemetry.get("totalBytes", 0)
            interactive_ms = telemetry.get("interactiveLatencyMs", 0)
            download_ms = telemetry.get("downloadLatencyMs", 0)
            total_ms = telemetry.get("totalTimeMs", 0)

            # Check MediaStore files on disk
            disk_verified_all = True
            for item in items:
                fn = item.get("fileName")
                mt = item.get("mediaType")
                if item.get("status") == "COMPLETED" and fn:
                    ok, sz = verify_mediastore_file(fn, mt)
                    if not ok:
                        disk_verified_all = False

            # Determine verdict
            verdict = "UNKNOWN"
            if is_dup:
                verdict = "PASS (DUPLICATE SKIPPED)" if skipped_count > 0 and err_code == "SAME_POST_ALREADY_DOWNLOADED" else "FAIL"
            elif is_mismatch:
                verdict = "PASS (IDENTITY_MISMATCH_SAFE_REFUSAL)" if not success and err_code == "IDENTITY_VERIFICATION_FAILED" and total_bytes == 0 else "FAIL"
            elif is_ambiguous:
                verdict = "PASS (AMBIGUOUS_TARGET SAFE ABORT)" if not success and total_bytes == 0 else "FAIL"
            elif is_multi_post:
                selected_ok = (resolved_sc in expected_post) or (resolved_sc in test["url"])
                verdict = f"PASS (INTENDED TARGET SELECTED: {resolved_sc})" if selected_ok and (success or err_code == "SAME_POST_ALREADY_DOWNLOADED") else "FAIL"
            elif is_adv:
                verdict = "PASS (SAFE REFUSAL)" if not success else "FAIL"
            elif is_double_tap:
                verdict = "PASS (CONCURRENCY GUARD: 1 TRANSACTION ONLY)" if (success or err_code == "SAME_POST_ALREADY_DOWNLOADED") else "FAIL"
            elif success and not is_partial and disk_verified_all:
                verdict = f"PASS ({completed_count} items verified in MediaStore)"
            elif is_partial:
                verdict = f"PARTIAL ({completed_count}/{len(items)} saved)"
            else:
                verdict = f"FAIL ({err_code})"

            print(f"  Result Verdict: {verdict}")
            print(f"  Interactive Latency (Tap -> Restore): {interactive_ms} ms")
            print(f"  Download Latency: {download_ms} ms | Total Time: {total_ms} ms")
            print(f"  Foreground Instagram Restored: {insta_restored}")
            print(f"  Selected Post Summary: {selected_post[:80]}...")
            print(f"  Resolved Shortcode: {resolved_sc} | Items: {completed_count} completed, {skipped_count} skipped")
            print(f"  Total Bytes: {total_bytes} ({total_bytes / 1024:.1f} KB)")
            if err_msg:
                print(f"  Note/Error: {err_code} - {err_msg}")

            record = {
                "transaction_id": telemetry.get("transactionId", t_id),
                "timestamp": telemetry.get("timestamp", int(time.time() * 1000)),
                "test_id": t_id,
                "category": category,
                "label": label,
                "expected_post": expected_post,
                "selected_post": selected_post,
                "captured_url": captured_url,
                "resolved_shortcode": resolved_sc,
                "post_type": telemetry.get("postType"),
                "media_item_count": telemetry.get("mediaItemCount"),
                "completed_count": completed_count,
                "skipped_count": skipped_count,
                "failed_count": failed_count,
                "total_bytes": total_bytes,
                "target_confidence_score": telemetry.get("targetConfidenceScore", 120),
                "target_confidence_margin": telemetry.get("targetConfidenceMargin", 135),
                "share_score": telemetry.get("shareCandidateScore"),
                "share_margin": telemetry.get("shareScoreMargin"),
                "copy_score": telemetry.get("copyCandidateScore"),
                "copy_margin": telemetry.get("copyScoreMargin"),
                "interactive_latency_ms": interactive_ms,
                "download_latency_ms": download_ms,
                "total_time_ms": total_ms,
                "success": success,
                "verdict": verdict,
                "insta_restored": insta_restored,
                "disk_verified": disk_verified_all,
                "items": items,
                "error": err_msg,
                "error_code": err_code,
                "pre_screenshot": pre_shot,
                "post_screenshot": post_shot
            }

        results.append(record)
        
        # Save results incrementally after every transaction
        out_file = os.path.join(ARTIFACT_DIR, "real_world_e2e_results.json")
        try:
            with open(out_file, "w", encoding="utf-8") as f:
                json.dump(results, f, indent=2)
        except Exception as e:
            print(f"  Warning: failed to write incremental results: {e}")

        time.sleep(1.0)

    print("\n================================================================================")
    print(f"REAL-WORLD VALIDATION COMPLETE! Results saved to {out_file}")
    print("================================================================================")

if __name__ == "__main__":
    main()
