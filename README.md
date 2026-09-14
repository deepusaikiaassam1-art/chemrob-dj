# ChemRob DJ
<img width="1905" height="523" alt="Screenshot 2026-09-03 220339" src="https://github.com/user-attachments/assets/3a053e52-cefc-4f41-a2a1-9ed7b914a357" />


A working implementation of the ChemRob DJ concept: predict the
pharmacological activity of a chemical structure, explain which parts of the
molecule drove the prediction, say whether the answer can be trusted, and
suggest specific structural edits to push the molecule toward a desired activity
for a chosen disease.

Everything is trained on public ChEMBL bioactivity data downloaded by the
pipeline itself. No pre-baked weights, no synthetic data.

---

## What it does

Give it a structure and it returns:

| Output | Module in the deck |
|---|---|
| Functional groups present, and which activities each is statistically tied to | 1 — functional-group deep search |
| Probability of activity across 14 pharmacological classes | 1 — whole-molecule head |
| What the Bemis–Murcko core ring alone predicts | 2 — core-ring classifier |
| Likelihood of hitting each of 44 individual ChEMBL targets | target head |
| Whether the molecule is inside the model's training space | applicability domain |
| Which atoms and functional groups drove the call | 4 — explainability |
| Ranked, precedented structural edits with predicted gain | 3 — optimization engine |
| All of the above re-weighted for a chosen indication | 6 — disease knowledge graph |

### Activity classes

`anticancer`, `anti_inflammatory`, `antidiabetic`, `antihypertensive`,
`antiviral_hiv`, `antidepressant_cns`, `analgesic_opioid`, `antihistaminic`,
`antithrombotic`, `anti_alzheimer`, `antiparkinson`, `antibacterial`,
`antifungal`, `antiprotozoal`

Each class is defined by a set of verified ChEMBL targets (protein targets for
the mechanistic classes, whole-organism targets for the anti-infectives). The
full mapping is in `chemrob/config.py` — it is the file to edit to add a
class or swap a target.

---

## Measured results

**One frozen build.** Every number below is read from `artifacts/` for the same
bundle: ChEMBL_37 plus CO-ADD, 1,030,878 activity records → 255,804 labelled
molecules, 21 heads (14 therapeutic + 7 safety) over 57 targets. Earlier builds
live in `artifacts/superseded/` and are not comparable to these.

Bemis–Murcko scaffold split: 204,643 train / 25,580 validation / 25,581 test.

### Headline

Therapeutic and safety heads are reported separately and never averaged
together — see "Why not one macro number" below.

| | scaffold split | time split (2018) |
|---|---|---|
| **Therapeutic (14 heads)** AUROC | 0.929 | 0.828 |
| **Therapeutic (14 heads)** AUPRC | **0.904** | **0.709** |
| Safety (7 liabilities) AUROC | 0.859 | 0.719 |
| Safety (7 liabilities) AUPRC | 0.957 | 0.797 |

**Quote 0.709 for anything forward-looking.** Training on literature to 2018 and
testing on 53,993 molecules published later costs **0.195 AUPRC** on the
therapeutic heads (0.904 → 0.709). The liability heads lose **0.159**
(0.957 → 0.797), and the blended all-21 figure of 0.183 describes neither block —
which is why the two are reported separately. That gap is the honest estimate of
what the scaffold split flatters.

An earlier build pooled all five cytochrome P450 isoforms into one head and the
safety block appeared to lose only 0.051, suggesting those endpoints were
unusually robust to distribution shift. Splitting the pooled head into per-isoform
heads removed that impression: at 0.159 the safety block degrades over time almost
as much as the therapeutic block. The robustness was an artifact of pooling
heterogeneous endpoints.

Other heads, scaffold split: per-target likelihood (57 heads) AUROC 0.916 /
AUPRC 0.875; core-ring classifier AUROC 0.774 over the same 21 tasks (0.132 below
the whole-molecule head — a direct measure of what the substituents contribute);
potency regression RMSE 0.85 log units, Spearman 0.68.

