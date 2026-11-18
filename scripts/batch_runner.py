import subprocess
import time
import json
import re
import os
import sys

ADB_PATH = r"C:\Users\msmay\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEVICE_ID = "192.168.1.3:33551"

def run_adb(cmd, timeout=15):
    full_cmd = [ADB_PATH, "-s", DEVICE_ID] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    return res.stdout.strip()

def scroll_feed(direction="down", distance=1100, duration=350):
    if direction == "down":
        start_y = 1700
        end_y = start_y - distance
    else:
        start_y = 600
        end_y = start_y + distance
    run_adb(["shell", "input", "swipe", "500", str(start_y), "500", str(end_y), str(duration)])
    time.sleep(1.2)

def scroll_reel():
    # Reels tab full screen vertical swipe
    run_adb(["shell", "input", "swipe", "500", "1800", "500", "300", "280"])
    time.sleep(1.5)

def switch_tab(tab_name):
    # Tab coordinates on 1080x2400: y=2242
    # feed: 108, clips/reels: 324, direct: 540, explore: 756, profile: 972
    coords = {
        "feed": (108, 2242),
        "reels": (324, 2242),
        "explore": (756, 2242)
    }
    if tab_name in coords:
        x, y = coords[tab_name]
        run_adb(["shell", "input", "tap", str(x), str(y)])
        time.sleep(1.5)

def get_dominant_author():
    """Reads the current dominant author name from logcat or screen dump."""
    run_adb(["shell", "uiautomator", "dump", "/sdcard/post_dump.xml"])
    xml_str = run_adb(["shell", "cat", "/sdcard/post_dump.xml"])
    # Search for user/author names
    names = re.findall(r'content-desc="([^"]+?)\'s profile picture"', xml_str)
    if not names:
        names = re.findall(r'resource-id="com\.instagram\.android:id/(?:row_feed_photo_profile_name|author_name|user_name)"[^>]*text="([^"]+)"', xml_str)
    if names:
        return names[0]
    return ""

def run_single_trial(trial_num, category, expected_post="", adversarial=""):
    cmd = [
        sys.executable,
        r"scripts\validation_harness.py",
        "--trial", str(trial_num),
        "--category", category,
        "--expected-post", expected_post,
        "--adversarial", adversarial
    ]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(p.stdout)
    if p.stderr:
        print("STDERR:", p.stderr)
