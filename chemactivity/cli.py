"""Forwards `python -m chemactivity.cli` to `python -m chemrob.cli`."""
import sys

from chemrob.cli import build_parser, main  # noqa: F401

if __name__ == "__main__":
    print("Note: `chemactivity` is now `chemrob` - use `python -m chemrob.cli`.\n",
          file=sys.stderr)
    raise SystemExit(main())
