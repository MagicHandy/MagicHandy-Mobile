"""Render matched desktop and Android motion captures. Run with --help for input paths."""

import argparse
import csv
import json
import math
import multiprocessing
import zipfile
from collections import defaultdict
from itertools import pairwise
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from plot_style import BLUE, DARK, DURATION, GRAY, ORANGE, axis, clipped, load
from runtime_plots import application_plot, bridge_plot, outlier_plot, turnaround_plot

MODEL = {
    "handy_original": "Original Handy",
    "handy_2_standard": "Handy 2 Standard",
    "handy_2_pro": "Handy 2 Pro",
}
NAMES = {
    "manual": "Manual Start",
    "full": "Full strokes",
    "short": "Upper 20%",
    "minimum-width": "Upper 10%",
    "compound": "Two short, one full",
    "compound-slow": "Two short, one full · slower legs",
    "regional": "Alternating regions",
    "down-fast": "Faster downstroke",
    "default": "Default field",
    "softest": "Soft turns",
    "cadence": "Steady cadence",
    "creative-v2": "Creative gesture",
}


def dwell(wire):
    return sum(
        max(0, min(DURATION, b[0]) - a[0])
        for a, b in pairwise(wire)
        if a[1] == b[1] and a[0] < DURATION
    )


def detail(args):
    ref_path, before_dir, after_dir, out = args
    ref, old, new = (
        load(ref_path),
        load(before_dir / ref_path.name),
        load(after_dir / ref_path.name),
    )
    ident = ref_path.stem
    name, model, route, speed = (
        ref["name"],
        ref["model"],
        ref["route"],
        ref["score"]["speed_percent"],
    )
    row = {
        "id": ident,
        "shape": name,
        "model": model,
        "route": route,
        "speed": speed,
        "error": " | ".join(x["error"] for x in (ref, old, new) if x.get("error")),
    }
    if row["error"]:
        return row
    reference, before, after = (clipped(x["wire"]) for x in (ref, old, new))
    expected = {t for t in ref["turns"] if t < DURATION}
    row.update(
        desktop=len(reference),
        before=len(before),
        after=len(after),
        exact=after == reference,
        baseline_exact=before == reference,
        missing_turns=len(expected - {t for t, _ in after}),
        desktop_flat_ms=dwell(ref["wire"]),
        before_flat_ms=dwell(old["wire"]),
        after_flat_ms=dwell(new["wire"]),
        desktop_period_ms=ref["period"],
        before_period_ms=old["period"],
        after_period_ms=new["period"],
        max_error_pct=round(new["max_error"], 4),
        peak_wire_pct_s=round(new["peak_wire_speed"], 3),
    )

    fig, axs = plt.subplots(3, 2, figsize=(12, 9.1))
    fig.subplots_adjust(
        top=0.87, bottom=0.065, left=0.075, right=0.975, wspace=0.22, hspace=0.52
    )
    fig.suptitle(
        f"{MODEL[model]}  /  {route.upper()}     {NAMES[name]} at {speed}%",
        x=0.075,
        y=0.972,
        ha="left",
        fontsize=17,
        weight="bold",
        color=DARK,
    )
    fig.text(
        0.075,
        0.926,
        f"First 12 seconds     Desktop {len(reference)} points     ·     Android 0.1.5 {len(before)}     ·     Updated Android {len(after)}",
        fontsize=11,
        color=DARK,
    )
    fig.text(
        0.075,
        0.898,
        "Dashed graphite = desktop     •     Amber = Android 0.1.5     •     Blue = updated Android",
        fontsize=9,
        color=GRAY,
    )
    axis(axs[0, 0], "Whole-loop motion", "Stroke (%)")
    axis(axs[0, 1], "First 12 seconds of encoded points", "Stroke (%)")
    axis(axs[1, 0], "First turnaround in detail", "Stroke (%)")
    axis(axs[1, 1], "Velocity through the turnaround", "Velocity (%/s)")
    axis(axs[2, 0], "Phase portrait", "Velocity (%/s)", x="Stroke (%)")
    axis(axs[2, 1], "Planned acceleration at the turn", "Acceleration (%/s²)")
    turn = next((t for t in ref["turns"] if t > 0), 500) / 1000
    window = (max(0, turn - min(0.6, turn * 0.72)), turn + min(0.6, turn * 0.72))
    # Draw the reference last. Dashed lines leave coincident updated lines visible.
    for data, color, ls, order in [
        (old, ORANGE, "-", 1),
        (new, BLUE, "-", 2),
        (ref, DARK, "--", 3),
    ]:
        plan = np.asarray(data["loop"])
        wire = np.asarray(data["wire"])
        x = wire[:, 0] / 1000
        y = wire[:, 1]
        clock = plan[:, 0] / 1000
        lw = 1.35 if order < 3 else 1.0
        axs[0, 0].plot(clock, plan[:, 1], ls, color=color, lw=lw)
        axs[0, 1].plot(x, y, ls, color=color, lw=lw * 0.8, marker=".", ms=2.2)
        axs[1, 0].plot(
            x, y, ls, color=color, lw=lw, marker="o" if order != 3 else None, ms=3
        )
        velocity = np.diff(y) / np.diff(x)
        axs[1, 1].stairs(velocity, x, color=color, ls=ls, lw=lw, baseline=None)
        axs[2, 0].plot(plan[:, 1], plan[:, 2], ls, color=color, lw=lw * 0.87)
        axs[2, 1].plot(clock, plan[:, 3], ls, color=color, lw=lw)
    axs[0, 1].set_xlim(0, 12)
    for ax in [axs[1, 0], axs[1, 1], axs[2, 1]]:
        ax.set_xlim(*window)
    for ax in axs.flat:
        ax.margins(y=0.10)
    fig.text(
        0.075,
        0.012,
        "Planned derivatives and encoded commands. Physical carriage motion was not measured.",
        fontsize=8,
        color=GRAY,
    )
    fig.savefig(out / "plots" / f"{ident}.png", dpi=92)
    plt.close(fig)
    return row


