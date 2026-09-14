"""
Layer 7 - REST API.

The backend the deck's web interface would sit on. The predictor is loaded once
at start-up rather than per request, because loading the fingerprint index for
the applicability-domain check is the expensive part.

    python -m chemrob.cli serve
    -> http://127.0.0.1:8000/docs
"""
from __future__ import annotations

import logging
from functools import lru_cache
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel, Field

from . import kg
from .config import CLASS_BY_KEY, CLASS_KEYS
from .fgroups import describe_groups
from .standardize import parse_user_structure

log = logging.getLogger(__name__)

app = FastAPI(
    title="ChemRob DJ",
    version="0.1.0",
    description=(
        "Structure-based pharmacological activity prediction, scaffold "
        "classification, explainability and lead optimization."
    ),
)


@lru_cache(maxsize=1)
def get_predictor():
    from .predict import ChemRobPredictor

    return ChemRobPredictor()


class PredictRequest(BaseModel):
    structure: str = Field(..., description="SMILES, InChI, or a molblock")
    disease: str | None = Field(None, description="indication to anchor the analysis to")
    explain: bool = True
    include_svg: bool = False


class OptimizeRequest(BaseModel):
    structure: str
    activity_class: str = Field(..., description=f"one of: {', '.join(CLASS_KEYS)}")
    n_suggestions: int = 10
    rounds: int = Field(1, ge=1, le=4)
    beam_width: int = Field(3, ge=1, le=10)
    include_ring_swaps: bool = True
    max_sa: float = 6.0


@app.get("/health")
def health() -> dict:
    try:
        p = get_predictor()
        return {"status": "ok", "model": p.bundle.metadata or {}}
    except FileNotFoundError as exc:
        return {"status": "no_model", "detail": str(exc)}


@app.get("/classes")
def classes() -> list[dict]:
    return [
        {"key": k, "label": CLASS_BY_KEY[k].label,
         "description": CLASS_BY_KEY[k].description,
         "targets": [{"chembl_id": t.chembl_id, "name": t.name}
                     for t in CLASS_BY_KEY[k].targets]}
        for k in CLASS_KEYS
    ]


@app.get("/diseases")
def diseases(q: str | None = None) -> list[dict]:
    """The search bar behind the disease box."""
    if q:
        return [m.to_dict() for m in kg.search_disease(q)]
    return kg.list_diseases()


@app.post("/standardize")
def standardize(req: PredictRequest) -> dict:
    res = parse_user_structure(req.structure)
    if not res.ok:
        raise HTTPException(status_code=400, detail=res.reason)
    return {
        "standardized_smiles": res.smiles,
        "inchikey": res.inchikey,
        "functional_groups": describe_groups(res.mol),
    }


@app.post("/predict")
def predict(req: PredictRequest) -> dict:
    try:
        predictor = get_predictor()
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    rep = predictor.predict(
        req.structure, disease=req.disease, explain=req.explain,
        make_svg=req.include_svg,
    )
    if not rep.get("ok"):
        raise HTTPException(status_code=400, detail=rep.get("error"))
    return rep


@app.post("/optimize")
def optimize_endpoint(req: OptimizeRequest) -> dict:
    from .optimize import optimize

    if req.activity_class not in CLASS_KEYS:
        raise HTTPException(
            status_code=400,
            detail=f"unknown activity class; expected one of {list(CLASS_KEYS)}",
        )
    try:
        predictor = get_predictor()
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    rep = optimize(
        predictor, req.structure, req.activity_class,
        n_suggestions=req.n_suggestions, rounds=req.rounds,
        beam_width=req.beam_width, include_ring_swaps=req.include_ring_swaps,
        max_sa=req.max_sa,
    )
    if not rep.get("ok"):
        raise HTTPException(status_code=400, detail=rep.get("error"))
    return rep


@app.post("/depict")
def depict(req: PredictRequest) -> Response:
    """SVG of the molecule with atoms coloured by their contribution."""
    from .explain import highlight_svg, occlusion_attribution, plain_svg

    res = parse_user_structure(req.structure)
    if not res.ok:
        raise HTTPException(status_code=400, detail=res.reason)
    if not req.explain:
        return Response(content=plain_svg(res.mol), media_type="image/svg+xml")

    predictor = get_predictor()
    rep = predictor.predict(req.structure, disease=req.disease, explain=False)
    lead = rep["activity_predictions"][0]["class_key"]
    j = CLASS_KEYS.index(lead)
    scores = occlusion_attribution(predictor._class_predict_fn(j), res.mol)
    return Response(content=highlight_svg(res.mol, scores), media_type="image/svg+xml")


STATIC_DIR = Path(__file__).resolve().parent / "static"