Split-to-split variation, three further scaffold splits (seeds 1–3):
macro AUROC 0.9058 ± 0.0012, macro AUPRC 0.9213 ± 0.0008.

### Why not one macro number

Averaging all 21 heads gives 0.922 AUPRC and it is misleading. The liability
heads have base rates of 0.72–0.87, so they score well arithmetically:
`pgp_efflux` reaches 0.975 AUPRC — second highest of any head — at a **lift of
1.12**, the lowest of all 21, and an MCC of 0.334. Most of that AUPRC is
prevalence, not discrimination. AUPRC lift over base rate and MCC are reported
alongside every head for exactly this reason.

### Negative control (y-scrambling)

Labels permuted within each task, preserving base rate and missingness:

| | AUROC | AUPRC |
|---|---|---|
| real labels | 0.929 | 0.904 |
| permuted × 3 | 0.505 | 0.459 ± 0.002 |

Chance. The model learned structure–activity relationships, not dataset
structure.

### Ablation — evaluation design dominates

| axis | range tested | AUPRC spread |
|---|---|---|
| **Split protocol** | random / scaffold / time | **0.239** |
| Molecular representation | descriptors / ECFP4 / both | 0.066 |
| Estimator | logistic regression / gradient boosting / random forest | 0.050 |
| Labelling scheme | two-state forced / three-state masked | 0.009 |

How the data is split moves the headline **3.6× more than the representation**,
4.7× more than the estimator, and at least 25× more than the labelling — a lower
bound, because the 0.009 labelling spread is not separable from split noise.
Random split reports 0.948 for a model that achieves 0.709 prospectively.

One caveat on reading the table: changing the split changes the *test set*, so
that row measures a change in the difficulty of the evaluation, while the other
three are measured on one fixed test set. The comparison is between how much each
decision moves the number a paper would report.

A random forest scores 0.9079 against 0.9042 for the gradient boosting model
shipped here, in 57 seconds against 315. The difference is inside the seed noise,
so these data do not separate them; the estimator is carried forward on
calibration behaviour, not on a measured win.

The descriptor block earns little: ECFP4 alone gives 0.9024, adding 33
descriptors gives 0.9042 (+0.0018, inside seed noise). Descriptors *alone*
reach 0.839 in 18 seconds versus 309 — a usable fast pre-filter.

### Calibration

Mean ECE **0.025**, mean Brier 0.095 across 21 heads; predicted rates track
observed rates closely (anticancer: 0.483 predicted vs 0.487 observed). This
matters because the optimizer weights the probability delta at 1.0 against 0.15
for predicted potency, on the grounds that it is calibrated.

### Applicability domain

Held-out performance stratified by maximum Tanimoto to the training set
(6,000 test molecules):

| max Tanimoto | n | AUROC | AUPRC | MCC |
|---|---|---|---|---|
| 0.3–0.4 | 333 | 0.761 | 0.718 | 0.314 |
| 0.4–0.5 | 660 | 0.782 | 0.714 | 0.324 |
| 0.5–0.6 | 839 | 0.868 | 0.812 | 0.493 |
| 0.6–0.8 | 2,802 | 0.921 | 0.937 | 0.648 |
| 0.8–1.0 | 1,316 | 0.943 | 0.967 | 0.748 |

AUROC and MCC both rise monotonically. AUPRC dips slightly between the first two
bands, then rises. The head count with enough labels to score varies from 18 in
the lowest band to 21 in the highest, so the macros are not over identical task
sets.

So the domain verdict is informative rather than decorative. **But median
similarity to training is 0.69, fifth percentile 0.38** — a Bemis–Murcko split
stops a *core* being shared, not a close analogue. The headline 0.904 is a
similarity-0.69 number; in the most distant band measured it is 0.718.

### Optimizer hold-out

