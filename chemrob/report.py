"""
Terminal rendering of a prediction or optimization report.

Kept separate from predict.py so the JSON stays the single source of truth: the
API returns it unchanged, and this module is only one way of looking at it.
"""
from __future__ import annotations

from .config import CLASS_BY_KEY


def _bar(p: float, width: int = 20) -> str:
    filled = int(round(p * width))
    return "#" * filled + "." * (width - filled)


def _rule(title: str, width: int = 78) -> str:
    return f"\n{title}\n" + "-" * width


def render_prediction(rep: dict, *, top_classes: int = 6, top_groups: int = 8) -> str:
    if not rep.get("ok"):
        return f"Could not process the structure: {rep.get('error')}"

    L: list[str] = []
    inp = rep["input"]
    L.append("=" * 78)
    L.append("ChemRob DJ - pharmacological activity report")
    L.append("=" * 78)
    L.append(f"Input SMILES     : {inp['raw'][:70]}")
    L.append(f"Standardized     : {inp['standardized_smiles']}")
    L.append(f"InChIKey         : {inp['inchikey']}")
    L.append(f"Murcko scaffold  : {inp['scaffold'] or '(acyclic)'}")

    p = rep["properties"]
    L.append(_rule("PHYSICOCHEMICAL PROFILE"))
    lip = p["lipinski"]
    L.append(
        f"MW {lip['mw']:.1f} | cLogP {lip['logp']:.2f} | HBD {lip['hbd']} | HBA {lip['hba']} "
        f"| TPSA {p['veber']['tpsa']} | QED {p['qed']} | SA {p['sa_score']}"
    )
    L.append(
        f"Lipinski violations: {lip['violations']} | Veber: "
        f"{'pass' if p['veber']['passes'] else 'fail'} | "
        f"alerts: {', '.join(p['structural_alerts']) or 'none'}"
    )

    ad = rep.get("applicability_domain")
    if ad:
        L.append(_rule("APPLICABILITY DOMAIN"))
        L.append(
            f"Verdict: {ad['verdict'].upper()}  "
            f"(max Tanimoto {ad['max_similarity']:.2f}, mean top-5 {ad['mean_top_k']:.2f})"
        )
        for nb in ad["nearest_neighbours"][:3]:
            L.append(f"  nearest: {nb['similarity']:.2f}  {nb['smiles'][:60]}")

    L.append(_rule("PREDICTED PHARMACOLOGICAL ACTIVITY (whole molecule)"))
    has_potency = any("predicted_potency" in r for r in rep["activity_predictions"])
    hdr = f"{'class':<34}{'prob':>7}  {'call':<9}{'confidence':<12}"
    L.append(hdr + (f"{'potency':>10}  bar" if has_potency else "bar"))
    for r in rep["activity_predictions"][:top_classes]:
        line = (f"{r['class_label'][:33]:<34}{r['probability']:>7.3f}  "
                f"{r['call']:<9}{r['confidence'][:11]:<12}")
        if has_potency:
            line += f"{r.get('predicted_potency', 'n/a'):>10}  "
        L.append(line + _bar(r["probability"]))

    if rep.get("scaffold_predictions"):
        L.append(_rule("CORE-RING (SCAFFOLD) CLASSIFIER"))
        L.append("What this ring system alone tends to produce:")
        for r in rep["scaffold_predictions"][:5]:
            L.append(f"  {r['class_label'][:40]:<42}{r['probability']:>7.3f}  {_bar(r['probability'], 14)}")

    if rep.get("target_predictions"):
        L.append(_rule("TARGET LIKELIHOOD"))
        for r in rep["target_predictions"][:8]:
            pot = f"  {r['predicted_potency']:>9}" if "predicted_potency" in r else ""
            L.append(f"  {r['name'][:40]:<42}{r['probability']:>7.3f}{pot}  ({r['chembl_id']})")

    groups = rep.get("functional_groups") or []
    if groups:
        L.append(_rule("FUNCTIONAL-GROUP DEEP SEARCH"))
        L.append("Detected: " + ", ".join(g["name"] for g in groups[:top_groups]))
        assoc = rep.get("functional_group_associations") or []
        if assoc:
            L.append("\nHistorical activity associations (enrichment vs base rate):")
            for a in assoc[:5]:
                best = a["associations"][:3]
                items = ", ".join(
                    f"{x['class_key']} {x['enrichment']:.2f}x" for x in best
                )
                L.append(f"  {a['group_name'][:30]:<32}{items}")
        else:
            L.append("(no statistically significant associations for these groups)")

    dc = rep.get("disease_context")
    if dc:
        L.append(_rule("DISEASE CONTEXT"))
        m = dc["matched"]
        L.append(f"Query '{dc['query']}' resolved to: {m['name']} (score {m['score']}, {m['matched_on']})")
        L.append("Mapped targets: " + ", ".join(t["name"] for t in dc["targets"]))
        if dc.get("target_predictions"):
            L.append("Predicted activity against those targets:")
            for t in dc["target_predictions"]:
                L.append(f"  {t['name'][:44]:<46}{t['probability']:>7.3f}")
        L.append("\nRe-ranked for this indication:")
        for r in (rep.get("ranked_for_disease") or [])[:5]:
            flag = "*" if r["on_indication"] else " "
            L.append(
                f" {flag}{r['class_label'][:33]:<34}p={r['probability']:.3f}  "
                f"contextual={r['contextual_score']:.3f}"
            )

    liabs = rep.get("liabilities") or []
    if liabs:
        L.append(_rule("SAFETY LIABILITIES (ADMET)"))
        L.append("Predicted risk - high values here are reasons for caution, not merit.")
        L.append(f"{'liability':<38}{'prob':>7}  {'concern':<10}bar")
        for r in liabs:
            L.append(f"{r['class_label'][:37]:<38}{r['probability']:>7.3f}  "
                     f"{r.get('concern','')!s:<10}{_bar(r['probability'], 14)}")

    selb = rep.get("selectivity")
    if selb and selb.get("pairs"):
        L.append(_rule("SELECTIVITY"))
        L.append("Selectivity index = predicted pActivity(primary) - pActivity(anti-target).")
        L.append(f"{'pair':<26}{'SI':>7}{'fold':>9}  verdict")
        shown = 0
        for p in selb["pairs"]:
            if not p["relevant"]:
                continue
            L.append(f"{p['label'][:25]:<26}{p['selectivity_index_log']:>+7.2f}"
                     f"{p['fold_selectivity']:>8.1f}x  {p['verdict']}")
            shown += 1
        if shown == 0:
            L.append("(no pair involves a target this molecule is predicted to engage)")
        else:
            top = next(p for p in selb["pairs"] if p["relevant"])
            L.append("")
            L.append(f"Why it matters - {top['label']}: {top['rationale']}")
        pr = selb.get("promiscuity") or {}
        if pr.get("n_targets_scored"):
            L.append("")
            L.append(f"Target engagement: {pr['n_predicted_active']} of "
                     f"{pr['n_targets_scored']} predicted active -> {pr['flag']}")

    cov = rep.get("covalent")
    if cov:
        L.append(_rule("COVALENT CHEMISTRY DETECTED"))
        for w in cov["warheads"]:
            L.append(f"  {w['name']}  (x{w['count']})")
            L.append(f"     mechanism : {w['mechanism']}")
            L.append(f"     e.g.      : {w['example_drugs']}")
        L.append("")
        L.append("  " + cov["advice"])

    ex = rep.get("explanation")
    if ex:
        L.append(_rule(f"EXPLANATION - {CLASS_BY_KEY[ex['explained_class']].label}"))
        L.append("Groups driving the prediction (+ pushes towards active):")
        for g in ex["groups"][:6]:
            sign = "+" if g["total_contribution"] >= 0 else "-"
            L.append(
                f"  {sign} {g['name'][:32]:<34}total={g['total_contribution']:+.4f}  "
                f"mean/atom={g['mean_contribution']:+.4f}"
            )
        if ex.get("fragments"):
            L.append("\nTop local environments:")
            for f in ex["fragments"][:4]:
                L.append(f"  {f['fragment'][:40]:<42}{f['score']:+.4f}")

    if rep.get("warnings"):
        L.append(_rule("WARNINGS"))
        for w in rep["warnings"]:
            L.append(f"  ! {w}")

    m = rep.get("model", {})
    L.append(_rule("MODEL PROVENANCE"))
    L.append(f"Backend: {m.get('backend')}")
    L.append(f"Trained on {m.get('trained_on_molecules')} molecules, split: {m.get('split')}")
    L.append("")
    return "\n".join(L)


