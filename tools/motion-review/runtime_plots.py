"""Plots of actual command delivery, bridge reversals and the residual HSP difference."""

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from plot_style import BLUE, DARK, DURATION, GRAY, ORANGE, axis, clipped, load


def application_plot(root, out):
    fig, axes = plt.subplots(4, 2, figsize=(13, 9), sharex="col")
    fig.subplots_adjust(top=0.885, bottom=0.07, hspace=0.36, wspace=0.22)
    fig.suptitle(
        "Handy commands captured during application playback",
        x=0.09,
        ha="left",
        fontsize=17,
        weight="bold",
    )
    fig.text(
        0.09,
        0.92,
        "25% requested speed   ·   Retarget at 13.25 s   ·   Stop at 20.65 s   ·   Open markers show previously queued endpoints",
        fontsize=9,
        color=GRAY,
    )
    for i, case in enumerate(["cloud-manual", "cloud-chat", "ble-manual", "ble-chat"]):
        for folder, color, lab in [
            ("wire-before", ORANGE, "0.1.5"),
            ("wire", BLUE, "updated"),
        ]:
            trace = load(root / folder / (case + ".json"))
            stop = trace["stop_ms"] / 1000
            adds = [e for e in trace["events"] if e["op"] == "add"]
            wire = np.array([p for e in adds for p in e["points"]])
            t = wire[:, 0] / 1000
            axes[i, 0].plot(t, wire[:, 1], color=color, lw=1, alpha=0.88)
            axes[i, 0].scatter(t[t < stop], wire[t < stop, 1], s=3, color=color)
            axes[i, 0].scatter(
                t[t >= stop],
                wire[t >= stop, 1],
                s=12,
                edgecolors=color,
                facecolors="none",
            )
            # Sawtooth coverage is measured on the controlled session clock between admissions.
            last = 0
            x = []
            y = []
            for e in adds:
                send = e["at_ms"] / 1000
                tail = e["points"][-1][0] / 1000
                x.extend([send, send])
                y.extend([max(0, last - send), max(0, tail - send)])
                last = tail
            x.append(stop)
            y.append(max(0, last - stop))
            axes[i, 1].plot(x, y, color=color, lw=1.3, label=lab)
        for ax in axes[i]:
            ax.axvline(13.25, color="#839683", ls=":", lw=1.3)
            ax.axvline(stop, color=DARK, ls="--", lw=1.2)
        axes[i, 0].axvspan(stop, max(t), color="#dadee2", alpha=0.8, zorder=0)
        axis(
            axes[i, 0],
            case.replace("-", " · ").upper(),
            "Stroke (%)",
            "Time (s)" if i == 3 else "",
        )
        axis(
            axes[i, 1], "Accepted buffer ahead", "Seconds", "Time (s)" if i == 3 else ""
        )
    axes[0, 1].legend(loc="upper right", ncol=2, fontsize=8)
    fig.savefig(out / "plots/application-playback.png", dpi=106)
    plt.close(fig)


def bridge_plot(root, out):
    data = {
        k: load(root / "bridge" / (k + ".json")) for k in ["before", "after", "desktop"]
    }
    cases = list(data["desktop"])
    fig, axs = plt.subplots(len(cases), 2, figsize=(12, 2.08 * len(cases) + 0.8))
    fig.subplots_adjust(top=0.91, bottom=0.06, hspace=0.69, wspace=0.22)
    fig.suptitle(
        "Handy through Intiface · physical endpoint steps",
        x=0.075,
        ha="left",
        weight="bold",
        fontsize=16,
    )
    fig.text(
        0.075,
        0.938,
        "Amber = previous Android     •     Blue = updated Android     •     Dashed graphite = desktop",
        fontsize=9,
        color=GRAY,
    )
    for axpair, case in zip(axs, cases):
        nums = []
        for label, color, ls in [
            ("before", ORANGE, "-"),
            ("after", BLUE, "-"),
            ("desktop", DARK, "--"),
        ]:
            c = data[label][case]
            pts = np.asarray(c["wire"])
            nums.append(len(clipped(c["wire"])))
            if not pts.size:
                continue
            for ax in axpair:
                ax.plot(
                    pts[:, 0] / 1000,
                    pts[:, 1],
                    ls,
                    color=color,
                    marker=".",
                    ms=4,
                    lw=1.3,
                )
        for ax in axpair:
            axis(
                ax,
                case.replace("-", " · ")
                + "   /   "
                + ("first 4 s" if ax is axpair[0] else "first turnaround"),
                "Stroke (%)",
            )
        axpair[0].set_xlim(0, 4)
        turns = data["desktop"][case]["turns"]
        first = next((t / 1000 for t in turns if t > 0), 0.5)
        axpair[1].set_xlim(max(0, first - 0.19), first + 0.19)
        if not nums[1]:
            axpair[1].text(
                0.5,
                0.19,
                "Updated Android and desktop\nreject this spacing before output",
                ha="center",
                transform=axpair[1].transAxes,
                color=DARK,
                fontsize=9,
                bbox={"color": "white", "alpha": 0.92},
            )
        else:
            axpair[0].text(
                0.01,
                0.03,
                "12 s points: " + " / ".join(map(str, nums)),
                transform=axpair[0].transAxes,
                fontsize=8,
                bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.8},
            )
    fig.savefig(out / "plots/bridge-turns.png", dpi=104)
    plt.close(fig)


