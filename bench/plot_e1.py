#!/usr/bin/env python3
"""
Renders the E1 figure straight from e1_results.csv.

Two panels, each internally consistent in pool regime:
  A. pages read per query, COLD pool  -- the deterministic, algorithmic metric.
     (WARM index reads 0 pages, which no log axis can plot; the cold number, 1,
     is the honest steady-state cost of a seek.)
  B. median latency, WARM pool -- steady state, and the number people expect to see.

Light and dark variants are emitted so the README can serve each via <picture>.
Palette is the validated categorical pair: slot 1 (blue) = index, slot 2 (orange) = scan.
"""
import csv
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CSV = os.path.join(ROOT, "e1_results.csv")
OUT = os.path.join(ROOT, "docs")

THEMES = {
    "light": dict(surface="#fcfcfb", primary="#0b0b0b", secondary="#52514e",
                  muted="#78766f", grid="#e4e3df", index="#2a78d6", scan="#eb6834"),
    "dark":  dict(surface="#1a1a19", primary="#ffffff", secondary="#c3c2b7",
                  muted="#8f8d83", grid="#333331", index="#3987e5", scan="#d95926"),
}


def load():
    rows = []
    with open(CSV) as fh:
        for r in csv.DictReader(fh):
            rows.append(r)
    return rows


def series(rows, mode, regime, field, cast=float):
    picked = [r for r in rows if r["mode"] == mode and r["regime"] == regime]
    picked.sort(key=lambda r: int(r["n"]))
    return [int(r["n"]) for r in picked], [cast(r[field]) for r in picked]


def human(n):
    if n >= 1_000_000:
        return f"{n // 1_000_000}M"
    if n >= 1_000:
        return f"{n // 1_000}K"
    return str(n)


def render(rows, theme_name):
    t = THEMES[theme_name]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11.5, 4.6))
    fig.patch.set_facecolor(t["surface"])

    panels = [
        (ax1, "pages_read", "COLD", "Pages read per query",
         "cold buffer pool - deterministic, no timer involved", "pages", int),
        (ax2, "median_us", "WARM", "Median latency",
         "warm buffer pool - steady state, 51 samples per point", "microseconds", float),
    ]

    for ax, field, regime, title, subtitle, ylabel, cast in panels:
        ax.set_facecolor(t["surface"])
        for mode, color, label in (("index", t["index"], "Index seek"),
                                   ("scan", t["scan"], "Full scan")):
            xs, ys = series(rows, mode, regime, field, cast)
            ax.plot(xs, ys, color=color, linewidth=2, marker="o", markersize=8,
                    markeredgecolor=t["surface"], markeredgewidth=2,
                    label=label, zorder=3, clip_on=False)

        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlim(700, 1_600_000)
        ax.set_xticks([1_000, 10_000, 100_000, 1_000_000])
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: human(int(v))))
        ax.tick_params(axis="both", colors=t["secondary"], labelsize=10, length=0)
        ax.grid(True, which="major", color=t["grid"], linewidth=1, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right", "left", "bottom"):
            ax.spines[side].set_visible(False)

        ax.set_title(title, color=t["primary"], fontsize=13, fontweight="600",
                     loc="left", pad=18)
        ax.text(0, 1.02, subtitle, transform=ax.transAxes, color=t["muted"],
                fontsize=9.5, ha="left", va="bottom")
        ax.set_xlabel("rows in table (log)", color=t["secondary"], fontsize=10)
        ax.set_ylabel(ylabel, color=t["secondary"], fontsize=10)

    # Direct labels, placed in axes fractions so they cannot collide with the marks:
    # the scan line climbs left-to-right, leaving the upper-left of each panel empty,
    # and the index line hugs the bottom.
    ax1.text(0.03, 0.88, "full scan\n1 page per ~120 rows", transform=ax1.transAxes,
             color=t["scan"], fontsize=10.5, fontweight="bold", va="top", linespacing=1.5)
    ax1.text(0.03, 0.11, "index seek - 1 page at every n", transform=ax1.transAxes,
             color=t["index"], fontsize=10.5, fontweight="bold", va="bottom")
    ax1.set_ylim(0.7, 20_000)

    ax2.text(0.03, 0.88, "full scan\n34 us at 1K -> 29.6 ms at 1M", transform=ax2.transAxes,
             color=t["scan"], fontsize=10.5, fontweight="bold", va="top", linespacing=1.5)
    ax2.text(0.03, 0.11, "index seek - flat, sub-microsecond", transform=ax2.transAxes,
             color=t["index"], fontsize=10.5, fontweight="bold", va="bottom")
    ax2.set_ylim(0.3, 90_000)

    # Legend lives in the header strip, clear of both plot areas.
    handles, labels = ax1.get_legend_handles_labels()
    leg = fig.legend(handles, labels, loc="upper right", bbox_to_anchor=(0.995, 0.995),
                     ncol=2, frameon=False, fontsize=10.5, handlelength=1.6,
                     columnspacing=1.6)
    for text in leg.get_texts():
        text.set_color(t["secondary"])

    fig.text(0.008, 0.955, "MiniDB E1 - index seek vs full scan, 1K to 1M rows",
             color=t["primary"], fontsize=15, fontweight="bold", ha="left", va="top")
    fig.text(0.008, 0.888,
             "Same query, same rows, two access paths. Pool fixed at 64 frames; B+Tree fanout 128.",
             color=t["muted"], fontsize=10, ha="left", va="top")

    fig.tight_layout(rect=(0, 0, 1, 0.855))
    path = os.path.join(OUT, f"e1-index-vs-scan-{theme_name}.png")
    fig.savefig(path, dpi=160, facecolor=t["surface"])
    plt.close(fig)
    print("wrote", path)


rows = load()
os.makedirs(OUT, exist_ok=True)
for name in THEMES:
    render(rows, name)
