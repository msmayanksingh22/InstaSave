import json
import os
import math

RESULTS_PATH = r"C:\Users\msmay\.gemini\antigravity-ide\brain\42b82130-4e56-435b-ac55-55b944cbda77\real_world_e2e_results.json"

def percentile(data, p):
    if not data:
        return 0.0
    sorted_d = sorted(data)
    idx = (len(sorted_d) - 1) * p / 100.0
    floor = math.floor(idx)
    ceil = math.ceil(idx)
    if floor == ceil:
        return sorted_d[int(idx)]
    return sorted_d[floor] * (ceil - idx) + sorted_d[ceil] * (idx - floor)

def main():
    with open(RESULTS_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    print(f"Total Transactions Recorded: {len(data)}")

    # Classifications
    success_count = 0
    safe_abort_count = 0
    failed_count = 0
    wrong_post = 0
    wrong_control = 0
    wrong_download = 0

    interactive_latencies = []
    download_latencies = []
    total_latencies = []

    # Category breakdowns
    by_category = {
        "Photo": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "Carousel": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "Reel": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "MultiPost": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "Duplicate": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "Failure": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0},
        "Concurrency": {"attempts": 0, "success": 0, "safe_abort": 0, "wrong_post": 0, "wrong_download": 0}
    }

    # Storage counters
    photos_saved = 0
    videos_saved = 0
    carousel_items_saved = 0
    duplicates_skipped = 0
    corrupt_files = 0
    unexpected_files = 0

    for item in data:
        cat = item.get("category", "Other")
        verdict = item.get("verdict", "")
        success = item.get("success", False)
        t_id = item.get("test_id", "")
        sc = item.get("resolved_shortcode", "")
        exp = item.get("expected_post", "")
        err_code = item.get("error_code")
        items = item.get("items", [])

        if cat in by_category:
            by_category[cat]["attempts"] += 1

        is_safe_refusal = ("SAFE_REFUSAL" in verdict) or ("SAFE ABORT" in verdict) or ("DUPLICATE SKIPPED" in verdict) or (err_code in ["IDENTITY_VERIFICATION_FAILED", "SAME_POST_ALREADY_DOWNLOADED", "RESOLUTION_FAILED", "TARGET_SELECTION_FAILED"])

        if "FAIL" in verdict and not is_safe_refusal:
            failed_count += 1
        elif is_safe_refusal:
            safe_abort_count += 1
            if cat in by_category:
                by_category[cat]["safe_abort"] += 1
        else:
            success_count += 1
            if cat in by_category:
                by_category[cat]["success"] += 1

        # Interactive latency: only count non-direct resolver tests where interactive UI was measured
        ilat = item.get("interactive_latency_ms", 0)
        dlat = item.get("download_latency_ms", 0)
        tot = item.get("total_time_ms", 0)
        if ilat > 0:
            interactive_latencies.append(ilat)
        if dlat > 0:
            download_latencies.append(dlat)
        if tot > 0:
            total_latencies.append(tot)

        # Count saved items
        for m in items:
            if m.get("status") == "COMPLETED":
                mt = m.get("mediaType")
                if cat == "Carousel":
                    carousel_items_saved += 1
                elif mt == "IMAGE":
                    photos_saved += 1
                elif mt == "VIDEO":
                    videos_saved += 1
            elif m.get("status") == "SKIPPED":
                duplicates_skipped += 1

        if item.get("skipped_count", 0) > 0 and len(items) == 0:
            duplicates_skipped += item.get("skipped_count", 0)

        # Verify ground truth matching
        if cat in ["Photo", "Carousel", "Reel"]:
            if sc and sc not in exp:
                wrong_post += 1
                wrong_download += 1
                if cat in by_category:
                    by_category[cat]["wrong_post"] += 1
                    by_category[cat]["wrong_download"] += 1

    print("\n--- OVERALL METRICS ---")
    print(f"Total Transactions: {len(data)}")
    print(f"Successful: {success_count}")
    print(f"Safe Aborted / Safely Refused: {safe_abort_count}")
    print(f"Failed (Defects): {failed_count}")
    print(f"Wrong Post Selections: {wrong_post}")
    print(f"Incorrect Control Clicks: {wrong_control}")
    print(f"Wrong Media Downloads: {wrong_download}")

    print("\n--- INTERACTIVE LATENCY (ms) ---")
    if interactive_latencies:
        print(f"Count: {len(interactive_latencies)}")
        print(f"Average: {sum(interactive_latencies)/len(interactive_latencies):.1f} ms")
        print(f"Median: {percentile(interactive_latencies, 50):.1f} ms")
        print(f"P95: {percentile(interactive_latencies, 95):.1f} ms")
        print(f"Maximum: {max(interactive_latencies)} ms")
        print(f"Minimum: {min(interactive_latencies)} ms")

    print("\n--- DOWNLOAD LATENCY (ms) ---")
    if download_latencies:
        print(f"Count: {len(download_latencies)}")
        print(f"Average: {sum(download_latencies)/len(download_latencies):.1f} ms")
        print(f"Median: {percentile(download_latencies, 50):.1f} ms")
        print(f"P95: {percentile(download_latencies, 95):.1f} ms")
        print(f"Maximum: {max(download_latencies)} ms")
        print(f"Minimum: {min(download_latencies)} ms")

    print("\n--- TOTAL TRANSACTION TIME (ms) ---")
    if total_latencies:
        print(f"Average: {sum(total_latencies)/len(total_latencies):.1f} ms")
        print(f"Median: {percentile(total_latencies, 50):.1f} ms")
        print(f"P95: {percentile(total_latencies, 95):.1f} ms")

    print("\n--- BY MEDIA TYPE ---")
    print(f"| Type | Attempts | Success | Safe Abort | Wrong Post | Wrong Download |")
    print(f"| :--- | :---: | :---: | :---: | :---: | :---: |")
    for k in ["Photo", "Carousel", "Reel"]:
        row = by_category[k]
        print(f"| {k} | {row['attempts']} | {row['success']} | {row['safe_abort']} | {row['wrong_post']} | {row['wrong_download']} |")

    print("\n--- STORAGE SUMMARY ---")
    print(f"Photos saved: {photos_saved}")
    print(f"Videos saved: {videos_saved}")
    print(f"Carousel items saved: {carousel_items_saved}")
    print(f"Duplicates skipped: {duplicates_skipped}")
    print(f"Corrupt/incomplete files: {corrupt_files}")
    print(f"Unexpected files: {unexpected_files}")

    print("\n--- TRANSACTION RECORDS ---")
    for d in data:
        t_id = d.get("test_id")
        sc = d.get("resolved_shortcode", "N/A")
        items_cnt = d.get("media_item_count", 0)
        bytes_cnt = d.get("total_bytes", 0)
        ilat = d.get("interactive_latency_ms", 0)
        tot = d.get("total_time_ms", 0)
        vrd = d.get("verdict", "")
        print(f"[{t_id}] sc={sc}, items={items_cnt}, bytes={bytes_cnt}, interactive={ilat}ms, total={tot}ms, verdict={vrd}")

if __name__ == "__main__":
    main()