def turnaround_plot(root, out):
    fig, axs = plt.subplots(2, 2, figsize=(11, 6.6))
    fig.subplots_adjust(
        top=0.825, bottom=0.085, left=0.08, right=0.98, wspace=0.22, hspace=0.47
    )
    fig.suptitle(
        "Manual Start and chat at the first reversal",
        x=0.08,
        y=0.97,
        ha="left",
        fontsize=19,
        weight="bold",
        color=DARK,
    )
    fig.text(
        0.08,
        0.912,
        "Original Handy · full strokes at 25% · transmitted HTTP points, with desktop alpha.89 overlaid",
        fontsize=10,
        color=DARK,
    )
    fig.text(
        0.08,
        0.865,
        "Amber = Android 0.1.5     •     Blue = Android 0.1.6     •     Dashed graphite = desktop",
        fontsize=9,
        color=GRAY,
    )
    for row, scenario in enumerate(["manual", "chat"]):
        old = load(root / "wire-before" / ("cloud-" + scenario + ".json"))
        new = load(root / "wire" / ("cloud-" + scenario + ".json"))
        streams = [
            np.asarray(
                [p for e in trace["events"] if e["op"] == "add" for p in e["points"]]
            )
            for trace in (old, new)
        ]
        ref = load(
            root
            / "reference"
            / (
                "handy_original-"
                + ("manual" if row == 0 else "full")
                + "-025-cloud.json"
            )
        )
        counts = [sum(array[:, 0] < DURATION) for array in streams]
        axis(
            axs[row, 0],
            f"{'Manual Start' if row == 0 else 'Chat full sweeps'}  ·  {counts[0]} → {counts[1]} points in 12 s",
            "Stroke (%)",
        )
        axis(axs[row, 1], "Detail around the first reversal", "Stroke (%)")
        for stream, color, style in [
            (streams[0], ORANGE, "-"),
            (streams[1], BLUE, "-"),
            (np.array(ref["wire"]), DARK, "--"),
        ]:
            for ax in axs[row]:
                ax.plot(
                    stream[:, 0] / 1000,
                    stream[:, 1],
                    style,
                    color=color,
                    lw=1.6 if style == "-" else 1,
                    marker="o" if style == "-" else None,
                    ms=3,
                )
        axs[row, 0].set(xlim=(0, 1.82), ylim=(-3, 104))
        axs[row, 1].set(xlim=(0.55, 1.265), ylim=(55, 103))
    fig.savefig(out / "plots/turnaround-comparison.png", dpi=116)
    plt.close(fig)


def outlier_plot(root, out, row, title):
    case = row["id"] + ".json"
    desktop = load(root / "reference" / case)
    android = load(root / "after" / case)
    first = np.asarray(desktop["wire"])
    second = np.asarray(android["wire"])
    times = np.arange(DURATION)
    reference = np.interp(times, first[:, 0], first[:, 1])
    updated = np.interp(times, second[:, 0], second[:, 1])
    difference = np.abs(updated - reference)
    peak = float(difference.max())
    changed = np.flatnonzero(difference > 1e-7)
    if len(changed):
        visible = slice(
            max(0, int(changed[0]) - 75), min(DURATION, int(changed[-1]) + 75)
        )
        times, updated, reference = times[visible], updated[visible], reference[visible]
    fig, axs = plt.subplots(1, 2, figsize=(11, 3.6))
    fig.subplots_adjust(top=0.77, left=0.08, right=0.975, bottom=0.18, wspace=0.25)
    fig.suptitle(
        title,
        x=0.08,
        y=0.965,
        ha="left",
        weight="bold",
        fontsize=15,
    )
    fig.text(
        0.08,
        0.838,
        f"Desktop and updated Android · {row['desktop']} and {row['after']} points in 12 s · {row['missing_turns']} missing turn times",
        fontsize=9,
        color=GRAY,
    )
    for values, color, style, lab in [
        (second, BLUE, "-", "Android 0.1.6"),
        (first, DARK, "--", "Desktop"),
    ]:
        axs[0].plot(
            values[:, 0] / 1000,
            values[:, 1],
            style,
            color=color,
            marker=".",
            ms=6,
            lw=1.4,
            label=lab,
        )
    axis(axs[0], "Turn and outgoing stroke", "Stroke (%)")
    axs[0].set_xlim(times[0] / 1000, times[-1] / 1000)
    axs[0].legend(fontsize=8, loc="upper left")
    axis(axs[1], "Interpolated wire difference", "Percentage points")
    axs[1].plot(times / 1000, updated - reference, color=BLUE)
    axs[1].axhline(0, lw=0.65, color=DARK)
    margin = max(0.1, peak * 1.15)
    axs[1].set(ylim=(-margin, margin))
    fig.savefig(out / "plots/pro-outlier.png", dpi=105)
    plt.close(fig)
    return round(peak, 3)