Rules mined on training data only, tested against held-out matched pairs:

| stratum | n | sign agreement | Spearman | MAE (log) |
|---|---|---|---|---|
| meaningful change (abs Δ ≥ 0.3) | 943 | **0.588** | **0.227** | 0.80 |
| high support (≥25 pairs) | 184 | 0.625 | 0.387 | 0.63 |
| *shuffled control* | 943 | 0.486 | −0.045 | 0.92 |
| *random-rule control* | 943 | 0.479 | 0.006 | 0.93 |

Real but modest. Both controls sit at chance, so the signal is genuine
(p ≈ 1e-12); 0.588 sign agreement means the direction is right about three times
in five. Two honest caveats: **coverage is only 6.8%** of held-out pairs, and
across *all* covered pairs sign agreement is 0.510 — the signal appears only
once pairs inside assay noise are excluded.


### External validation — BindingDB

An independent database, curated by a different group, that the released bundle
was never trained on. Every compound already present in training was removed by
standardised SMILES before scoring.

| | value |
|---|---|
| molecules scored | 6,239 |
| therapeutic classes with enough external labels | 10 of 14 |
| macro AUROC / AUPRC | 0.901 / **0.878** |
| mean per-class change vs internal scaffold split | **−0.050** AUPRC |

Nine of ten classes fall. The tenth rises 0.052, but its external base rate is
0.72 at a lift of 1.36, so that is prevalence rather than better generalisation.
Four therapeutic classes and all seven safety heads have no evaluable external
labels, and two of the four are the weakest internal heads — so this validates
the covered subset, not the whole model.

### Placement against public benchmarks

Five safety heads scored **zero-shot** against Therapeutics Data Commons sets:
no benchmark-specific training, no benchmark split, and every benchmark molecule
already in training removed first (31–44% of each set).

| benchmark | n scored | AUROC | AUPRC | published range |
|---|---|---|---|---|
| P-glycoprotein (Broccatelli) | 841 | **0.911** | 0.932 | AUROC ~0.90–0.94 |
| hERG (Karim) | 385 | **0.826** | 0.880 | AUROC ~0.74–0.88 |
| CYP3A4 (Veith) | 6,722 | 0.784 | 0.614 | AUPRC ~0.79–0.88 |
| CYP2C9 (Veith) | 6,595 | 0.800 | 0.571 | AUPRC ~0.72–0.83 |
| CYP2D6 (Veith) | 7,163 | 0.743 | 0.328 | AUPRC ~0.62–0.74 |

P-gp and hERG land inside their published ranges without ever seeing the
benchmark. The three P450 heads fall below theirs, and the reason is the label,
not the model: the Veith sets are single-concentration qHTS at base rates of
0.13–0.32, while these heads are trained on dose-response IC50 values at base
rates of 0.72–0.84. Leaderboard entries are trained on each benchmark's own
split, so the comparison is structurally unfavourable here and is reported as
placement rather than ranking. All five are shown, including the three that
fall short.


## What is in this repository

Source, and the evaluation evidence. Not the data, and not the trained models.

| | in git | why |
|---|---|---|
| `chemrob/`, `scripts/` | yes | the pipeline |
| `artifacts/*.json` | yes | every number quoted here and in the paper, ~170 KB |
| `data/provenance.json` | yes | release identifiers, fetch window, package versions |
| `data/` (2.9 GB) | no | downloaded from ChEMBL / CO-ADD; rebuildable, and better cited than mirrored |
| `artifacts/*.joblib` (69 MB) | no | trained bundles; rebuildable from the data |
| `*.log` (50 MB) | no | machine- and path-specific |

To rebuild everything from nothing:

```bash
python scripts/build_dataset.py --max-records 60000    # downloads ChEMBL + CO-ADD, ~1h
python scripts/train.py                                 # ~65 min on 16 GB
```

