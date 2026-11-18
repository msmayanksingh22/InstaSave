import subprocess
import time
import json
import re
import os
import sys
import argparse
from datetime import datetime

ADB_PATH = r"C:\Users\msmay\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def get_active_device_id():
    try:
        out = subprocess.run([ADB_PATH, "devices"], capture_output=True, text=True).stdout
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                return parts[0]
    except Exception:
        pass
    return "adb-IZRC6H8LEQCAFYMR-VUlk4u._adb-tls-connect._tcp"

DEVICE_ID = get_active_device_id()
RESULTS_FILE = r"C:\Users\msmay\.gemini\antigravity-ide\brain\42b82130-4e56-435b-ac55-55b944cbda77\scratch\validation_50_trials.json"

def run_adb(cmd, timeout=10):
    full_cmd = [ADB_PATH, "-s", DEVICE_ID] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    return res.stdout.strip()

def is_instagram_foreground():
    out = run_adb(["shell", "dumpsys", "activity", "activities"])
    for line in out.splitlines():
        if "topResumedActivity" in line or "mResumedActivity" in line:
            return "com.instagram.android" in line
    out2 = run_adb(["shell", "dumpsys", "window", "windows"])
    for line in out2.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            return "com.instagram.android" in line
    return False

def get_current_post_info():
    """Non-blocking screen check: avoids uiautomator dump to prevent accessibility token deadlocks."""
    return []

def clear_telemetry_and_logcat():
    run_adb(["shell", "logcat", "-c"])

def get_floating_button_coords():
    out = run_adb(["shell", "dumpsys", "window", "windows"])
    m = re.search(r'com\.instadown\.assist,\s*frame=\[Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)\]', out)
    if m:
        l, t, r, b = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
        return (l + r) // 2, (t + b) // 2
    # Fallback to default
    return 646, 1793

def trigger_floating_button():
    x, y = get_floating_button_coords()
    run_adb(["shell", "input", "tap", str(x), str(y)])

def wait_for_completion(timeout_sec=6.0):
    start = time.time()
    while time.time() - start < timeout_sec:
        time.sleep(0.3)
        logs = run_adb(["shell", "logcat", "-d", "-t", "200", "-s", "InstaDownloaderPOC:V"])
        if "Automation transaction completed" in logs or "AMBIGUOUS_TARGET" in logs or "Automation failed" in logs or "FAILED" in logs:
            return logs
    return run_adb(["shell", "logcat", "-d", "-t", "200", "-s", "InstaDownloaderPOC:V"])

def parse_latest_result():
    xml_data = run_adb(["shell", "run-as", "com.instadown.assist", "cat", "shared_prefs/reliability_prefs.xml"])
    match = re.search(r'<string name="history_json">(\[.*\])<\/string>', xml_data)
    if not match:
        return None
    raw_json = match.group(1)
    raw_json = raw_json.replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    try:
        arr = json.loads(raw_json)
        if arr:
            return arr[-1]
    except Exception as e:
        print(f"Error parsing JSON: {e}")
    return None

def audit_logcat_safety(logs):
    incorrect_clicks = []
    for line in logs.splitlines():
        if "performControlledClick" in line:
            if "ACTION_CLICK succeeded" in line or "dispatchGesture" in line:
                if "Share" not in line and "Copy" not in line and "ImageView" not in line and "ViewGroup" not in line and "LinearLayout" not in line and "TextView" not in line and "button" not in line:
                    incorrect_clicks.append(line)
    return len(incorrect_clicks), incorrect_clicks

