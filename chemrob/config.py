"""
Central configuration for ChemRob DJ.

Everything the pipeline needs to know about *what* it is predicting lives here:
the pharmacological activity classes, the ChEMBL targets that define each class,
the activity thresholds used to turn raw potency into labels, and the
disease -> target mapping used by the knowledge-graph layer.

All ChEMBL identifiers below were verified against the live ChEMBL web service.
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------
# Paths
# --------------------------------------------------------------------------
PKG_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = PKG_ROOT.parent


def _resolve_dir(name: str, folder: str) -> Path:
    """
    Find the data / artifacts directory, in order of decreasing specificity.

    Deriving these from the package's parent works while the code sits in a
    checkout, and breaks the moment it is pip-installed: the parent is then
    site-packages, where a 2 GB dataset has no business living and which is not
    reliably writable. Resolution therefore tries, in order:

      1. an explicit environment variable (CHEMROB_DATA / CHEMROB_ARTIFACTS,
         with the pre-rename CHEMACTIVITY_* names still honoured);
      2. the folder beside the package, which is the editable-install and
         checkout layout and keeps existing setups working untouched;
      3. %LOCALAPPDATA%\\ChemRobDJ on Windows, ~/.local/share/chemrob elsewhere.
    """
    for env in (f"CHEMROB_{name}", f"CHEMACTIVITY_{name}"):
        value = os.environ.get(env)
        if value:
            return Path(value).expanduser()

    # Frozen (PyInstaller) build: the package lives inside a temporary
    # extraction directory that is deleted on exit, so data and artifacts must
    # be looked for beside the .exe the user actually launched, not beside the
    # module.
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent / folder

    beside = PROJECT_ROOT / folder
    if beside.exists():
        return beside

    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        return base / "ChemRobDJ" / folder
    return Path.home() / ".local" / "share" / "chemrob" / folder


DATA_DIR = _resolve_dir("DATA", "data")
ARTIFACT_DIR = _resolve_dir("ARTIFACTS", "artifacts")
RAW_ACTIVITIES = DATA_DIR / "chembl_activities.parquet"
CURATED_DATASET = DATA_DIR / "curated_dataset.parquet"
MMP_RULES = DATA_DIR / "mmp_rules.parquet"
FG_STATS = DATA_DIR / "functional_group_stats.parquet"

for _d in (DATA_DIR, ARTIFACT_DIR):
    _d.mkdir(parents=True, exist_ok=True)

# --------------------------------------------------------------------------
# Activity thresholds
# --------------------------------------------------------------------------
# pActivity = -log10(molar potency).  9.0 == 1 nM, 7.0 == 100 nM, 6.0 == 1 uM.
#
# Thresholds are per class, not global, because "active" means different things
# for different assay formats. 100 nM against a purified kinase is a decent
# inhibitor; 100 nM MIC against S. aureus would be a remarkable antibiotic, and
# demanding it would label almost every real antibacterial as inactive. Each
# class therefore carries the cutoff that is conventional for how it is measured.
#
# Defaults, used for biochemical (purified-protein) assays:
ACTIVE_THRESHOLD = 7.0      # >= this  -> label 1   (100 nM)
INACTIVE_THRESHOLD = 6.0    # <  this  -> label 0   (1 uM)
# Whole-organism MIC assays (antibacterial, antifungal):
PHENOTYPIC_ACTIVE_THRESHOLD = 5.5     # ~3 uM
PHENOTYPIC_INACTIVE_THRESHOLD = 4.5   # ~32 uM
# Intracellular parasite IC50 assays sit between the two.
PARASITE_ACTIVE_THRESHOLD = 6.0
PARASITE_INACTIVE_THRESHOLD = 5.0
# Safety / ADMET liabilities: 10 uM is the conventional concern threshold for
# hERG and CYP inhibition, 100 uM a clear pass.
# hERG and CYP data cluster tightly around 10 uM (median pActivity 5.0), and
# almost nothing is reported weaker than 100 uM, so a 4.0 cutoff produced zero
# negatives. 10 uM active / 32 uM inactive matches both the conventional safety
# flag and where the data actually sits.
LIABILITY_ACTIVE_THRESHOLD = 5.0
LIABILITY_INACTIVE_THRESHOLD = 4.5
# Between the two thresholds the measurement is treated as ambiguous and masked
# out of the loss rather than being forced into a class.

# Potency endpoints we accept.
ACCEPTED_ACTIVITY_TYPES = ("IC50", "Ki", "Kd", "EC50", "MIC", "GI50", "XC50", "AC50")
# Relations that represent an exact measurement. Censored '>' values are still
# usable as evidence of inactivity and are handled explicitly during curation.
EXACT_RELATIONS = ("=", "~")


@dataclass(frozen=True)
class TargetSpec:
    """A single ChEMBL target that contributes evidence to an activity class."""
    chembl_id: str
    name: str
    kind: str = "protein"  # 'protein' or 'organism'


@dataclass(frozen=True)
class ActivityClass:
    """One pharmacological activity class = one output neuron of the activity head."""
    key: str
    label: str
    description: str
    targets: tuple[TargetSpec, ...] = field(default_factory=tuple)
    active_threshold: float = ACTIVE_THRESHOLD
    inactive_threshold: float = INACTIVE_THRESHOLD
    # A liability is predicted the same way but means the opposite: hitting
    # hERG is a reason to drop a compound, not a result to optimise toward.
    # The flag keeps these out of the activity ranking and the optimizer's
    # objectives, and into a safety section of their own.
    liability: bool = False

    @property
    def assay_format(self) -> str:
        return "phenotypic" if any(t.kind == "organism" for t in self.targets) else "biochemical"


ACTIVITY_CLASSES: tuple[ActivityClass, ...] = (
    ActivityClass(
        "anticancer", "Anticancer / antiproliferative",
        "Inhibition of oncogenic kinases and epigenetic writers driving tumour growth.",
        (
            TargetSpec("CHEMBL203", "EGFR"),
            TargetSpec("CHEMBL279", "VEGFR2 (KDR)"),
            TargetSpec("CHEMBL301", "CDK2"),
            TargetSpec("CHEMBL325", "HDAC1"),
            TargetSpec("CHEMBL1862", "ABL1"),
        ),
    ),
    ActivityClass(
        "anti_inflammatory", "Anti-inflammatory",
        "Inhibition of the eicosanoid cascade and inflammatory MAP-kinase signalling.",
        (
            TargetSpec("CHEMBL221", "COX-1"),
            TargetSpec("CHEMBL230", "COX-2"),
            TargetSpec("CHEMBL215", "5-lipoxygenase"),
            TargetSpec("CHEMBL260", "p38 MAPK (MAPK14)"),
        ),
    ),
    ActivityClass(
        "antidiabetic", "Antidiabetic",
        "Incretin, insulin-sensitising and polyol-pathway targets in type-2 diabetes.",
        (
            TargetSpec("CHEMBL284", "DPP-4"),
            TargetSpec("CHEMBL235", "PPAR-gamma"),
            TargetSpec("CHEMBL1900", "Aldose reductase (AKR1B1)"),
        ),
    ),
    ActivityClass(
        "antihypertensive", "Antihypertensive / cardiovascular",
        "Renin-angiotensin system modulation for blood-pressure control.",
        (
            TargetSpec("CHEMBL1808", "ACE"),
            TargetSpec("CHEMBL227", "Angiotensin II type-1 receptor"),
            TargetSpec("CHEMBL286", "Renin"),
        ),
    ),
    ActivityClass(
        "antiviral_hiv", "Antiviral (HIV)",
        "The three classical HIV-1 enzymatic targets.",
        (
            TargetSpec("CHEMBL247", "HIV-1 reverse transcriptase"),
            TargetSpec("CHEMBL243", "HIV-1 protease"),
            TargetSpec("CHEMBL3471", "HIV-1 integrase"),
        ),
    ),
    ActivityClass(
        "antidepressant_cns", "Antidepressant / CNS",
        "Monoamine transporters, monoamine oxidase A and serotonergic receptors.",
        (
            TargetSpec("CHEMBL228", "Serotonin transporter (SERT)"),
            TargetSpec("CHEMBL222", "Noradrenaline transporter (NET)"),
            TargetSpec("CHEMBL238", "Dopamine transporter (DAT)"),
            TargetSpec("CHEMBL1951", "MAO-A"),
            TargetSpec("CHEMBL224", "5-HT2A receptor"),
        ),
    ),
    ActivityClass(
        "analgesic_opioid", "Analgesic (opioid)",
        "The mu / kappa / delta opioid receptor family.",
        (
            TargetSpec("CHEMBL233", "Mu opioid receptor"),
            TargetSpec("CHEMBL237", "Kappa opioid receptor"),
            TargetSpec("CHEMBL236", "Delta opioid receptor"),
        ),
    ),
    ActivityClass(
        "antihistaminic", "Antihistaminic / antiallergic",
        "Histamine H1 and H3 receptor antagonism.",
        (
            TargetSpec("CHEMBL231", "Histamine H1 receptor"),
            TargetSpec("CHEMBL264", "Histamine H3 receptor"),
        ),
    ),
    ActivityClass(
        "antithrombotic", "Antithrombotic / anticoagulant",
        "Serine proteases of the coagulation cascade.",
        (
            TargetSpec("CHEMBL204", "Thrombin"),
            TargetSpec("CHEMBL244", "Factor Xa"),
        ),
    ),
    ActivityClass(
        "anti_alzheimer", "Anti-Alzheimer / nootropic",
        "Cholinesterases and amyloid processing.",
        (
            TargetSpec("CHEMBL220", "Acetylcholinesterase"),
            TargetSpec("CHEMBL1914", "Butyrylcholinesterase"),
            TargetSpec("CHEMBL4822", "BACE1"),
        ),
    ),
    ActivityClass(
        "antiparkinson", "Antiparkinson",
        "Dopaminergic and adenosinergic targets used in Parkinson disease.",
        (
            TargetSpec("CHEMBL2039", "MAO-B"),
            TargetSpec("CHEMBL217", "Dopamine D2 receptor"),
            TargetSpec("CHEMBL251", "Adenosine A2A receptor"),
        ),
    ),
    ActivityClass(
        "antibacterial", "Antibacterial",
        "Whole-organism growth inhibition of Gram-positive, Gram-negative and mycobacterial pathogens.",
        (
            TargetSpec("CHEMBL352", "Staphylococcus aureus", "organism"),
            TargetSpec("CHEMBL354", "Escherichia coli", "organism"),
            TargetSpec("CHEMBL360", "Mycobacterium tuberculosis", "organism"),
            TargetSpec("CHEMBL348", "Pseudomonas aeruginosa", "organism"),
            # Completing the ESKAPE panel - the pathogens antibacterial
            # screening is actually aimed at. Each adds >12k measurements.
            TargetSpec("CHEMBL350", "Klebsiella pneumoniae", "organism"),
            TargetSpec("CHEMBL614425", "Acinetobacter baumannii", "organism"),
            TargetSpec("CHEMBL357", "Enterococcus faecium", "organism"),
        ),
        PHENOTYPIC_ACTIVE_THRESHOLD, PHENOTYPIC_INACTIVE_THRESHOLD,
    ),
    ActivityClass(
        "antifungal", "Antifungal",
        "Whole-organism growth inhibition of pathogenic yeasts and moulds.",
        (
            TargetSpec("CHEMBL366", "Candida albicans", "organism"),
            # This was the weakest class in evaluation (AUPRC 0.767 on 157 test
            # positives) and rested on C. albicans alone. These two roughly
            # triple its evidence base and take it beyond a single yeast.
            TargetSpec("CHEMBL363", "Aspergillus fumigatus", "organism"),
            TargetSpec("CHEMBL365", "Cryptococcus neoformans", "organism"),
        ),
        PHENOTYPIC_ACTIVE_THRESHOLD, PHENOTYPIC_INACTIVE_THRESHOLD,
    ),
    ActivityClass(
        "antiprotozoal", "Antiprotozoal (antileishmanial / antimalarial)",
        "Whole-organism activity against Leishmania, Plasmodium and Trypanosoma.",
        (
            TargetSpec("CHEMBL367", "Leishmania donovani", "organism"),
            TargetSpec("CHEMBL364", "Plasmodium falciparum", "organism"),
            TargetSpec("CHEMBL612849", "Trypanosoma brucei", "organism"),
            TargetSpec("CHEMBL368", "Trypanosoma cruzi", "organism"),
        ),
        PARASITE_ACTIVE_THRESHOLD, PARASITE_INACTIVE_THRESHOLD,
    ),
    # ---------------- safety liabilities (ADMET) ----------------
    ActivityClass(
        "herg_liability", "hERG cardiotoxicity risk",
        "Blockade of the hERG potassium channel, the standard preclinical "
        "predictor of QT prolongation and torsades de pointes.",
        (
            TargetSpec("CHEMBL240", "hERG (KCNH2)"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    # One head per isoform, not one pooled "CYP inhibition" head.
    # The pooled version scored AUPRC 0.963 internally and then collapsed on the
    # TDC Veith benchmarks (CYP2D6 AUPRC 0.266 against a 0.13 base rate). Pooling
    # five isoforms at an 88% positive rate taught it "looks like a CYP ligand"
    # rather than which isoform is inhibited, which is the question actually
    # being asked.
    ActivityClass(
        "cyp3a4_inhibition", "CYP3A4 inhibition",
        "The dominant drug-metabolising isoform; inhibition drives the majority of clinically significant interactions.",
        (
            TargetSpec("CHEMBL340", "CYP3A4"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    ActivityClass(
        "cyp2d6_inhibition", "CYP2D6 inhibition",
        "Highly polymorphic; inhibition compounds the variability already present between poor and extensive metabolisers.",
        (
            TargetSpec("CHEMBL289", "CYP2D6"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    ActivityClass(
        "cyp2c9_inhibition", "CYP2C9 inhibition",
        "Metabolises warfarin and phenytoin, where a small shift in clearance has a large clinical consequence.",
        (
            TargetSpec("CHEMBL3397", "CYP2C9"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    ActivityClass(
        "cyp1a2_inhibition", "CYP1A2 inhibition",
        "Induced by smoking and inhibited by fluoroquinolones; relevant to theophylline and clozapine.",
        (
            TargetSpec("CHEMBL3356", "CYP1A2"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    ActivityClass(
        "cyp2c19_inhibition", "CYP2C19 inhibition",
        "Activates clopidogrel, so inhibition removes the prodrug's effect.",
        (
            TargetSpec("CHEMBL3622", "CYP2C19"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
    ActivityClass(
        "pgp_efflux", "P-glycoprotein efflux",
        "ABCB1 interaction, which limits oral absorption and brain penetration.",
        (
            TargetSpec("CHEMBL4302", "P-glycoprotein (ABCB1)"),
        ),
        LIABILITY_ACTIVE_THRESHOLD, LIABILITY_INACTIVE_THRESHOLD,
        liability=True,
    ),
)

CLASS_KEYS: tuple[str, ...] = tuple(c.key for c in ACTIVITY_CLASSES)
# Pharmacological classes only - what the optimizer may aim at and what the
# activity ranking shows.
THERAPEUTIC_CLASS_KEYS: tuple[str, ...] = tuple(
    c.key for c in ACTIVITY_CLASSES if not c.liability
)
LIABILITY_CLASS_KEYS: tuple[str, ...] = tuple(
    c.key for c in ACTIVITY_CLASSES if c.liability
)
CLASS_BY_KEY: dict[str, ActivityClass] = {c.key: c for c in ACTIVITY_CLASSES}

ALL_TARGETS: tuple[TargetSpec, ...] = tuple(t for c in ACTIVITY_CLASSES for t in c.targets)
TARGET_KEYS: tuple[str, ...] = tuple(t.chembl_id for t in ALL_TARGETS)
TARGET_BY_ID: dict[str, TargetSpec] = {t.chembl_id: t for t in ALL_TARGETS}
TARGET_TO_CLASS: dict[str, str] = {
    t.chembl_id: c.key for c in ACTIVITY_CLASSES for t in c.targets
}

# --------------------------------------------------------------------------
# Disease -> target knowledge graph (Layer 6)
# --------------------------------------------------------------------------
@dataclass(frozen=True)
class DiseaseEntry:
    key: str
    name: str
    synonyms: tuple[str, ...]
    targets: tuple[str, ...]
    primary_classes: tuple[str, ...]


DISEASES: tuple[DiseaseEntry, ...] = (
    DiseaseEntry("visceral_leishmaniasis", "Visceral leishmaniasis (kala-azar)",
                 ("leishmaniasis", "kala azar", "kala-azar", "leishmania", "black fever"),
                 ("CHEMBL367",), ("antiprotozoal",)),
    DiseaseEntry("malaria", "Malaria",
                 ("plasmodium", "falciparum", "cerebral malaria"),
                 ("CHEMBL364",), ("antiprotozoal",)),
    DiseaseEntry("chagas", "Chagas disease",
                 ("chagas", "trypanosoma cruzi", "american trypanosomiasis"),
                 ("CHEMBL368",), ("antiprotozoal",)),
    DiseaseEntry("sleeping_sickness", "Human African trypanosomiasis",
                 ("sleeping sickness", "trypanosomiasis", "trypanosoma"),
                 ("CHEMBL612849",), ("antiprotozoal",)),
    DiseaseEntry("tuberculosis", "Tuberculosis",
                 ("tb", "mycobacterium", "mdr-tb", "pulmonary tuberculosis"),
                 ("CHEMBL360",), ("antibacterial",)),
    DiseaseEntry("bacterial_infection", "Bacterial infection / sepsis",
                 ("mrsa", "staph", "staphylococcus", "e coli", "escherichia",
                  "pseudomonas", "antibiotic resistance", "sepsis", "bacterial",
                  "klebsiella", "acinetobacter", "enterococcus", "eskape",
                  "vre", "carbapenem resistant"),
                 ("CHEMBL352", "CHEMBL354", "CHEMBL348", "CHEMBL350",
                  "CHEMBL614425", "CHEMBL357"), ("antibacterial",)),
    DiseaseEntry("candidiasis", "Invasive fungal infection",
                 ("candida", "thrush", "fungal infection", "mycosis", "antifungal",
                  "aspergillosis", "cryptococcosis", "invasive fungal"),
                 ("CHEMBL366", "CHEMBL363", "CHEMBL365"), ("antifungal",)),
    DiseaseEntry("breast_cancer", "Breast cancer",
                 ("mammary carcinoma", "her2", "breast tumour", "breast tumor"),
                 ("CHEMBL203", "CHEMBL301", "CHEMBL325"), ("anticancer",)),
    DiseaseEntry("lung_cancer", "Non-small-cell lung cancer",
                 ("nsclc", "lung carcinoma", "lung tumour", "lung tumor"),
                 ("CHEMBL203", "CHEMBL279"), ("anticancer",)),
    DiseaseEntry("leukemia", "Chronic myeloid leukaemia",
                 ("cml", "leukaemia", "leukemia", "bcr-abl", "blood cancer"),
                 ("CHEMBL1862", "CHEMBL301"), ("anticancer",)),
    DiseaseEntry("solid_tumour_angiogenesis", "Solid tumour / angiogenesis",
                 ("angiogenesis", "solid tumour", "solid tumor", "carcinoma", "cancer"),
                 ("CHEMBL279", "CHEMBL203", "CHEMBL325", "CHEMBL301", "CHEMBL1862"),
                 ("anticancer",)),
    DiseaseEntry("rheumatoid_arthritis", "Rheumatoid arthritis",
                 ("arthritis", "ra", "joint inflammation", "osteoarthritis"),
                 ("CHEMBL230", "CHEMBL260", "CHEMBL215"), ("anti_inflammatory",)),
    DiseaseEntry("inflammation_pain", "Inflammation / inflammatory pain",
                 ("inflammation", "anti-inflammatory", "nsaid", "oedema", "edema"),
                 ("CHEMBL230", "CHEMBL221", "CHEMBL215"), ("anti_inflammatory",)),
    DiseaseEntry("asthma", "Asthma / allergic airway disease",
                 ("allergy", "allergic rhinitis", "bronchial asthma", "urticaria"),
                 ("CHEMBL215", "CHEMBL231"), ("anti_inflammatory", "antihistaminic")),
    DiseaseEntry("type2_diabetes", "Type-2 diabetes mellitus",
                 ("diabetes", "t2dm", "hyperglycaemia", "hyperglycemia", "insulin resistance"),
                 ("CHEMBL284", "CHEMBL235", "CHEMBL1900"), ("antidiabetic",)),
    DiseaseEntry("diabetic_complications", "Diabetic neuropathy / cataract",
                 ("diabetic neuropathy", "diabetic cataract", "polyol"),
                 ("CHEMBL1900",), ("antidiabetic",)),
    DiseaseEntry("hypertension", "Hypertension",
                 ("high blood pressure", "blood pressure", "heart failure",
                  "cardiovascular", "antihypertensive"),
                 ("CHEMBL1808", "CHEMBL227", "CHEMBL286"), ("antihypertensive",)),
    DiseaseEntry("hiv_aids", "HIV / AIDS",
                 ("hiv", "aids", "retrovirus", "antiretroviral"),
                 ("CHEMBL247", "CHEMBL243", "CHEMBL3471"), ("antiviral_hiv",)),
    DiseaseEntry("depression", "Major depressive disorder",
                 ("depression", "mdd", "antidepressant", "anxiety", "mood disorder"),
                 ("CHEMBL228", "CHEMBL222", "CHEMBL1951", "CHEMBL224"),
                 ("antidepressant_cns",)),
    DiseaseEntry("schizophrenia", "Schizophrenia / psychosis",
                 ("psychosis", "antipsychotic", "schizophrenic"),
                 ("CHEMBL217", "CHEMBL224"), ("antidepressant_cns", "antiparkinson")),
    DiseaseEntry("pain", "Moderate-to-severe pain",
                 ("analgesia", "analgesic", "nociception", "opioid", "chronic pain"),
                 ("CHEMBL233", "CHEMBL237", "CHEMBL236"), ("analgesic_opioid",)),
    DiseaseEntry("thrombosis", "Thrombosis / stroke prophylaxis",
                 ("clot", "anticoagulant", "dvt", "stroke", "embolism"),
                 ("CHEMBL204", "CHEMBL244"), ("antithrombotic",)),
    DiseaseEntry("alzheimer", "Alzheimer disease",
                 ("alzheimers", "alzheimer", "dementia", "cognitive decline", "amyloid"),
                 ("CHEMBL220", "CHEMBL1914", "CHEMBL4822"), ("anti_alzheimer",)),
    DiseaseEntry("parkinson", "Parkinson disease",
                 ("parkinsons", "parkinson", "dopaminergic", "bradykinesia"),
                 ("CHEMBL2039", "CHEMBL217", "CHEMBL251"), ("antiparkinson",)),
    DiseaseEntry("peptic_ulcer", "Gastric acid / peptic ulcer",
                 ("ulcer", "gastric", "acid reflux", "gerd"),
                 ("CHEMBL264", "CHEMBL231"), ("antihistaminic",)),
)

DISEASE_BY_KEY: dict[str, DiseaseEntry] = {d.key: d for d in DISEASES}

# --------------------------------------------------------------------------
# Featurisation / model hyper-parameters
# --------------------------------------------------------------------------
FP_RADIUS = 2          # ECFP4
FP_BITS = 2048
SCAFFOLD_FP_BITS = 1024

RANDOM_SEED = 42
TEST_FRACTION = 0.10
VALID_FRACTION = 0.10

# Applicability-domain thresholds on max Tanimoto similarity to the training set.
AD_IN_DOMAIN = 0.40
AD_BORDERLINE = 0.25

# Minimum labelled molecules required before a class head is trained at all.
MIN_LABELS_PER_CLASS = 60
MIN_POSITIVES_PER_CLASS = 25