`data/provenance.json` records the ChEMBL release, the fetch window, the CO-ADD
archive and the exact package versions the released numbers came from. ChEMBL is
a live endpoint, so a later release will not reproduce them exactly — match the
release identifier if you need to.

## Install

```bash
pip install -r requirements.txt
```

RDKit and scikit-learn are required. PyTorch is only needed for the D-MPNN
backend and FastAPI only for the REST API; neither is installed by default and
the pipeline runs without both:

```bash
pip install -e .[gnn]     # D-MPNN backend
pip install -e .[api]     # chemrob serve
```

## Run

```bash
python scripts/build_dataset.py --max-records 20000
```

Downloads ChEMBL bioactivity data for all configured targets (cached per target
under `data/chembl_cache/`, so re-runs are instant and interrupted downloads
resume), then curates it into `data/curated_dataset.parquet`.

```bash
python scripts/train.py
```

Trains the whole bundle into `artifacts/` (~40 min on 16 CPU threads). Add
`--dmpnn --epochs 20` to also train the graph network, or `--dmpnn-only` to
train it without touching the baseline bundle and its metrics.

```bash
python -m chemrob.cli predict --smiles "CC(=O)Oc1ccccc1C(=O)O"
python -m chemrob.cli predict --smiles "..." --disease "visceral leishmaniasis"
python -m chemrob.cli optimize --smiles "..." --class anticancer --rounds 2
python -m chemrob.cli groups --smiles "..."
python -m chemrob.cli diseases leishmaniasis
python -m chemrob.cli info --metrics --per-task
python -m chemrob.cli screen library.csv     # score a whole file
python -m chemrob.cli serve                 # web app on :8000
```

`serve` gives you two things: a structure-drawing workbench at
**http://127.0.0.1:8000/app** (sketch pad, disease box, full report inline) and
the API reference at `/docs`.

Add `--json` to `predict` and `optimize` for machine-readable output; the CLI's
text rendering is only one view of that same JSON.

---

## How it works

### 1. Standardization (`standardize.py`)

SMILES / InChI / molblock in → one canonical structure out. Salts stripped to
the parent fragment, charges neutralized where chemically sensible, functional
groups normalized, stereochemistry reassigned. Molecules outside 6–100 heavy
atoms, above 1000 Da, or containing non-organic elements are rejected — these
are assay artefacts and polymers, not drug candidates.

### 2. Representation (`featurize.py`)

Two encodings in parallel:

- **ECFP4 count fingerprint (2048 bits) + 33 physicochemical descriptors** —
  the fixed-length vector the gradient-boosting heads consume.
- **Atom/bond feature graph** — directed edges, each bond becoming two
  half-edges with a pointer to its reverse partner, for the D-MPNN.

The Morgan bit→atom map is retained, because the explainability layer needs to
know which atoms produced which fingerprint bit.

### 3. Labels (`curate.py`) — the part that decides whether metrics mean anything

Potency values are converted to pActivity (−log₁₀ molar). Labelling is
three-state, against **per-class** thresholds:

| Assay format | Classes | Active | Inactive |
|---|---|---|---|
| Biochemical (purified protein) | the 11 mechanistic classes | pActivity ≥ 7.0 (100 nM) | < 6.0 (1 µM) |
| Whole-organism MIC | antibacterial, antifungal | ≥ 5.5 (≈3 µM) | < 4.5 (≈32 µM) |
| Intracellular parasite IC50 | antiprotozoal | ≥ 6.0 | < 5.0 |

Anything between the two thresholds is **masked**, as is anything never assayed.

One threshold for everything would be wrong: 100 nM against a purified kinase is
a decent inhibitor, but a 100 nM MIC against *S. aureus* would be a remarkable
antibiotic, and demanding it would label nearly every real antibacterial as
inactive.

Read the thresholds literally when interpreting output. Imatinib comes out
`anticancer = 0` here, which looks wrong until you notice its best measurement
across this class's five targets is pActivity 6.48 — it is genuinely not a
≥ 100 nM binder of any of them. The label means "not potent at 100 nM against
*these targets*", not "not an anticancer drug".

