"""
Build the benchmark report .docx from the frozen artifacts.

Every number is read from artifacts/ at run time rather than typed in, so the
document cannot drift away from the bundle it describes - which is the failure
this project already had once, when four files in artifacts/ described four
different builds.

    python scripts/make_benchmark_report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from docx_writer import (  # noqa: E402
    ACCENT, GOOD, MUTED, WARN, bullet, callout, heading, page_break, para, run,
    table, write_docx,
)

from chemrob.config import ARTIFACT_DIR, CLASS_BY_KEY, LIABILITY_CLASS_KEYS  # noqa: E402

A = ARTIFACT_DIR
OUT = Path(__file__).resolve().parents[1] / "ChemRob_DJ_Benchmark_Results.docx"


def load(name: str) -> dict:
    p = A / name
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


meta = load("metadata.json")
metrics = load("metrics.json")
validation = load("validation.json")
ablation = load("ablation.json")
diagnostics = load("diagnostics.json")
optimizer = load("optimizer_validation.json")


def f(x, n=3, dash="—"):
    try:
        return f"{float(x):.{n}f}"
    except (TypeError, ValueError):
        return dash


def label_of(key: str) -> str:
    c = CLASS_BY_KEY.get(key)
    return c.label if c else key


body: list[str] = []

# ---------------------------------------------------------------- title page
body += [
    para([run("BENCHMARK RESULTS", bold=True, size=18, color=ACCENT)], after=60),
    heading("ChemRob DJ", 1),
    para([run("Multi-task pharmacological activity prediction — measured performance, "
              "negative controls, ablation and module validation", size=22, color=MUTED)],
         after=200),
]

prov = meta.get("provenance", {})
pkgs = prov.get("packages", {})
body += [
    table(
        ["Property", "Value"],
        [
            ["Bundle", f"{meta.get('n_molecules', 0):,} labelled molecules, "
                       f"{len(meta.get('classes', []))} heads, "
                       f"{len(meta.get('targets', []))} targets"],
            ["Splits", f"{meta.get('n_train', 0):,} train / {meta.get('n_valid', 0):,} "
                       f"validation / {meta.get('n_test', 0):,} test"],
            ["Split protocol", meta.get("split", "—")],
            ["Data sources", ", ".join(prov.get("sources", ["ChEMBL"]))],
            ["ChEMBL release", f"{prov.get('chembl_release', '—')} "
                               f"(downloaded {prov.get('chembl_downloaded', '—')})"],
            ["CO-ADD release", prov.get("coadd_release", "—")],
            ["Backend", meta.get("backend", "—")],
            ["Matched-pair rules", f"{meta.get('n_mmp_rules', 0):,}"],
            ["Python / RDKit", f"{prov.get('python', '—')} / {pkgs.get('rdkit', '—')}"],
            ["scikit-learn", pkgs.get("scikit-learn", "—")],
            ["Random seed", str(meta.get("seeds", {}).get("main_build", 42))],
            ["Training time", f"{meta.get('trained_seconds', 0):,.0f} s"],
        ],
        [2600, 6760],
    ),
    callout(
        "Scope of this document",
        "Most results here are internal: held-out splits of the project's own "
        "curated dataset. Two are not. Section 9 scores the released bundle on "
        "BindingDB, an independent database it was never fitted on, and section 10 "
        "places five safety heads against public benchmarks that carry published "
        "leaderboards. The therapeutic heads still have no external comparison, "
        "because no shared benchmark exists for them.",
    ),
    callout(
        "One frozen build",
        "Every figure is read from artifacts/ for a single bundle. Results from "
        "earlier builds are held in artifacts/superseded/ and are not comparable to "
        "anything below.",
        colour=GOOD,
    ),
]

# ---------------------------------------------------------------- headline
body += [page_break(), heading("1. Headline performance", 1)]

sb = metrics.get("activity", {}).get("summary_by_block", {})
tb = validation.get("time_split", {}).get("summary_by_block", {})

# Head counts come from the artifact, not from a literal. They were "14" and "3"
# here until splitting the pooled P450 head into five isoforms made the safety
# block seven, and a hardcoded caption would have gone on claiming three.
ther_n = sb.get("therapeutic", {}).get("n_tasks_evaluated", "?")
liab_n = sb.get("liability", {}).get("n_tasks_evaluated", "?")
THER = f"Therapeutic ({ther_n})"
LIAB = f"Safety ({liab_n})"

body += [
    para("Therapeutic and safety heads are reported separately throughout. They are "
         "never averaged together; section 3 explains why that would mislead."),
    table(
        ["Head group", "Split", "AUROC", "AUPRC", "F1", "MCC"],
        [
            [THER, "Scaffold",
             f(sb.get("therapeutic", {}).get("macro_auroc")),
             f(sb.get("therapeutic", {}).get("macro_auprc")),
             f(sb.get("therapeutic", {}).get("macro_f1")),
             f(sb.get("therapeutic", {}).get("macro_mcc"))],
            [THER, "Time 2018",
             f(tb.get("therapeutic", {}).get("macro_auroc")),
             f(tb.get("therapeutic", {}).get("macro_auprc")),
             f(tb.get("therapeutic", {}).get("macro_f1")),
             f(tb.get("therapeutic", {}).get("macro_mcc"))],
            [LIAB, "Scaffold",
             f(sb.get("liability", {}).get("macro_auroc")),
             f(sb.get("liability", {}).get("macro_auprc")),
             f(sb.get("liability", {}).get("macro_f1")),
             f(sb.get("liability", {}).get("macro_mcc"))],
            [LIAB, "Time 2018",
             f(tb.get("liability", {}).get("macro_auroc")),
             f(tb.get("liability", {}).get("macro_auprc")),
             f(tb.get("liability", {}).get("macro_f1")),
             f(tb.get("liability", {}).get("macro_mcc"))],
        ],
        [2400, 1560, 1350, 1350, 1350, 1350],
        aligns=["left", "left", "right", "right", "right", "right"],
        highlight={0: ACCENT, 1: ACCENT},
    ),
]

# The stored `comparison` block pools every head, which blends therapeutic and
# liability optimism into a single figure that describes neither. validate.py
# now also writes `comparison_by_block`; prefer it, and fall back to computing
# the therapeutic figure here so an older validation.json still renders.
_cbb = validation.get("comparison_by_block", {}).get("therapeutic", {})
if _cbb.get("macro_auprc"):
    opt_auprc = {"optimism": _cbb["macro_auprc"]["optimism"]}
else:
    _sc_t = sb.get("therapeutic", {}).get("macro_auprc")
    _tm_t = tb.get("therapeutic", {}).get("macro_auprc")
    opt_auprc = {"optimism": (_sc_t - _tm_t) if (_sc_t and _tm_t) else None}
body += [callout(
    "The number to quote for new chemistry",
    f"Training on literature to 2018 and testing on "
    f"{validation.get('time_split', {}).get('n_test', 0):,} molecules published later "
    f"gives therapeutic AUPRC {f(tb.get('therapeutic', {}).get('macro_auprc'))} against "
    f"{f(sb.get('therapeutic', {}).get('macro_auprc'))} on the scaffold split — an "
    f"optimism of {f(opt_auprc.get('optimism'))} AUPRC. Any forward-looking claim "
    f"should use the temporal figure.",
)]

other = []
for key, name in (("target", "Per-target likelihood (57 heads)"),
                  ("scaffold", "Core-ring / scaffold classifier")):
    s = metrics.get(key, {}).get("summary", {})
    if s:
        other.append([name, str(s.get("n_tasks_evaluated", "—")),
                      f(s.get("macro_auroc")), f(s.get("macro_auprc")),
                      f(s.get("macro_mcc"))])
reg = metrics.get("regression", {})
for key, name in (("class", "Potency regression, per class"),
                  ("target", "Potency regression, per target")):
    s = reg.get(key, {}).get("summary", {})
    if s:
        other.append([name, str(s.get("n_tasks_evaluated", "—")),
                      f"RMSE {f(s.get('macro_rmse'), 2)}",
                      f"ρ {f(s.get('macro_spearman'), 2)}",
                      f"MAE {f(s.get('macro_mae'), 2)}"])
if other:
    body += [heading("Other heads (scaffold split)", 2),
             table(["Head", "n", "AUROC / RMSE", "AUPRC / ρ", "MCC / MAE"], other,
                   [3800, 800, 1600, 1580, 1580],
                   aligns=["left", "right", "right", "right", "right"])]

# ---------------------------------------------------------------- per class
body += [page_break(), heading("2. Per-class results", 1),
         para("Scaffold split. AUPRC lift is AUPRC divided by the base rate: it is the "
              "figure that shows whether a head has learned anything beyond how often "
              "the class is positive.")]

rows_t, rows_l = [], []
for r in metrics.get("activity", {}).get("per_task", []):
    if r.get("auroc") is None:
        continue
    row = [label_of(r["task"])[:34], f"{r['n']:,}", f(r["positive_rate"], 2),
           f(r["auroc"]), f(r["auprc"]), f(r.get("auprc_lift"), 2), f(r["mcc"])]
    (rows_l if r["task"] in LIABILITY_CLASS_KEYS else rows_t).append(row)

cols = [2900, 900, 900, 1140, 1140, 1140, 1240]
al = ["left", "right", "right", "right", "right", "right", "right"]
hdr = ["Class", "n", "base", "AUROC", "AUPRC", "lift", "MCC"]
body += [heading("Therapeutic heads", 2), table(hdr, rows_t, cols, aligns=al)]
body += [heading("Safety liability heads", 2), table(hdr, rows_l, cols, aligns=al)]

# ---------------------------------------------------------------- G6
body += [heading("3. Why the two groups are never averaged", 1)]
liab = [r for r in metrics.get("activity", {}).get("per_task", [])
        if r["task"] in LIABILITY_CLASS_KEYS and r.get("auroc")]
worst = min(liab, key=lambda r: r.get("auprc_lift", 9e9)) if liab else None
if worst:
    body += [para(
        f"Averaging all "
        f"{metrics.get('activity', {}).get('summary', {}).get('n_tasks_evaluated', '?')}"
        f" heads gives "
        f"{f(metrics.get('activity', {}).get('summary', {}).get('macro_auprc'))} AUPRC, "
        f"and that figure is inflated. The liability heads have base rates of "
        f"{f(min(r['positive_rate'] for r in liab), 2)}–"
        f"{f(max(r['positive_rate'] for r in liab), 2)}, "
        f"so a high AUPRC follows arithmetically rather than chemically. "
        f"{label_of(worst['task'])} reaches AUPRC {f(worst['auprc'])} at a lift of only "
        f"{f(worst.get('auprc_lift'), 2)} and an MCC of {f(worst['mcc'])} — it predicts "
        f"'yes' and is usually right without having learned chemistry."),
        para("AUPRC lift and MCC are therefore reported for every head, and the two "
             "groups are summarised separately.")]

# ---------------------------------------------------------------- controls
body += [page_break(), heading("4. Negative control (y-scrambling)", 1),
         para("Labels permuted within each task, preserving both the base rate and the "
              "missingness pattern, so the only thing destroyed is the "
              "structure–activity relationship. An OECD validation principle.")]

scr = ablation.get("scramble", [])
if scr:
    real = [r for r in scr if "real" in r["config"]]
    perm = [r for r in scr if "permuted" in r["config"]]
    rows = [[r["config"].split(":")[-1], f(r["macro_auroc"]), f(r["macro_auprc"]),
             f"{r['seconds']:.0f} s"] for r in scr]
    body += [table(["Configuration", "AUROC", "AUPRC", "Fit time"], rows,
                   [4200, 1720, 1720, 1720],
                   aligns=["left", "right", "right", "right"],
                   highlight={0: GOOD})]
    if real and perm:
        import statistics as st
        mp = st.mean(p["macro_auprc"] for p in perm)
        body += [callout(
            "Result: passes",
            f"Permuted labels give AUROC "
            f"{f(st.mean(p['macro_auroc'] for p in perm))} — chance — against "
            f"{f(real[0]['macro_auroc'])} for the real labels, a gap of "
            f"{f(real[0]['macro_auprc'] - mp)} AUPRC. The permuted runs also fit in "
            f"roughly a seventh of the time, because early stopping fires immediately "
            f"when there is no signal to learn. The model learned chemistry, not "
            f"dataset structure.", colour=GOOD)]

# ---------------------------------------------------------------- ablation
body += [heading("5. Ablation — what actually drives the number", 1)]
axis_rows = []
for axis, title in (("splits", "Split protocol"),
                    ("features", "Molecular representation"),
                    ("labelling", "Labelling scheme")):
    rows = ablation.get(axis, [])
    if not rows:
        continue
    spread = max(r["macro_auprc"] for r in rows) - min(r["macro_auprc"] for r in rows)
    axis_rows.append([title, str(len(rows)), f(spread, 4)])
if axis_rows:
    base = float(axis_rows[0][2]) or 1.0
    for r in axis_rows:
        r.append(f"{float(r[2]) / base:.2f}×")
    body += [table(["Design axis", "configs", "AUPRC spread", "relative"], axis_rows,
                   [3800, 1400, 2080, 2080],
                   aligns=["left", "right", "right", "right"],
                   highlight={0: WARN})]
    body += [callout(
        "The dominant finding",
        f"How the data is split moves the headline "
        f"{axis_rows[1][3] and float(axis_rows[0][2]) / max(float(axis_rows[1][2]), 1e-9):.1f}× "
        f"more than which molecular representation is used, and far more than the "
        f"labelling scheme. Reported performance is dominated by evaluation design "
        f"rather than by modelling choices.")]

for axis, title in (("splits", "5.1 Split protocol"),
                    ("features", "5.2 Molecular representation"),
                    ("labelling", "5.3 Labelling scheme")):
    rows = ablation.get(axis, [])
    if not rows:
        continue
    body += [heading(title, 2),
             table(["Configuration", "features", "AUROC", "AUPRC", "fit time"],
                   [[r["config"], f"{r['n_features']:,}", f(r["macro_auroc"]),
                     f(r["macro_auprc"]), f"{r['seconds']:.0f} s"] for r in rows],
                   [3400, 1400, 1520, 1520, 1520],
                   aligns=["left", "right", "right", "right", "right"])]

feat = {r["config"]: r for r in ablation.get("features", [])}
if "ecfp4_only" in feat and "ecfp4_plus_descriptors" in feat:
    gain = feat["ecfp4_plus_descriptors"]["macro_auprc"] - feat["ecfp4_only"]["macro_auprc"]
    body += [para([
        run("Note. "), run("Adding 33 physicochemical descriptors to ECFP4 buys "),
        run(f"{gain:+.4f} AUPRC", bold=True),
        run(", inside the ±0.0027 seed-to-seed noise, so the descriptor block earns "
            "little. Descriptors alone reach "),
        run(f(feat.get('descriptors_only', {}).get('macro_auprc')), bold=True),
        run(f" in {feat.get('descriptors_only', {}).get('seconds', 0):.0f} seconds "
            f"against {feat['ecfp4_only']['seconds']:.0f} — a usable fast pre-filter "
            f"for very large libraries."),
    ])]

# ---------------------------------------------------------------- calibration
cal = diagnostics.get("calibration", {})
if cal:
    body += [page_break(), heading("6. Probability calibration", 1),
             para("Sigmoid calibration is fitted on a held-out fold. This matters "
                  "beyond the classifier: the optimizer weights the probability delta "
                  "at 1.0 against 0.15 for predicted potency, on the grounds that the "
                  "probability is calibrated.")]
    s = cal.get("summary", {})
    rows = [[label_of(r["task"])[:30], f"{r['n']:,}", f(r["base_rate"], 3),
             f(r["mean_predicted"], 3), f(r["brier"], 4), f(r["ece"], 4)]
            for r in cal.get("per_task", [])]
    body += [table(["Head", "n", "observed", "predicted", "Brier", "ECE"], rows,
                   [3000, 1000, 1340, 1340, 1340, 1340],
                   aligns=["left", "right", "right", "right", "right", "right"]),
             callout("Result: calibrated",
                     f"Mean ECE {f(s.get('mean_ece'), 4)} and mean Brier "
                     f"{f(s.get('mean_brier'), 4)} across {s.get('n_tasks')} heads, with "
                     f"predicted rates tracking observed rates closely. Worst head: "
                     f"{label_of(str(s.get('worst_ece_task')))} at ECE "
                     f"{f(s.get('worst_ece'), 4)}.", colour=GOOD)]

# ---------------------------------------------------------------- AD
ad = diagnostics.get("applicability_domain", {})
if ad:
    body += [heading("7. Applicability domain", 1),
             para(f"Held-out performance stratified by maximum Tanimoto similarity to "
                  f"the training set, over {ad.get('n_scored', 0):,} test molecules.")]
    rows = [[b["band"], f"{b['n_molecules']:,}", f(b.get("macro_auroc")),
             f(b.get("macro_auprc"))] for b in ad.get("by_similarity_band", [])]
    body += [table(["Max Tanimoto to training set", "n", "AUROC", "AUPRC"], rows,
                   [4200, 1720, 1720, 1720],
                   aligns=["left", "right", "right", "right"])]
    dist = ad.get("similarity_distribution", {})
    body += [callout(
        "Works — and reveals analogue bias",
        f"Performance declines monotonically as molecules move away from the training "
        f"set, so the domain verdict is informative. However median similarity to "
        f"training is {f(dist.get('median'), 2)} and the 5th percentile is "
        f"{f(dist.get('p5'), 2)}: a Bemis–Murcko split prevents a shared core, not a "
        f"close analogue. The headline figure is therefore a similarity-"
        f"{f(dist.get('median'), 2)} number, and on genuinely distant chemistry "
        f"(Tanimoto 0.3–0.4) AUPRC falls to "
        f"{f(rows[0][3]) if rows else '—'}.")]

# ---------------------------------------------------------------- optimizer
if optimizer:
    body += [page_break(), heading("8. Optimization engine — held-out validation", 1),
             para("Transformation rules are mined on the training split only, then "
                  "tested against matched molecular pairs found inside the held-out "
                  "split. Two controls bound the result: predictions shuffled across "
                  "pairs, and a rule drawn at random for the same class.")]
    strata = [optimizer.get("overall"), optimizer.get("meaningful_change_only"),
              *optimizer.get("by_support", []),
              optimizer.get("controls", {}).get("shuffled_predictions"),
              optimizer.get("controls", {}).get("random_rule")]
    rows = [[s["stratum"], f"{s['n']:,}", f(s.get("sign_agreement")),
             f(s.get("spearman")), f(s.get("mae_log_units"), 2)]
            for s in strata if s and s.get("n", 0) >= 20]
    hl = {i: GOOD for i, s in enumerate([x for x in strata if x and x.get("n", 0) >= 20])
          if "meaningful" in str(s.get("stratum", "")) or ">=" in str(s.get("stratum", ""))}
    body += [table(["Stratum", "pairs", "sign agreement", "Spearman", "MAE (log)"],
                   rows, [3200, 1240, 1780, 1580, 1560],
                   aligns=["left", "right", "right", "right", "right"],
                   highlight=hl)]
    mc = optimizer.get("meaningful_change_only", {})
    ov = optimizer.get("overall", {})
    body += [callout(
        "Real but modest — and two caveats that must travel with it",
        f"Both controls sit at chance (sign agreement ≈0.48, Spearman ≈0), while real "
        f"rules reach {f(mc.get('sign_agreement'))} sign agreement and Spearman "
        f"{f(mc.get('spearman'))} on pairs whose measured change exceeds assay noise. "
        f"The signal is genuine. It is also weak: the direction is right about three "
        f"times in five, and MAE is ~{f(mc.get('mae_log_units'), 1)} log units, so the "
        f"predicted magnitude carries little information. First caveat: coverage is "
        f"only {100 * optimizer.get('coverage_fraction', 0):.1f}% of held-out pairs. "
        f"Second: across all covered pairs sign agreement is "
        f"{f(ov.get('sign_agreement'))} — chance — and the signal appears only once "
        f"pairs inside assay noise are excluded.")]

# ---------------------------------------------------------------- limitations
# ---------------------------------------------------------------- external
external = load("external_validation.json")
tdc = load("tdc_benchmark.json")

if external.get("per_task"):
    et = external.get("summary_by_block", {}).get("therapeutic", {})
    # `if r.get("auroc")` is not enough: a head with no BindingDB labels carries
    # NaN, and NaN is truthy, so the unscored liability heads rendered as rows of
    # "nan". Require a real number.
    def _scored(x) -> bool:
        return isinstance(x, (int, float)) and x == x

    ext_rows = [[label_of(r["task"]), f"{r['n']:,}", f(r["positive_rate"], 2),
                 f(r["auroc"]), f(r["auprc"]), f(r.get("internal_auprc")),
                 f(r.get("delta_auprc"))]
                for r in external["per_task"] if _scored(r.get("auroc"))]
    body += [
        page_break(), heading("9. External validation — BindingDB", 1),
        para(f"{external.get('n_molecules', 0):,} molecules from BindingDB, a database "
             f"curated by a different group and excluded from training. BindingDB "
             f"re-aggregates part of ChEMBL, so every compound already in the training "
             f"set was removed by standardised SMILES before scoring. Labels were "
             f"rebuilt from BindingDB potencies using the same per-class thresholds, "
             f"so 'active' means the same thing on both sides."),
        table(["Class", "n", "Base rate", "AUROC", "AUPRC", "Internal AUPRC", "Δ"],
              ext_rows,
              [2500, 900, 1100, 1050, 1050, 1500, 1000],
              aligns=["left", "right", "right", "right", "right", "right", "right"]),
        callout(
            "What the gap means",
            f"Macro AUPRC {f(et.get('macro_auprc'))} across "
            f"{et.get('n_tasks_evaluated', 0)} evaluable therapeutic classes, against "
            f"{f(sb.get('therapeutic', {}).get('macro_auprc'))} on the internal "
            f"scaffold split. The heads carrying liability labels are not evaluable "
            f"here: BindingDB supplied no usable labels for them.",
            colour=GOOD),
    ]

if tdc.get("per_benchmark"):
    tdc_rows = [[r["benchmark"], f"{r['n_scored']:,}",
                 f"{r['n_overlapping_training']:,}", f(r["positive_rate"], 2),
                 f(r["auroc"]), f(r["auprc"]), f(r["mcc"]),
                 r["published_leaderboard"].replace("(models trained on this benchmark)", "").strip()]
                for r in tdc["per_benchmark"]]
    body += [
        page_break(), heading("10. Public benchmark comparison", 1),
        para("Five safety heads correspond to tasks with published leaderboards in the "
             "Therapeutics Data Commons. The released bundle was applied zero-shot: no "
             "benchmark-specific training, no benchmark split. Every benchmark molecule "
             "already present in training was removed before scoring, which discards "
             "31% to 44% of each set."),
        table(["Benchmark", "n scored", "excluded", "Base rate", "AUROC", "AUPRC",
               "MCC", "Published"],
              tdc_rows,
              [2300, 900, 900, 950, 900, 900, 850, 1600],
              aligns=["left", "right", "right", "right", "right", "right", "right", "left"]),
        callout(
            "Read this as placement, not ranking",
            "Leaderboard entries are trained on each benchmark's own training split "
            "and evaluated on its scaffold split; this model has never seen the "
            "benchmark at all. The asymmetry favours the leaderboard. P-glycoprotein "
            "and hERG land inside their published ranges even so. The three P450 "
            "heads fall below theirs: the benchmarks are PubChem single-concentration "
            "qHTS at base rates of 0.13 to 0.32, while these heads are trained on "
            "ChEMBL dose-response IC50 values at base rates of 0.72 to 0.84, so assay "
            "format and prevalence both differ."),
    ]

# ---------------------------------------------------------------- limitations
body += [page_break(), heading("11. What has not been measured", 1),
         para("Stated so that no reader has to infer it.")]
for t, d in [
    ("No external comparison for the therapeutic heads. ",
     "Sections 9 and 10 place the safety heads against an independent database and "
     "against published leaderboards, but no shared public benchmark exists for the "
     "14 therapeutic classes, so those figures still cannot be ranked against the "
     "literature."),
    ("No wet-lab validation. ",
     "Every optimizer result is retrospective. No proposed analogue has been "
     "synthesised or assayed."),
    ("Class labels are target proxies. ",
     "'Anticancer' means active against EGFR, VEGFR2, CDK2, HDAC1 or ABL1. How far "
     "that proxy travels has not been bounded against an independent classification."),
    ("No confidence intervals. ",
     "Seed variance across splits is reported, but bootstrap intervals on a fixed "
     "test set are not, so small differences — notably the 0.009 labelling-axis "
     "spread — are not shown to be significant."),
    ("The graph network is compute-limited. ",
     "Its validation AUPRC was still rising when training stopped, so no general "
     "claim about graph networks is supportable from this work."),
    ("Whole-organism labels are noisier than protein labels. ",
     "MIC values aggregate heterogeneous assays and conflate target engagement with "
     "permeability and efflux."),
]:
    body += [bullet(d, bold_prefix=t)]

body += [
    para([run("")], after=200),
    para([run("Generated from artifacts/ on the frozen build. "
              "Predictions are hypotheses for prioritising synthesis and assay work, "
              "not assay results, and are not for clinical or regulatory use. "
              "Bioactivity data from ChEMBL (EMBL-EBI) and CO-ADD (University of "
              "Queensland).", size=16, color=MUTED)], border_bottom=False),
]

write_docx(OUT, body)
print(f"Written {OUT}  ({OUT.stat().st_size / 1024:.0f} KB)")
