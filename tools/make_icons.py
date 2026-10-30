"""Writes the app's UI icons as Android vector drawables from the standard Material Icons (Google, Apache License 2.0,
https://github.com/google/material-design-icons), 24 x 24 viewport, filled style. Also writes tools/icons.html, an
icon contact sheet (render it in a browser and screenshot it for the UI audit, taste D23).

Run: python -I tools/make_icons.py
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")

# file name -> (Material Icons name, what it is used for, path data)
ICONS = {
    "ic_back": ("arrow_back", "Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
    "ic_ball": ("circle", "Slider ends (small / big ball)", "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2z"),
    "ic_bring": ("place", "Move to marker", "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"),
    "ic_close": ("close", "Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"),
    "ic_info": ("info", "About", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z"),
    "ic_list": ("format_list_bulleted", "Questions (deck)", "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z"),
    "ic_minus": ("remove", "Room size -", "M19,13H5v-2h14v2z"),
    "ic_plus": ("add", "Room size +", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
    "ic_recall": ("filter_center_focus", "Bring the card back", "M5,15H3v4c0,1.1 0.9,2 2,2h4v-2H5v-4zM5,5h4V3H5c-1.1,0 -2,0.9 -2,2v4h2V5zM19,3h-4v2h4v4h2V5c0,-1.1 -0.9,-2 -2,-2zM19,19h-4v2h4c1.1,0 2,-0.9 2,-2v-4h-2v4zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"),
    "ic_room": ("crop_free", "Room", "M3,5v4h2V5h4V3H5c-1.1,0 -2,0.9 -2,2zM5,15H3v4c0,1.1 0.9,2 2,2h4v-2H5v-4zM19,19h-4v2h4c1.1,0 2,-0.9 2,-2v-4h-2v4zM19,3h-4v2h4v4h2V5c0,-1.1 -0.9,-2 -2,-2z"),
    "ic_sound_off": ("volume_off", "Sound is off", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"),
    "ic_sound_on": ("volume_up", "Sound is on", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"),
    "ic_settings": ("settings", "Settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"),
    "ic_exit": ("logout", "Quit the app", "M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z"),
    "ic_star": ("star", "Stars", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"),
}

UNUSED = ["ic_eye", "ic_eye_off", "ic_globe", "ic_turn_left", "ic_turn_right", "ic_wrap"]


def main():
    for name, (mi, use, path) in ICONS.items():
        with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write(f'<!-- {name}: Material Icons "{mi}" (Google, Apache License 2.0, https://github.com/google/material-design-icons). {use}. -->\n')
            f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n')
            f.write(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />\n</vector>\n')
    for name in UNUSED:
        p = os.path.join(OUT, name + ".xml")
        if os.path.exists(p):
            os.remove(p)
    cells = "".join(
        f'<figure><div class="tile dark"><svg viewBox="0 0 24 24"><path fill="#fff" d="{path}"/></svg></div>'
        f'<div class="tile light"><svg viewBox="0 0 24 24"><path fill="#12161b" d="{path}"/></svg></div>'
        f'<figcaption><b>{name}</b><br>{mi}<br>{use}</figcaption></figure>'
        for name, (mi, use, path) in ICONS.items()
    )
    html = (
        "<!doctype html><meta charset=utf-8><title>Feel the Number icons</title><style>"
        "body{font:13px system-ui;background:#f4f4f4;margin:16px}main{display:grid;grid-template-columns:repeat(5,170px);gap:14px}"
        "figure{margin:0;background:#fff;border-radius:10px;padding:8px}.tile{display:inline-flex;width:72px;height:72px;border-radius:8px;"
        "align-items:center;justify-content:center;margin:2px}.dark{background:#3a3f45}.light{background:#c9cdd1}svg{width:40px;height:40px}"
        "figcaption{margin-top:4px;line-height:1.35}</style><h3>Feel the Number v0.1.6 - UI icons (Material Icons, Apache 2.0), on dark and light fills</h3>"
        f"<main>{cells}</main>"
    )
    with open(os.path.join(HERE, "icons.html"), "w", encoding="utf-8") as f:
        f.write(html)
    print("icons:", len(ICONS))


if __name__ == "__main__":
    main()