**The masked state is the important one.** A compound assayed against COX-2 and
nothing else is not evidence of antibacterial *in*activity, so those cells are
excluded from the loss instead of being filled with zeros. Filling them with
zeros is the standard way multi-task bioactivity models end up reporting
excellent, meaningless numbers.

**Mass-concentration units are converted, not discarded.** Two-thirds of the
whole-organism records report µg/mL rather than molar, and they carry no
`pchembl_value`. Dropping them — the easy thing to do — cost this dataset 75% of
its antibacterial and 88% of its antifungal data. They are converted via
molecular weight (µg/mL ÷ MW × 10⁶ = nM), which is why standardization runs
*before* potency conversion in the pipeline.

**Replicates are collapsed by median, and consistency is judged on the IQR, not
the range.** This one matters more than it looks. A marketed drug carries
hundreds of measurements against its primary target across mutants, cell lines
and assay formats, so its min-to-max span is wide even when the bulk of values
agree closely. Filtering on range deletes exactly the best-characterised
compounds: gefitinib has 234 EGFR measurements with a median of 7.81 and a range
of 5.36, so a range filter throws all of them away and relabels gefitinib as a
weak EGFR binder. Its IQR is 1.99. Pairs with fewer than four measurements fall
back to the range, since quartiles are meaningless there.

A class is active if any of its targets is active, and inactive only if the
molecule was tested against at least one of them and none came out active.

### 4. Splitting

**Bemis–Murcko scaffold split**, not random. A molecule in the test set never
shares its core with one in training. Random splits on ChEMBL data inflate QSAR
metrics substantially, because close analogues end up on both sides.

### 5. Models

**Baseline** (`models/baseline.py`) — one calibrated `HistGradientBoosting`
classifier per task, each trained only on the rows where that task has a label,
with balanced class weights and sigmoid calibration on the validation fold.
Tasks with fewer than 60 labelled or 25 positive molecules are skipped rather
than fitted to noise.

**D-MPNN** (`models/dmpnn.py`) — the architecture from the deck: a shared
directed message-passing encoder feeding three heads.

```
molecular graph ──► D-MPNN encoder ──► attention + mean pooling
                          │                     │
                          │                     ├──► activity head  (14 classes)
                          │                     ├──► target head    (44 targets)
   Murcko scaffold ───────┘ (same weights) ─────┴──► scaffold head  (14 classes)
```

Messages live on directed bonds and each update subtracts the reverse edge's
message, so information never bounces straight back where it came from. Loss is
masked BCE with per-task positive weighting; focal loss is also implemented.
Output biases are initialised to each task's base rate.

The scaffold head shares the encoder rather than owning a second network — the
transfer-learning step from Module 2 of the deck. It is trained on each
molecule's core with that molecule's own labels, which is precisely the question
"given only this ring system, what activity does it tend to produce?"

### 6. Applicability domain (`applicability.py`)

Max and mean-top-5 Tanimoto similarity to the training set, reported globally
and per class. Below 0.25 the molecule is declared out of domain and the report
says the predictions are unreliable instead of quietly returning a number. A
high probability outside the domain is never labelled "high confidence".

### 7. Explainability (`explain.py`)

Atom-level attribution by **occlusion**: every ECFP bit knows which atoms
generated it, so an atom is silenced by zeroing its bits and the drop in
predicted probability is that atom's contribution. Model-agnostic, and expressed
in probability units rather than arbitrary attention mass. Atom scores are then
pooled onto the matched functional groups, so the answer reads "the
benzenesulfonamide drove this" rather than "atom 14 mattered". The D-MPNN
exposes its readout attention weights for the same purpose.

`--svg` writes the molecule with atoms coloured red (pushes toward active) to
blue (pushes away).

### 8. Functional-group associations (`fg_enrichment.py`)

