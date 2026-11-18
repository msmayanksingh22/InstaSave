import subprocess
import time
import json
import os
import re

ADB_PATH = r"C:\Users\msmay\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEVICE_ID = "192.168.1.3:45657"
ARTIFACT_DIR = r"C:\Users\msmay\.gemini\antigravity-ide\brain\42b82130-4e56-435b-ac55-55b944cbda77"
SCREENSHOT_DIR = os.path.join(ARTIFACT_DIR, ".tempmediaStorage")

DOWNLOAD_TEST_DATASET = [
    # Test A: Single Photos
    {
        "test_id": "TEST_A1",
        "label": "Single-Photo 1 (1080p)",
        "category": "Photo",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_count": 1,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    {
        "test_id": "TEST_A2",
        "label": "Single-Photo 2 (1080p)",
        "category": "Photo",
        "url": "https://www.instagram.com/p/DV_AEYKjKyR/",
        "expected_count": 1,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    # Test B: Carousels (Order & Slide sequence preservation)
    {
        "test_id": "TEST_B1",
        "label": "Carousel 1 (11 Slides)",
        "category": "Carousel",
        "url": "https://www.instagram.com/p/Dcf1nCmjwre/",
        "expected_count": 11,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    {
        "test_id": "TEST_B2",
        "label": "Carousel 2 (5 Slides, 12.5 MP)",
        "category": "Carousel",
        "url": "https://www.instagram.com/p/DcdhmVkmBps/",
        "expected_count": 5,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    {
        "test_id": "TEST_B3",
        "label": "Carousel 3 (9 Slides, 12.5 MP)",
        "category": "Carousel",
        "url": "https://www.instagram.com/p/DcaXml7ClY2/",
        "expected_count": 9,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    # Test D: Reels (Video Progressive MP4)
    {
        "test_id": "TEST_D1",
        "label": "Reel 1 (720p MP4)",
        "category": "Reel",
        "url": "https://www.instagram.com/reel/DcWSrBiBieA/",
        "expected_count": 1,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    {
        "test_id": "TEST_D2",
        "label": "Reel 2 (720p MP4)",
        "category": "Reel",
        "url": "https://www.instagram.com/reel/DcY8eF9s4JY/",
        "expected_count": 1,
        "is_duplicate_test": False,
        "test_mismatch": False
    },
    # Test E: Duplicate Detection
    {
        "test_id": "TEST_E1",
        "label": "Duplicate Re-tap (Single-Photo 1)",
        "category": "Duplicate",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_count": 1,
        "is_duplicate_test": True,
        "test_mismatch": False
    },
    # Test H: Wrong Identity Protection Safety Gate
    {
        "test_id": "TEST_H1",
        "label": "Mismatched Identity Refusal",
        "category": "SafetyGate",
        "url": "https://www.instagram.com/p/DUpiJzVjHYP/",
        "expected_count": 0,
        "is_duplicate_test": False,
        "test_mismatch": True
    },
    # Adversarial / Errors
    {
        "test_id": "TEST_ERR1",
        "label": "Deleted Post Rejection",
        "category": "ErrorHandling",
        "url": "https://www.instagram.com/p/ZZZdeletedZZZ/",
        "expected_count": 0,
        "is_duplicate_test": False,
        "test_mismatch": False
    }
]

def run_adb(args):
    cmd = [ADB_PATH, "-s", DEVICE_ID] + args
    res = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    return res.stdout.strip()

def clear_logcat():
    run_adb(["logcat", "-c"])

def get_downloader_log(expected_shortcode=None):
    out = run_adb(["logcat", "-s", "DOWNLOADER_TEST", "-d"])
    for line in reversed(out.splitlines()):
        if "RESULT_JSON:" in line:
            json_str = line.split("RESULT_JSON:", 1)[1].strip()
            try:
                data = json.loads(json_str)
                if expected_shortcode is None or data.get("shortcode") == expected_shortcode:
                    return data
            except Exception as e:
                pass
    return None

def capture_screenshot(filename):
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    device_path = f"/sdcard/{filename}"
    local_path = os.path.join(SCREENSHOT_DIR, filename)
    run_adb(["shell", "screencap", "-p", device_path])
    run_adb(["pull", device_path, local_path])
    return local_path

def verify_mediastore_files():
    # Query files in Pictures/InstaDown and Movies/InstaDown
    out_pics = run_adb(["shell", "ls", "-la", "/sdcard/Pictures/InstaDown/"])
    out_vids = run_adb(["shell", "ls", "-la", "/sdcard/Movies/InstaDown/"])
    return out_pics, out_vids

def main():
    print("================================================================================")
    print("PHASE 3 EMPIRICAL DOWNLOAD BENCHMARK ON PHYSICAL ANDROID 16 DEVICE")
    print(f"Device: {DEVICE_ID}")
    print(f"Total Test Cases: {len(DOWNLOAD_TEST_DATASET)}")
    print("================================================================================\n")

    # Clear previous history for clean run
    print("Initializing clean test environment: clearing history...")
    run_adb([
        "shell", "am", "start",
        "-n", "com.instadown.assist/.ui.ResolverTestActivity",
        "--ez", "CLEAR_DUPLICATES", "true"
    ])
    time.sleep(1.0)

    results = []

    for idx, test in enumerate(DOWNLOAD_TEST_DATASET, start=1):
        test_id = test["test_id"]
        label = test["label"]
        category = test["category"]
        url = test["url"]
        is_dup = test["is_duplicate_test"]
        is_mismatch = test["test_mismatch"]

        print(f"--------------------------------------------------------------------------------")
        print(f"[{idx}/{len(DOWNLOAD_TEST_DATASET)}] Executing {test_id}: {label} ({category})")
        print(f"  URL: {url}")

        clear_logcat()

        # Extract shortcode
        m = re.search(r'/(p|reel|reels|tv)/([^/?#&]+)', url)
        shortcode = m.group(2) if m else "unknown"

        # Launch download via intent
        intent_args = [
            "shell", "am", "start",
            "-n", "com.instadown.assist/.ui.ResolverTestActivity",
            "--es", "TEST_URL", url,
            "--ez", "EXECUTE_DOWNLOAD", "true"
        ]
        if is_mismatch:
            intent_args.extend(["--ez", "TEST_MISMATCH", "true"])

        run_adb(intent_args)

        # Wait for download result
        start_wait = time.time()
        telemetry = None
        timeout = 25.0 if "11" in label or category == "Reel" else 15.0

        while time.time() - start_wait < timeout:
            time.sleep(0.5)
            expected_sc = "ARTIFICIAL_MISMATCH" if is_mismatch else shortcode
            telemetry = get_downloader_log(expected_shortcode=expected_sc)
            if telemetry:
                break

        if not telemetry:
            print("  FAILED: Timed out waiting for downloader telemetry in logcat")
            record = {
                "test_num": idx,
                "test_id": test_id,
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
            completed_count = telemetry.get("completedCount", 0)
            failed_count = telemetry.get("failedCount", 0)
            skipped_count = telemetry.get("skippedCount", 0)
            total_bytes = telemetry.get("totalBytes", 0)
            elapsed_ms = telemetry.get("elapsedTimeMs", 0)
            items = telemetry.get("items", [])
            err = telemetry.get("error")
            err_code = telemetry.get("errorCode")

            verdict = "UNKNOWN"
            if is_dup:
                verdict = "PASS (DUPLICATE SKIPPED)" if skipped_count > 0 else "FAIL"
            elif is_mismatch:
                verdict = "PASS (SAFETY REJECTION)" if not success and err_code == "IDENTITY_VERIFICATION_FAILED" else "FAIL"
            elif category == "ErrorHandling":
                verdict = "PASS (SAFE REJECTION)" if not success else "FAIL"
            elif success and not is_partial:
                verdict = f"PASS ({completed_count} items saved)"
            elif is_partial:
                verdict = f"PARTIAL ({completed_count}/{len(items)} items saved)"
            else:
                verdict = f"FAIL ({err_code})"

            print(f"  Result Verdict: {verdict}")
            print(f"  Completed: {completed_count} | Failed: {failed_count} | Skipped: {skipped_count}")
            print(f"  Total Bytes: {total_bytes} ({total_bytes / 1024:.1f} KB) | Latency: {elapsed_ms}ms")

            for item in items:
                print(f"    Item [{item.get('index')}]: {item.get('mediaType')} -> {item.get('fileName')} | Bytes: {item.get('bytesDownloaded')} (Verified: {item.get('isVerified')}) | URI: {item.get('localUri')}")

            if err:
                print(f"  Note/Error: {err_code} - {err}")

            screenshot_name = f"phase3_{test_id.lower()}.png"
            capture_screenshot(screenshot_name)

            record = {
                "test_num": idx,
                "test_id": test_id,
                "label": label,
                "category": category,
                "input_url": url,
                "shortcode": shortcode,
                "verdict": verdict,
                "success": success,
                "is_partial": is_partial,
                "completed_count": completed_count,
                "failed_count": failed_count,
                "skipped_count": skipped_count,
                "total_bytes": total_bytes,
                "elapsed_time_ms": elapsed_ms,
                "items": items,
                "error": err,
                "error_code": err_code,
                "screenshot": screenshot_name
            }

        results.append(record)
        time.sleep(1.0)

    # Save benchmark results
    out_file = os.path.join(ARTIFACT_DIR, "phase3_benchmark_results.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    # Verify MediaStore directories
    print("\n--------------------------------------------------------------------------------")
    print("VERIFYING MEDIASTORE DIRECTORIES ON DEVICE DISK:")
    pics_ls, vids_ls = verify_mediastore_files()
    print("Pictures/InstaDown:")
    print(pics_ls)
    print("\nMovies/InstaDown:")
    print(vids_ls)

    print("\n================================================================================")
    print(f"PHASE 3 BENCHMARK COMPLETE! Results saved to {out_file}")
    print("================================================================================")

if __name__ == "__main__":
    main()
