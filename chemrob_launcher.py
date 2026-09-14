"""
Entry point for the frozen ChemRobDJ.exe.

Double-clicking the exe should do the obvious thing - open the workbench - while
passing arguments should behave exactly like the CLI. So: no arguments means
serve, anything else is handed straight to the normal command parser.

`multiprocessing.freeze_support()` comes first because scikit-learn and joblib
spawn worker processes, and in a frozen build each of those re-runs this file.
Without the guard the app would fork copies of itself instead of starting.
"""
from __future__ import annotations

import multiprocessing
import sys
import threading
import time
import webbrowser


def _open_browser(url: str, delay: float = 4.0) -> None:
    def go() -> None:
        time.sleep(delay)
        try:
            webbrowser.open(url)
        except Exception:
            pass
    threading.Thread(target=go, daemon=True).start()


def main() -> int:
    from chemrob.cli import main as cli_main

    if len(sys.argv) > 1:
        return cli_main(sys.argv[1:])

    # Double-clicked: start the web app and open it.
    port = 8000
    _open_browser(f"http://127.0.0.1:{port}/app")
    print()
    print("  If your browser does not open, go to:")
    print(f"      http://127.0.0.1:{port}/app")
    print("  Close this window to stop ChemRob DJ.")
    print()
    return cli_main(["serve", "--port", str(port)])


if __name__ == "__main__":
    multiprocessing.freeze_support()
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n  Stopped.")
        raise SystemExit(0)