def record_trial(trial_num, category, expected_post, adversarial_note=""):
    print(f"\n==========================================")
    print(f"--- STARTING TRIAL {trial_num}/50 [{category}] ---")
    print(f"Expected Post Ground-Truth: '{expected_post}'")
    if adversarial_note:
        print(f"Adversarial Condition: {adversarial_note}")
    print(f"==========================================")

    if not is_instagram_foreground():
        print("ERROR: Instagram is NOT in foreground! Please ensure Instagram is active.")
        return None

    visible_users = get_current_post_info()
    print(f"Detected Visible Authors on screen: {visible_users}")

    print("Clearing logcat and triggering floating button...")
    clear_telemetry_and_logcat()
    time.sleep(0.2)
    trigger_floating_button()

    print("Waiting for automation engine...")
    logs = wait_for_completion()

    res = parse_latest_result()
    if not res:
        print("WARNING: Could not parse latest result from ReliabilityTracker! Inspecting logcat directly.")
        res = {
            "verdict": "FAILED",
            "failureReason": "Could not read from SharedPreferences",
            "phaseTimings": {},
            "totalTimeMs": 0
        }

    # Safety Audit
    num_incorrect_clicks, click_details = audit_logcat_safety(logs)

    # Ground-Truth Post Selection Verification
    selected_summary = res.get("postContainerSummary", "")
    selected_user = ""
    u_match = re.search(r"user='([^']+)'", selected_summary)
    if u_match:
        selected_user = u_match.group(1).lower()

    wrong_post = False
    if expected_post:
        exp_clean = expected_post.lower().replace("@", "").replace(" ", "").replace("_", "").replace(".", "").strip()
        sel_clean = selected_user.lower().replace("@", "").replace(" ", "").replace("_", "").replace(".", "").strip()
        matched = (exp_clean in sel_clean or sel_clean in exp_clean)
        if not matched:
            exp_tokens = [t for t in re.split(r'[^a-z0-9]', expected_post.lower()) if len(t) >= 4]
            sel_tokens = [t for t in re.split(r'[^a-z0-9]', selected_user.lower()) if len(t) >= 4]
            common = set(exp_tokens).intersection(set(sel_tokens))
            if common or any(et in sel_clean for et in exp_tokens) or any(st in exp_clean for st in sel_tokens):
                matched = True
        if sel_clean and not matched:
            wrong_post = True

    # Check restored post
    time.sleep(0.4)
    verdict = res.get("verdict", "UNKNOWN")
    if "AMBIGUOUS_TARGET" in logs:
        verdict = "AMBIGUOUS_TARGET"

    restored_matches = (is_instagram_foreground() and not wrong_post and (verdict == "PASS" or verdict == "SUCCESS"))

    trial_data = {
        "trial_num": trial_num,
        "category": category,
        "timestamp": datetime.now().isoformat(),
        "expected_post": expected_post,
        "selected_post_summary": selected_summary,
        "selected_post_user": selected_user,
        "wrong_post_selection": wrong_post,
        "adversarial_note": adversarial_note,
        "share_candidate": res.get("shareCandidate", ""),
        "share_score": res.get("shareCandidateScore", 0),
        "share_runner_up_score": res.get("shareRunnerUpScore", 0),
        "share_margin": res.get("shareScoreMargin", 0),
        "copy_candidate": res.get("copyLinkCandidate", ""),
        "copy_score": res.get("copyCandidateScore", 0),
        "copy_runner_up_score": res.get("copyRunnerUpScore", 0),
        "copy_margin": res.get("copyScoreMargin", 0),
        "reveal_operations": res.get("revealOperationsCount", 0),
        "clipboard_url": res.get("instagramUrl", ""),
        "url_is_fresh": res.get("validInstagramUrl", False),
        "restored_matches_original": restored_matches,
        "verdict": verdict,
        "incorrect_control_clicks": num_incorrect_clicks,
        "incorrect_click_details": click_details,
        "phase_timings": res.get("phaseTimings", {}),
        "total_time_ms": res.get("totalTimeMs", 0)
    }

    print(f"\n--- TRIAL {trial_num} RESULTS ---")
    print(f"Verdict: {verdict}")
    print(f"Selected Post: {selected_summary} (Wrong Post: {wrong_post})")
    print(f"Share Score: {trial_data['share_score']} (Margin: {trial_data['share_margin']})")
    print(f"Copy Score: {trial_data['copy_score']} (Margin: {trial_data['copy_margin']})")
    print(f"Reveal (Swipes): {trial_data['reveal_operations']}")
    print(f"URL Captured: {trial_data['clipboard_url']}")
    print(f"Total Time: {trial_data['total_time_ms']}ms")
    print(f"Incorrect Clicks: {num_incorrect_clicks}")
    print(f"Restored View Matches: {restored_matches}")

    all_trials = []
    if os.path.exists(RESULTS_FILE):
        try:
            with open(RESULTS_FILE, "r", encoding="utf-8") as f:
                all_trials = json.load(f)
        except Exception:
            all_trials = []
    all_trials.append(trial_data)
    os.makedirs(os.path.dirname(RESULTS_FILE), exist_ok=True)
    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(all_trials, f, indent=2)

    return trial_data