@app.get("/app", response_class=HTMLResponse)
def workbench() -> FileResponse:
    """The structure-drawing workbench: sketch pad, disease box, full report."""
    return FileResponse(STATIC_DIR / "app.html", media_type="text/html")


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    """Welcome screen. Themed to follow the viewer's light/dark preference."""
    return """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Welcome to ChemRob DJ</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans+Condensed:wght@600;700&family=IBM+Plex+Sans:wght@400;500&display=swap">
<style>
:root{
  --ground:#FAFBFC;--surface:#FFF;--sunk:#F1F4F8;--ink:#14171D;--muted:#5B6572;
  --faint:#8A94A2;--rule:#DDE2E8;--accent:#1F4FA8;--accent-soft:#E8EFFA;--good:#1E6F4C;
}
@media (prefers-color-scheme:dark){:root{
  --ground:#0F1216;--surface:#161B21;--sunk:#1C222A;--ink:#E6EAF0;--muted:#9AA5B4;
  --faint:#6E7988;--rule:#262E38;--accent:#7BA6F0;--accent-soft:#17233A;--good:#5CC292;
}}
*{box-sizing:border-box}
body{margin:0;background:var(--ground);color:var(--ink);
  font-family:"IBM Plex Sans",system-ui,sans-serif;line-height:1.6}
.wrap{max-width:820px;margin:0 auto;padding:64px 24px 80px}
.eyebrow{font-family:"IBM Plex Mono",monospace;font-size:11px;letter-spacing:.14em;
  text-transform:uppercase;color:var(--accent);margin:0 0 12px}
h1{font-family:"IBM Plex Sans Condensed",sans-serif;font-weight:700;
  font-size:clamp(34px,6vw,50px);line-height:1.05;margin:0 0 12px;letter-spacing:-.015em}
.sub{font-size:17px;color:var(--muted);max-width:60ch;margin:0 0 28px}
.hero{display:flex;align-items:center;gap:22px;flex-wrap:wrap;
  border-bottom:1px solid var(--rule);padding-bottom:32px;margin-bottom:32px}
.ring{flex:0 0 auto}
.status{font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--good);
  display:inline-flex;align-items:center;gap:7px}
.dot{width:7px;height:7px;border-radius:50%;background:var(--good);display:inline-block}
h2{font-family:"IBM Plex Sans Condensed",sans-serif;font-size:15px;font-weight:600;
  text-transform:uppercase;letter-spacing:.08em;color:var(--faint);margin:34px 0 14px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));
  gap:1px;background:var(--rule);border:1px solid var(--rule)}
.grid a{background:var(--surface);padding:16px 18px;text-decoration:none;display:block;color:inherit}
.grid a:hover,.grid a:focus-visible{background:var(--accent-soft)}
.grid .m{font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--accent);
  display:block;margin-bottom:5px}
.grid .d{font-size:13.5px;color:var(--muted)}
.cta{display:inline-block;background:var(--accent);color:#fff;text-decoration:none;
  font-weight:500;padding:11px 22px;margin-top:6px}
.cta:hover,.cta:focus-visible{opacity:.88}
footer{margin-top:44px;padding-top:20px;border-top:1px solid var(--rule);
  color:var(--faint);font-size:13px}
:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
</style></head>
<body><div class="wrap">
  <div class="hero">
    <svg class="ring" width="86" height="96" viewBox="0 0 86 96" aria-hidden="true">
      <g fill="none" stroke="var(--accent)" stroke-width="2.4" stroke-linejoin="round">
        <path d="M43 6 L76 25 L76 63 L43 82 L10 63 L10 25 Z"/>
        <path d="M43 18 L66 31 L66 57 L43 70 L20 57 L20 31 Z" opacity=".38"/>
      </g>
      <circle cx="43" cy="6" r="4.5" fill="var(--accent)"/>
      <circle cx="76" cy="63" r="4.5" fill="var(--accent)"/>
      <circle cx="10" cy="25" r="4.5" fill="var(--accent)"/>
    </svg>
    <div>
      <p class="eyebrow">Pharmacological activity prediction</p>
      <h1>Welcome to ChemRob DJ</h1>
      <p class="sub">Draw or paste a structure and get predicted activity across 14 classes,
      potency, selectivity, the functional groups driving the call, and precedented
      suggestions for what to change next.</p>
      <span class="status"><span class="dot"></span>Server running</span>
    </div>
  </div>

  <a class="cta" href="/app">Open the workbench</a>
  <a class="cta" href="/docs" style="background:transparent;color:var(--accent);border:1px solid var(--accent);margin-left:8px">API reference</a>

  <h2>Endpoints</h2>
  <div class="grid">
    <a href="/docs#/default/predict_predict_post"><span class="m">POST /predict</span>
      <span class="d">Activity, potency, scaffold, targets, selectivity, explanation</span></a>
    <a href="/docs#/default/optimize_endpoint_optimize_post"><span class="m">POST /optimize</span>
      <span class="d">Ranked structural edits toward a chosen activity</span></a>
    <a href="/docs#/default/depict_depict_post"><span class="m">POST /depict</span>
      <span class="d">SVG with atoms coloured by contribution</span></a>
    <a href="/docs#/default/standardize_standardize_post"><span class="m">POST /standardize</span>
      <span class="d">Canonicalize a SMILES, InChI, or molblock</span></a>
    <a href="/diseases"><span class="m">GET /diseases</span>
      <span class="d">Disease-target knowledge graph, 24 indications</span></a>
    <a href="/classes"><span class="m">GET /classes</span>
      <span class="d">The 14 activity classes and their ChEMBL targets</span></a>
  </div>

  <footer>Predictions are hypotheses for prioritising synthesis and assay work,
  not assay results. Not for clinical or regulatory use.
  Bioactivity data from ChEMBL (EMBL-EBI).</footer>
</div></body></html>"""
