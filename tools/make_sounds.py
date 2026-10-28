"""Synthesises the app's three short sounds into app/src/main/res/raw (16-bit mono 44.1 kHz, CC0 by construction).

pop   - the reveal: a soft bubble "pop" (pitch drop 900 -> 300 Hz, 120 ms, quick decay)
chime - three stars: two bell partials, E6 then B6, 600 ms
tick  - wall snap: a 25 ms damped click at 1.8 kHz
"""
import math, struct, sys, wave, os

RATE = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")


def write(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s / peak * 0.8)) * 32767)) for s in samples))
    print(name, len(samples) / RATE, "s")


def pop():
    n = int(0.12 * RATE)
    out, ph = [], 0.0
    for i in range(n):
        t = i / RATE
        f = 300 + 600 * math.exp(-t * 40)
        ph += 2 * math.pi * f / RATE
        env = min(1.0, t / 0.004) * math.exp(-t * 35)
        out.append(math.sin(ph) * env)
    return out


def chime():
    n = int(0.6 * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        s = 0.0
        for f0, start in ((1318.5, 0.0), (1975.5, 0.12)):
            if t >= start:
                u = t - start
                env = min(1.0, u / 0.003) * math.exp(-u * 6)
                s += (math.sin(2 * math.pi * f0 * u) + 0.3 * math.sin(2 * math.pi * f0 * 2.76 * u) * math.exp(-u * 10)) * env
        out.append(s)
    return out


def tick():
    n = int(0.025 * RATE)
    return [math.sin(2 * math.pi * 1800 * i / RATE) * math.exp(-i / RATE * 220) for i in range(n)]


if __name__ == "__main__":
    write("pop", pop())
    write("chime", chime())
    write("tick", tick())