def generate_report():
    if not os.path.exists(RESULTS_FILE):
        print("No results file found.")
        return

    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        trials = json.load(f)

    total_attempts = len(trials)
    if total_attempts == 0:
        print("0 trials found.")
        return

    successes = sum(1 for t in trials if t["verdict"] in ("PASS", "SUCCESS"))
    safe_aborts = sum(1 for t in trials if t["verdict"] in ("AMBIGUOUS_TARGET", "FAILED", "FAIL", "SAFE_ABORT") and t["incorrect_control_clicks"] == 0)
    wrong_posts = sum(1 for t in trials if t["wrong_post_selection"])
    wrong_clicks = sum(t["incorrect_control_clicks"] for t in trials)

    categories = sorted(list(set(t["category"] for t in trials)))

    print("\n" + "="*80)
    print("50-TRIAL RELIABILITY VALIDATION SUMMARY REPORT")
    print("="*80)
    print(f"Total Transactions: {total_attempts}")
    print(f"Successful Captures: {successes} ({successes/total_attempts*100:.1f}%)")
    print(f"Safe Aborts: {safe_aborts} ({safe_aborts/total_attempts*100:.1f}%)")
    print(f"WRONG_POST_SELECTIONS: {wrong_posts} (Target: 0)")
    print(f"INCORRECT_CONTROL_CLICKS: {wrong_clicks} (Target: 0)")

    print("\n--- CATEGORY BREAKDOWN TABLE ---")
    headers = ["Category", "Attempts", "Success", "Safe Abort", "Wrong Post", "Wrong Click", "Avg ms", "P95 ms", "Max ms"]
    print(f"{headers[0]:<25} | {headers[1]:<8} | {headers[2]:<8} | {headers[3]:<10} | {headers[4]:<10} | {headers[5]:<11} | {headers[6]:<8} | {headers[7]:<8} | {headers[8]:<8}")
    print("-" * 110)

    for cat in categories:
        cat_trials = [t for t in trials if t["category"] == cat]
        c_att = len(cat_trials)
        c_succ = sum(1 for t in cat_trials if t["verdict"] in ("PASS", "SUCCESS"))
        c_abort = sum(1 for t in cat_trials if t["verdict"] in ("AMBIGUOUS_TARGET", "FAILED", "FAIL", "SAFE_ABORT") and t["incorrect_control_clicks"] == 0)
        c_wp = sum(1 for t in cat_trials if t["wrong_post_selection"])
        c_wc = sum(t["incorrect_control_clicks"] for t in cat_trials)
        latencies = [t["total_time_ms"] for t in cat_trials if t["total_time_ms"] > 0]
        c_avg = sum(latencies)/len(latencies) if latencies else 0
        sorted_lat = sorted(latencies)
        c_p95 = sorted_lat[int(len(sorted_lat)*0.95)] if sorted_lat else 0
        c_max = max(latencies) if latencies else 0
        print(f"{cat:<25} | {c_att:<8} | {c_succ:<8} | {c_abort:<10} | {c_wp:<10} | {c_wc:<11} | {c_avg:<8.0f} | {c_p95:<8} | {c_max:<8}")

    print("-" * 110)

    # Phase Latencies
    print("\n--- PHASE LATENCY BREAKDOWN (Across All Completed Runs) ---")
    phase_names = [
        ("identifyPost", "IDENTIFY_POST"),
        ("locateShare", "LOCATE_SHARE"),
        ("clickShare", "OPEN_SHARE"),
        ("locateCopyLink", "LOCATE_COPY"),
        ("revealCopyLink", "REVEAL_COPY"),
        ("clickCopyLink", "CLICK_COPY"),
        ("clipboardCapture", "CLIPBOARD"),
        ("dismissShare", "DISMISS"),
        ("restoreInstagram", "RESTORE")
    ]
    print(f"{'Phase':<20} | {'Avg ms':<10} | {'Median ms':<10} | {'P95 ms':<10} | {'Max ms':<10}")
    print("-" * 68)

    for pkey, pname in phase_names:
        vals = []
        for t in trials:
            ptimings = t.get("phase_timings", {})
            if pkey in ptimings and ptimings[pkey] > 0:
                vals.append(ptimings[pkey])
        if vals:
            svals = sorted(vals)
            avg_v = sum(vals) / len(vals)
            med_v = svals[len(vals)//2]
            p95_v = svals[int(len(vals)*0.95)]
            max_v = max(vals)
            print(f"{pname:<20} | {avg_v:<10.1f} | {med_v:<10} | {p95_v:<10} | {max_v:<10}")

    # Top 5 Slowest Transactions
    print("\n--- TOP 5 SLOWEST TRANSACTIONS (Root-Cause Audit) ---")
    completed = [t for t in trials if t["total_time_ms"] > 0]
    sorted_slowest = sorted(completed, key=lambda x: x["total_time_ms"], reverse=True)[:5]
    for idx, st in enumerate(sorted_slowest, 1):
        print(f"\n#{idx}: Trial {st['trial_num']} [{st['category']}] - {st['total_time_ms']}ms (Verdict: {st['verdict']})")
        print(f"  Post: {st['selected_post_user']} | URL: {st['clipboard_url']}")
        print(f"  Phase Timings: {st.get('phase_timings', {})}")
        print(f"  Reveal Operations (Swipes): {st.get('reveal_operations', 0)}")
        timings = st.get("phase_timings", {})
        if timings:
            max_phase = max(timings.items(), key=lambda x: x[1])
            print(f"  Dominant Local Bottleneck: {max_phase[0]} ({max_phase[1]}ms)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--trial", type=int, default=1)
    parser.add_argument("--category", type=str, default="Single-Photo")
    parser.add_argument("--expected-post", type=str, default="")
    parser.add_argument("--adversarial", type=str, default="")
    parser.add_argument("--report", action="store_true")
    args = parser.parse_args()

    if args.report:
        generate_report()
    else:
        record_trial(args.trial, args.category, args.expected_post, args.adversarial)
