"""
Terminal flier.

Two things make this less trivial than it looks on Windows:

  * The default console code page is cp1252, so the block-drawing characters
    that make up the large lettering raise UnicodeEncodeError unless
    PYTHONIOENCODING=utf-8 is set. Printing a banner is never worth crashing
    over, so capability is tested up front and an ASCII version is used when
    the console cannot take the Unicode one.
  * ANSI colour needs virtual-terminal processing enabled on older consoles,
    and should be off entirely when output is piped to a file.

Both are detected rather than assumed. The art is kept under 78 columns so it
survives an 80-column terminal without wrapping into nonsense.
"""
from __future__ import annotations

import json
import os
import sys

from . import APP_NAME, __version__

# --------------------------------------------------------------------------
# Capability detection
# --------------------------------------------------------------------------
def _console_supports_unicode() -> bool:
    enc = getattr(sys.stdout, "encoding", None) or "ascii"
    try:
        "\u2588\u2554\u2b21".encode(enc)
        return True
    except (UnicodeEncodeError, LookupError):
        return False


def _enable_windows_vt() -> bool:
    """Turn on ANSI escape handling in the Windows console; harmless elsewhere."""
    if os.name != "nt":
        return True
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32
        # -11 = STD_OUTPUT_HANDLE, 0x0004 = ENABLE_VIRTUAL_TERMINAL_PROCESSING
        handle = kernel32.GetStdHandle(-11)
        mode = ctypes.c_ulong()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        return bool(kernel32.SetConsoleMode(handle, mode.value | 0x0004))
    except Exception:  # noqa: BLE001
        return False


def _use_colour() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if not sys.stdout.isatty():
        return False
    if os.environ.get("TERM") == "dumb":
        return False
    return _enable_windows_vt()


# --------------------------------------------------------------------------
# Art
# --------------------------------------------------------------------------
_UNICODE_ART = [
    " ██████╗██╗  ██╗███████╗███╗   ███╗██████╗  ██████╗ ██████╗   ██████╗     ██╗",
    "██╔════╝██║  ██║██╔════╝████╗ ████║██╔══██╗██╔═══██╗██╔══██╗  ██╔══██╗    ██║",
    "██║     ███████║█████╗  ██╔████╔██║██████╔╝██║   ██║██████╔╝  ██║  ██║    ██║",
    "██║     ██╔══██║██╔══╝  ██║╚██╔╝██║██╔══██╗██║   ██║██╔══██╗  ██║  ██║    ██║",
    "╚██████╗██║  ██║███████╗██║ ╚═╝ ██║██║  ██║╚██████╔╝██████╔╝  ██████╔╝█████╔╝",
    " ╚═════╝╚═╝  ╚═╝╚══════╝╚═╝     ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝   ╚═════╝ ╚════╝ ",
]

_ASCII_ART = [
    "   _____ _                 _____       _        _____     _ ",
    "  / ____| |               |  __ \\     | |      |  __ \\   | |",
    "  | |    | |__   ___ _ __  | |__) |___ | |__    | |  | |  | |",
    "  | |    | '_ \\ / _ \\ '_ \\ |  _  // _ \\| '_ \\   | |  | |  | |",
    "  | |____| | | |  __/ | | || | \\ \\ (_) | |_) |  | |__| | _| |",
    "   \\_____|_| |_|\\___|_| |_||_|  \\_\\___/|_.__/   |_____/  \\__/",
]

TAGLINE = "Structure-based pharmacological activity prediction & lead optimization"


class _C:
    """ANSI codes, blanked out when colour is unavailable."""

    def __init__(self, on: bool) -> None:
        self.reset = "\033[0m" if on else ""
        self.dim = "\033[2m" if on else ""
        self.bold = "\033[1m" if on else ""
        self.blue = "\033[38;5;33m" if on else ""
        self.sky = "\033[38;5;75m" if on else ""
        self.cyan = "\033[38;5;44m" if on else ""
        self.grey = "\033[38;5;245m" if on else ""
        self.green = "\033[38;5;35m" if on else ""
        self.amber = "\033[38;5;179m" if on else ""