def render_optimization(rep: dict) -> str:
    if not rep.get("ok"):
        return f"Optimization failed: {rep.get('error')}"

    L: list[str] = []
    par, obj = rep["parent"], rep["objective"]
    L.append("=" * 78)
    L.append(f"ChemRob DJ - optimization for {obj['class_label']}")
    L.append("=" * 78)
    L.append(f"Parent          : {par['smiles']}")
    L.append(f"Predicted p({obj['class_key']}) = {par['probability']:.3f}")
    L.append(
        f"Parent QED {par['properties']['qed']} | SA {par['properties']['sa_score']} | "
        f"MW {par['properties']['lipinski']['mw']}"
    )
    L.append(f"\nEvaluated {rep['n_candidates_evaluated']} candidates over "
             f"{len(rep['rounds'])} round(s).")

    L.append(_rule("RANKED SUGGESTIONS"))
    for i, s in enumerate(rep["suggestions"], 1):
        pr = s["properties"]
        L.append(f"\n{i}. {s['smiles']}")
        L.append(f"   edit      : {s['edit']}  [{s['source']}]")
        pot = ""
        if s.get("predicted_pactivity") is not None:
            d = s.get("delta_pactivity")
            pot = f"   potency pAct {s['predicted_pactivity']:.2f}"
            if d is not None:
                pot += f" ({d:+.2f})"
        L.append(
            f"   predicted : {s['predicted_probability']:.3f} "
            f"({s['delta_vs_parent']:+.3f} vs parent)   score {s['score']:+.3f}{pot}"
        )
        L.append(
            f"   props     : QED {pr['qed']} | SA {pr['sa_score']} | "
            f"MW {pr['lipinski']['mw']} | cLogP {pr['lipinski']['logp']} | "
            f"sim-to-parent {s['similarity_to_parent']}"
        )
        flags = []
        if s["novel_vs_training_set"]:
            flags.append("novel")
        if not s["in_applicability_domain"]:
            flags.append("OUTSIDE DOMAIN")
        if pr["structural_alerts"]:
            flags.append("alerts: " + ", ".join(pr["structural_alerts"]))
        if flags:
            L.append("   flags     : " + " | ".join(flags))
        if s["precedent"].get("evidence"):
            L.append(f"   evidence  : {s['precedent']['evidence']}")

    L.append("")
    L.append(rep["note"])
    L.append("")
    return "\n".join(L)
