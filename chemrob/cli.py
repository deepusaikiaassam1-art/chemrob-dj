"""
Command-line interface.

    python -m chemrob.cli predict  --smiles "CC(=O)Oc1ccccc1C(=O)O"
    python -m chemrob.cli predict  --smiles "..." --disease "visceral leishmaniasis"
    python -m chemrob.cli optimize --smiles "..." --class anticancer --rounds 2
    python -m chemrob.cli groups   --smiles "..."
    python -m chemrob.cli diseases
    python -m chemrob.cli info
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob import banner, kg, report
from chemrob.config import ARTIFACT_DIR, CLASS_KEYS, CLASS_BY_KEY, THERAPEUTIC_CLASS_KEYS
from chemrob.fgroups import describe_groups
from chemrob.standardize import parse_user_structure


def _read_structure(args) -> str | None:
    if args.smiles:
        return args.smiles
    if getattr(args, "file", None):
        return Path(args.file).read_text(encoding="utf-8")
    return None


def cmd_predict(args) -> int:
    from chemrob.predict import ChemRobPredictor

    structure = _read_structure(args)
    if not structure:
        print("Provide --smiles or --file", file=sys.stderr)
        return 2

    predictor = ChemRobPredictor(use_dmpnn=args.dmpnn)
    rep = predictor.predict(
        structure, disease=args.disease, explain=not args.no_explain,
        make_svg=bool(args.svg),
    )
    if args.json:
        print(json.dumps(rep, indent=2, default=float))
    else:
        print(report.render_prediction(rep))
    if args.svg and rep.get("ok") and rep.get("explanation", {}).get("svg"):
        Path(args.svg).write_text(rep["explanation"]["svg"], encoding="utf-8")
        print(f"Highlighted structure written to {args.svg}")
    return 0 if rep.get("ok") else 1


def cmd_optimize(args) -> int:
    from chemrob.optimize import optimize
    from chemrob.predict import ChemRobPredictor

    structure = _read_structure(args)
    if not structure:
        print("Provide --smiles or --file", file=sys.stderr)
        return 2

    predictor = ChemRobPredictor()
    class_key = args.activity_class
    if class_key not in THERAPEUTIC_CLASS_KEYS:
        print(f"Unknown class {class_key!r}. Available:", file=sys.stderr)
        for k in THERAPEUTIC_CLASS_KEYS:
            print(f"  {k:<22} {CLASS_BY_KEY[k].label}", file=sys.stderr)
        return 2

    rep = optimize(
        predictor, structure, class_key,
        n_suggestions=args.n, rounds=args.rounds, beam_width=args.beam,
        include_ring_swaps=not args.no_ring_swaps, max_sa=args.max_sa,
    )
    if args.json:
        print(json.dumps(rep, indent=2, default=float))
    else:
        print(report.render_optimization(rep))
    return 0 if rep.get("ok") else 1


def cmd_screen(args) -> int:
    from chemrob import batch
    from chemrob.predict import ChemRobPredictor

    src = Path(args.input)
    if not src.exists():
        print(f"No such file: {src}", file=sys.stderr)
        return 2

    structures = batch.read_structures(src)
    if not structures:
        print(f"{src} contained no structures.", file=sys.stderr)
        return 1
    print(f"Read {len(structures)} structures from {src.name}")

    predictor = ChemRobPredictor()
    classes = args.classes.split(",") if args.classes else None
    df = batch.screen(predictor, structures, classes=classes)

    if args.only_class:
        df = batch.rank_by(df, args.only_class, in_domain_only=args.in_domain_only,
                           top=args.top)
    elif args.in_domain_only and "ad_verdict" in df.columns:
        df = df[df["ad_verdict"] != "out_of_domain"]

    out = Path(args.out) if args.out else src.with_name(src.stem + "_chemrob.csv")
    df.to_csv(out, index=False)
    print()
    print(batch.summarize_screen(df, classes))
    print()
    print(f"Written to {out}")
    return 0


def cmd_groups(args) -> int:
    structure = _read_structure(args)
    parsed = parse_user_structure(structure or "")
    if not parsed.ok:
        print(f"Could not parse structure: {parsed.reason}", file=sys.stderr)
        return 1
    print(f"Standardized: {parsed.smiles}\n")
    rows = describe_groups(parsed.mol)
    print(f"{'kind':<18}{'group':<38}{'count':>6}  atoms")
    for g in rows:
        print(f"{g['kind']:<18}{g['name'][:37]:<38}{g['count']:>6}  {g['atoms']}")
    return 0


def cmd_diseases(args) -> int:
    if args.query:
        hits = kg.search_disease(args.query)
        if not hits:
            print(f"No indication in the knowledge graph matches {args.query!r}.")
            return 1
        for h in hits:
            d = h.to_dict()
            print(f"{d['score']:>5.2f}  {d['name']}  [{d['key']}]")
            print(f"        classes : {', '.join(d['primary_classes'])}")
            print(f"        targets : {', '.join(t['name'] for t in d['targets'])}")
        return 0
    for d in kg.list_diseases():
        print(f"{d['key']:<28}{d['name'][:38]:<40}{','.join(d['classes'])}")
    return 0


def cmd_info(args) -> int:
    meta_path = ARTIFACT_DIR / "metadata.json"
    metrics_path = ARTIFACT_DIR / "metrics.json"
    if not meta_path.exists():
        print("No trained bundle found. Run scripts/build_dataset.py then scripts/train.py.")
        return 1
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    print(json.dumps(meta, indent=2))
    if metrics_path.exists() and args.metrics:
        metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
        for head in ("activity", "target", "scaffold"):
            if head in metrics and metrics[head].get("summary"):
                print(f"\n[{head}] {json.dumps(metrics[head]['summary'])}")
                if args.per_task and metrics[head].get("per_task"):
                    print(f"{'task':<24}{'n':>7}{'pos':>7}{'AUROC':>8}{'AUPRC':>8}{'F1':>7}")
                    for r in metrics[head]["per_task"]:
                        if r.get("auroc") is None:
                            continue
                        print(f"{r['task'][:23]:<24}{r['n']:>7}{r['n_positive']:>7}"
                              f"{r['auroc']:>8.3f}{r['auprc']:>8.3f}{r['f1']:>7.3f}")
    return 0


def cmd_welcome(args) -> int:
    banner.print_banner()
    return 0


def cmd_serve(args) -> int:
    import uvicorn

    banner.print_banner(
        subtitle=f"REST API starting on http://{args.host}:{args.port}",
        show_tips=False,
    )
    print(f"  Interactive docs:  http://{args.host}:{args.port}/docs")
    print("  Press Ctrl+C to stop.")
    print()

    uvicorn.run("chemrob.api:app", host=args.host, port=args.port, reload=False)
    return 0


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="chemrob", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="command")

    p = sub.add_parser("predict", help="predict pharmacological activity for a structure")
    p.add_argument("--smiles", help="SMILES, InChI, or molblock")
    p.add_argument("--file", help="read the structure from a file")
    p.add_argument("--disease", help="anchor the analysis to an indication")
    p.add_argument("--dmpnn", action="store_true", help="use the graph-network backend")
    p.add_argument("--no-explain", action="store_true")
    p.add_argument("--svg", help="write the highlighted structure to this SVG path")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_predict)

    p = sub.add_parser("optimize", help="suggest structural edits for a desired activity")
    p.add_argument("--smiles")
    p.add_argument("--file")
    p.add_argument("--class", dest="activity_class", required=True,
                   help="target activity class, e.g. anticancer")
    p.add_argument("-n", type=int, default=10, help="number of suggestions")
    p.add_argument("--rounds", type=int, default=1, help="iterative optimization rounds")
    p.add_argument("--beam", type=int, default=3, help="molecules carried into the next round")
    p.add_argument("--max-sa", type=float, default=6.0, help="synthetic-accessibility cutoff")
    p.add_argument("--no-ring-swaps", action="store_true")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_optimize)

    p = sub.add_parser("screen", help="score a whole file of structures")
    p.add_argument("input", help="CSV, TSV, SMI or TXT file of structures")
    p.add_argument("--out", help="output CSV (default: <input>_chemrob.csv)")
    p.add_argument("--classes", help="comma-separated subset of activity classes")
    p.add_argument("--only-class", help="rank the output by this one class")
    p.add_argument("--in-domain-only", action="store_true",
                   help="drop compounds outside the applicability domain")
    p.add_argument("--top", type=int, help="keep only the top N rows")
    p.set_defaults(func=cmd_screen)

    p = sub.add_parser("groups", help="functional-group inventory for a structure")
    p.add_argument("--smiles")
    p.add_argument("--file")
    p.set_defaults(func=cmd_groups)

    p = sub.add_parser("diseases", help="list or search the disease-target graph")
    p.add_argument("query", nargs="?")
    p.set_defaults(func=cmd_diseases)

    p = sub.add_parser("info", help="show the trained bundle's provenance and metrics")
    p.add_argument("--metrics", action="store_true")
    p.add_argument("--per-task", action="store_true")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("welcome", help="show the welcome banner")
    p.set_defaults(func=cmd_welcome)

    p = sub.add_parser("serve", help="run the REST API")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8000)
    p.set_defaults(func=cmd_serve)

    return ap


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    argv = sys.argv[1:] if argv is None else argv
    # Running the CLI bare is a person finding their feet, not an error.
    # Greet them and show what to try instead of printing usage and exiting 2.
    if not argv:
        banner.print_banner()
        return 0
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(levelname)s %(message)s",
    )
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