def overview(rows, root, out):
    result = []
    groups = defaultdict(list)
    for row in rows:
        groups[row["model"], row["route"]].append(row)
    for (model, route), group in groups.items():
        group.sort(key=lambda r: (list(NAMES).index(r["shape"]), r["speed"]))
        for ix in range(0, len(group), 12):
            subset = group[ix : ix + 12]
            fig, axes = plt.subplots(
                math.ceil(len(subset) / 3),
                3,
                figsize=(12, 2.2 * math.ceil(len(subset) / 3) + 0.9),
                squeeze=False,
            )
            fig.subplots_adjust(
                left=0.045, right=0.99, bottom=0.065, top=0.88, hspace=0.62, wspace=0.20
            )
            fig.suptitle(
                f"{MODEL[model]}  /  {route.upper()}     First four seconds",
                x=0.045,
                ha="left",
                weight="bold",
                fontsize=15,
            )
            fig.text(
                0.045,
                0.918,
                "Point counts cover 12 seconds: previous Android / updated Android / desktop. Each panel shows stroke position from 0 to 100%.",
                fontsize=8,
                color=GRAY,
            )
            for ax, row in zip(axes.flat, subset):
                ax.set_title(
                    f"{NAMES[row['shape']]} · {row['speed']}%",
                    fontsize=8.7,
                    loc="left",
                    weight="bold",
                )
                if row["error"]:
                    ax.text(0.2, 0.5, "Capture failed")
                    continue
                for folder, color, style in [
                    ("before", ORANGE, "-"),
                    ("after", BLUE, "-"),
                    ("reference", DARK, "--"),
                ]:
                    a = np.asarray(load(root / folder / (row["id"] + ".json"))["wire"])
                    ax.plot(
                        a[:, 0] / 1000,
                        a[:, 1],
                        style,
                        color=color,
                        lw=1,
                        marker=".",
                        ms=1.8,
                    )
                ax.set(
                    xlim=(0, 4), ylim=(-5, 105), xticks=[0, 2, 4], yticks=[0, 50, 100]
                )
                ax.tick_params(labelsize=7)
                ax.grid(alpha=0.2)
                ax.set_xlabel(
                    f"{row['before']} / {row['after']} / {row['desktop']} points",
                    fontsize=8,
                )
            for ax in list(axes.flat)[len(subset) :]:
                ax.set_visible(False)
            path = f"overview-{model}-{route}-{ix // 12 + 1}.png"
            fig.savefig(out / "plots" / path, dpi=96)
            plt.close(fig)
            result.append(
                [f"{MODEL[model]} · {route.upper()} · {ix // 12 + 1}", "plots/" + path]
            )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "captures",
        type=Path,
        help="directory containing reference, before and after JSON directories",
    )
    parser.add_argument("output", type=Path)
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()
    root, out = args.captures.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    (out / "plots").mkdir(exist_ok=True)
    files = sorted((root / "reference").glob("*.json"))
    if not files:
        parser.error("no desktop cases found")
    with multiprocessing.Pool(args.workers) as pool:
        rows = pool.map(
            detail, [(f, root / "before", root / "after", out) for f in files]
        )
    sheets = overview(rows, root, out)
    application_plot(root, out)
    bridge_plot(root, out)
    turnaround_plot(root, out)
    residual = next((r for r in rows if not r["error"] and not r["exact"]), None)
    difference = None
    if residual:
        title = f"{MODEL[residual['model']]} · {NAMES[residual['shape']]} at {residual['speed']}%"
        difference = outlier_plot(root, out, residual, title)
    with (out / "metrics.csv").open("w", newline="", encoding="utf8") as stream:
        writer = csv.DictWriter(
            stream, fieldnames=sorted(set().union(*(r.keys() for r in rows)))
        )
        writer.writeheader()
        writer.writerows(rows)
    with zipfile.ZipFile(
        out / "captures.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=5
    ) as archive:
        for directory in [
            "reference",
            "before",
            "after",
            "wire-before",
            "wire",
            "bridge",
        ]:
            for p in sorted((root / directory).glob("*.json")):
                archive.write(p, p.relative_to(root))
        for name in ["provenance.json", "comparison.json"]:
            p = root / "legacy-run" / name
            if p.is_file():
                archive.write(p, "verification/" + name)
    manifest = {
        "cases": rows,
        "sheets": sheets,
        "models": MODEL,
        "shapes": NAMES,
        "outlier_difference": difference,
        "has_neon": (out / "gemini-neon-review.zip").is_file(),
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf8")
    template = Path(__file__).with_name("atlas.html").read_text(encoding="utf8")
    (out / "index.html").write_text(
        template.replace("/*ATLAS*/{}", json.dumps(manifest)), encoding="utf8"
    )
    print(f"Rendered {len(rows)} cases and {len(sheets)} overview sheets to {out}")


if __name__ == "__main__":
    main()