104 SMARTS patterns covering functional groups, privileged ring systems,
pharmacophores and structural alerts. Which activity a group is tied to is
**measured on the training split**, not hard-coded: active rate with the group
versus without, scored by a one-sided Fisher exact test with Benjamini–Hochberg
correction. On this dataset 989 (group, class) pairs clear the minimum-support
filter and 261 survive correction at q < 0.05. Without the correction, dozens of
associations would look significant by chance alone.

### 9. Optimization engine (`optimize.py`, `mmp.py`)

Answers "what should I change on the ring?" with three sources of edits:

1. **Mined matched molecular pairs** — every pair of training molecules
   differing by one substituent, with the measured pActivity difference
   attributed to that swap. Rule means are shrunk toward zero by their own
   support, so a rule with 3 noisy observations cannot outrank one with 200.
2. **Curated bioisosteres** — standard medicinal-chemistry replacements.
3. **Ring replacement** — scaffold hopping across accepted ring-equivalence sets.

Candidates are re-scored by the activity model, then filtered on synthetic
accessibility (Ertl SA score), QED, Lipinski/Veber, and structural alerts, and
checked for novelty against the training set. Ranking is by predicted gain minus
penalties for being hard to make, undruglike, too far from the parent, or
outside the applicability domain.

`--rounds N` feeds each round's winners back in as new parents — the iterative
loop from the workflow diagram.

Mining this dataset yields **105,936 transformation rules** across all 14
classes. As a sanity check on whether they carry real SAR: the highest-scoring
anticancer transformations all convert some other group into a hydroxamic acid
(`O=C(NO)[*:1]`, +2.0 to +3.2 log units over 18–24 matched pairs), which is the
zinc-binding pharmacophore every HDAC inhibitor is built around. Nobody told the
pipeline that; it fell out of the pair statistics.

### 10. Disease knowledge graph (`kg.py`)

24 indications with synonyms, each resolving to concrete ChEMBL targets and
activity classes. Fuzzy search handles spelling variants; short acronyms ("TB",
"RA") require a whole-word match so they don't collide with unrelated names.
A resolved disease re-weights every activity class — on-indication classes keep
full weight, others are damped to 0.25 rather than hidden, so a strong
off-indication prediction still surfaces as a possible off-target effect. The
raw probability is always reported alongside the contextual score.

---

## Honest limitations

Read this section before quoting any number from the model.

**The headline metrics overstate prospective performance by ~0.2 AUPRC.** The
scaffold-split macro AUPRC is 0.905; the time-split figure, which is what you
should expect on chemistry published after training, is 0.709. Quote the
time-split number in any forward-looking claim.

**A class label is not a drug label.** Miltefosine, the oral antileishmanial, is
scored at only p=0.08 antiprotozoal by this model even though its *Leishmania
donovani* target head gives 0.49 - its recorded in-vitro potencies sit near the
class threshold. Class-level calls aggregate several targets and can disagree
with clinical reality; check the target-level output before concluding anything.


**The generative optimizer is a search, not a trained generator.** The deck
specifies a Graph-VAE / RL-fine-tuned generator and identifies it as the
riskiest component. What is implemented is a reward-guided search over a
discrete, precedented action space (mined MMP rules + bioisosteres + ring
swaps), using the activity model as the reward signal in the way an RL generator
would. The policy is a rule library rather than learned weights. This is the
prototype the deck recommends building first; a learned policy would replace the
rule library, not the scoring and filtering around it.

**"Activity class" means "active against this class's ChEMBL targets."** It is a
target-based proxy for a clinical pharmacological class, not a claim about what
a drug does in a patient. A compound predicted `anticancer` is predicted to
inhibit EGFR/VEGFR2/CDK2/HDAC1/ABL1, which is a long way from being an
anticancer drug.