def _model_stats() -> list[str]:
    """
    Headline numbers from the trained bundle, if one is present.

    Read from metadata rather than hard-coded, so the flier cannot drift out of
    step with whatever model is actually installed. Silently returns nothing
    when no bundle exists - a first-run user should not meet a stack trace.
    """
    try:
        from .config import ARTIFACT_DIR

        meta = json.loads((ARTIFACT_DIR / "metadata.json").read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return []

    bits: list[str] = []
    if meta.get("n_molecules"):
        bits.append(f"{meta['n_molecules']:,} molecules")
    if meta.get("classes"):
        from .config import LIABILITY_CLASS_KEYS

        n_liab = sum(1 for k in meta["classes"] if k in LIABILITY_CLASS_KEYS)
        n_ther = len(meta["classes"]) - n_liab
        bits.append(f"{n_ther} activity classes"
                    + (f" + {n_liab} safety" if n_liab else ""))
    if meta.get("targets"):
        bits.append(f"{len(meta['targets'])} targets")
    return bits


def render_banner(*, subtitle: str | None = None, show_tips: bool = True) -> str:
    """Build the flier as a string, adapted to the current console."""
    unicode_ok = _console_supports_unicode()
    c = _C(_use_colour())
    art = _UNICODE_ART if unicode_ok else _ASCII_ART
    rule_ch = "─" if unicode_ok else "-"
    bullet = "›" if unicode_ok else ">"
    dot = "·" if unicode_ok else "-"
    width = 78

    lines: list[str] = [""]
    # Fade the wordmark from deep blue into a lighter sky tone down the rows.
    for i, row in enumerate(art):
        shade = c.blue if i < 3 else c.sky
        lines.append(f" {shade}{row}{c.reset}")

    lines.append("")
    lines.append(f"  {c.bold}{APP_NAME}{c.reset}  {c.dim}v{__version__}{c.reset}")
    lines.append(f"  {c.grey}{subtitle or TAGLINE}{c.reset}")

    stats = _model_stats()
    if stats and show_tips:
        lines.append(f"  {c.cyan}{f' {dot} '.join(stats)}{c.reset}")

    lines.append(f"  {c.dim}{rule_ch * width}{c.reset}")

    if show_tips:
        # Commands are shown without the `python -m chemrob.cli` prefix and the
        # prefix given once above: spelled out on every row the lines run to 88
        # columns and wrap into unreadable fragments on a standard terminal.
        arrow = "->" if not unicode_ok else "→"
        lines.append(f"  {c.dim}Run as:{c.reset} "
                     f"{c.bold}python -m chemrob.cli {c.reset}{c.dim}<command>{c.reset}")
        lines.append("")
        tips = [
            ('predict --smiles "..."', "activity, potency, safety, explanation"),
            ('predict --smiles "..." --disease X', "anchored to an indication"),
            ('optimize --smiles "..." --class Y', "ranked structural edits"),
            ("screen library.csv", "score a whole file at once"),
            ("serve", f"workbench {arrow} 127.0.0.1:8000/app"),
        ]
        for cmd, what in tips:
            lines.append(
                f"  {c.green}{bullet}{c.reset} {c.cyan}{cmd:<35}{c.reset}"
                f"{c.grey}{what}{c.reset}"
            )
        lines.append("")
        lines.append(
            f"  {c.amber}Predictions are hypotheses for prioritising synthesis and assay "
            f"work.{c.reset}"
        )
        lines.append(
            f"  {c.dim}Read the applicability-domain line first {dot} "
            f"--help on any command{c.reset}"
        )
    lines.append("")
    return "\n".join(lines)


def print_banner(**kwargs) -> None:
    """Print the flier, degrading to plain text rather than ever raising."""
    try:
        sys.stdout.write(render_banner(**kwargs) + "\n")
    except UnicodeEncodeError:
        sys.stdout.write(f"\n  {APP_NAME}\n  {TAGLINE}\n\n")


def banner_lines_for_log() -> str:
    """Colour-free, ASCII-only variant for log files and non-tty output."""
    return "\n".join(_ASCII_ART) + f"\n\n  {APP_NAME}\n  {TAGLINE}\n"
