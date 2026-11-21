"""Labels, units and plot styling for the matched motion figures."""

import json

BLUE = "#1467ad"
ORANGE = "#b35014"
DARK = "#272c35"
GRAY = "#647183"
DURATION = 12000


def load(path):
    return json.loads(path.read_bytes())


def axis(ax, title, unit, x="Time (s)"):
    ax.set_title(title, loc="left", fontsize=10, fontweight="bold", pad=7, color=DARK)
    ax.set(xlabel=x, ylabel=unit)
    ax.tick_params(labelsize=8, colors=GRAY)
    ax.grid(True, color="#dfe4e8", linewidth=0.55)
    for spine in ax.spines.values():
        spine.set_visible(False)


def clipped(wire):
    return [(t, x) for t, x in wire if t < DURATION]