**Classes are not equally supported.** Some heads rest on tens of thousands of
labelled molecules, others on a few hundred. Always read `positive_rate` and
`n` alongside AUROC in `cli info --metrics --per-task`. AUPRC and its lift over
the base rate are the numbers to trust; with a 3% active rate an AUROC of 0.85
can still be operationally useless.

**Whole-organism labels are noisier than protein labels.** MIC values for
antibacterial and antiprotozoal classes come from heterogeneous assays with
varying inoculum, media and readout, and phenotypic activity conflates target
engagement with permeability and efflux.

**The antiprotozoal head over-fires on kinase-like scaffolds.** It has the
highest positive rate of any class (61%), and the whole-cell antiparasitic
screening sets in ChEMBL are dominated by re-screened kinase-inhibitor
libraries. The result is that a molecule such as imatinib or gefitinib can come
back with a high antiprotozoal probability despite never having been assayed
against a parasite. Some of that is real — plenty of kinase inhibitors do kill
*Plasmodium* in culture — but the confidence is overstated, and the per-class
`nearest_active_similarity` in the report is the field to check before believing
it.

**Predicted gains from the optimizer are model estimates, not measurements.**
They inherit every bias in the training data and are only as good as the
applicability-domain check that accompanies them.

**No ADMET, no toxicity, no selectivity modelling.** The deck's L5 mentions
ADMET filtering; what is implemented is drug-likeness and synthetic
accessibility only. Anything about absorption, metabolism, hERG or hepatotoxicity
would need separate models and separate data.

**Not for clinical or regulatory use.** This is a hypothesis-generation tool for
prioritising which compounds to synthesise and assay first.

---

## Project layout

```
chemrob_dj/  (folder can be renamed freely; nothing depends on its name)
├── chemrob/
│   ├── config.py          activity classes, targets, disease graph, thresholds
│   ├── standardize.py     L1  structure input and standardization
│   ├── featurize.py       L2  ECFP4 + descriptors + graph featurisation
│   ├── fgroups.py             104 SMARTS patterns
│   ├── scaffold.py            Bemis-Murcko cores and the scaffold split
│   ├── chembl.py              ChEMBL web-service client (cached, threaded)
│   ├── curate.py              cleaning, aggregation, three-state labelling
│   ├── dataset.py             feature building and split assembly
│   ├── models/
│   │   ├── baseline.py    L3  calibrated gradient-boosting heads
│   │   └── dmpnn.py       L3  multi-task directed message-passing network
│   ├── train.py               baseline training pipeline
│   ├── train_dmpnn.py         graph-network training loop
│   ├── evaluate.py            masked multi-label metrics
│   ├── applicability.py       domain check
│   ├── explain.py         L4  occlusion attribution and depiction
│   ├── fg_enrichment.py       group -> activity association testing
│   ├── mmp.py             L5  matched-pair mining
│   ├── bioisosteres.py    L5  curated replacement library
│   ├── properties.py          SA score, QED, Lipinski, Veber, alerts
│   ├── optimize.py        L5  candidate generation, scoring, ranking
│   ├── kg.py              L6  disease-target knowledge graph
│   ├── covalent.py            covalent-warhead detection and caveat
│   ├── batch.py               library screening
│   ├── banner.py              terminal welcome banner
│   ├── static/app.html    L7  structure-drawing workbench
│   ├── predict.py             inference orchestration
│   ├── report.py              terminal rendering
│   ├── cli.py                 command-line interface
│   └── api.py             L7  FastAPI service
├── scripts/
│   ├── build_dataset.py
│   ├── train.py
│   └── evaluate.py
├── data/                  downloaded and curated data (created at runtime)
└── artifacts/             trained models and metrics (created at runtime)
```

## Data sources

ChEMBL (EMBL-EBI) via the public web service, CC BY-SA 3.0. Please cite ChEMBL
if you publish results derived from this pipeline. The activity thresholds,
target lists and disease mappings in `config.py` are project-specific choices
and should be reviewed against the pharmacology you actually care about.
