"""
End-to-end check that every layer of the trained bundle actually runs.

    python scripts/smoke_test.py

Exercises standardization, functional-group search, all three prediction heads,
the applicability domain, attribution, the disease graph, and the optimizer, on
a panel of well-known drugs whose pharmacology is not in doubt. It asserts that
the plumbing works and that outputs are well-formed - it is not a substitute for
the held-out metrics in `scripts/evaluate.py`.
"""
from __future__ import annotations

import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import CLASS_KEYS

PANEL: list[tuple[str, str, str]] = [
    ("imatinib", "Cc1ccc(NC(=O)c2ccc(CN3CCN(C)CC3)cc2)cc1Nc1nccc(-c2cccnc2)n1", "anticancer"),
    ("celecoxib", "Cc1ccc(-c2cc(C(F)(F)F)nn2-c2ccc(S(N)(=O)=O)cc2)cc1", "anti_inflammatory"),
    ("gefitinib", "COc1cc2ncnc(Nc3ccc(F)c(Cl)c3)c2cc1OCCCN1CCOCC1", "anticancer"),
    ("fluoxetine", "CNCCC(Oc1ccc(C(F)(F)F)cc1)c1ccccc1", "antidepressant_cns"),
    ("donepezil", "COc1cc2c(cc1OC)C(=O)C(CC1CCN(Cc3ccccc3)CC1)C2", "anti_alzheimer"),
    ("miltefosine", "CCCCCCCCCCCCCCCCOP(=O)([O-])OCC[N+](C)(C)C", "antiprotozoal"),
    ("ciprofloxacin", "O=C(O)c1cn(C2CC2)c2cc(N3CCNCC3)c(F)cc2c1=O", "antibacterial"),
    ("fluconazole", "OC(Cn1cncn1)(Cn1cncn1)c1ccc(F)cc1F", "antifungal"),
    ("pioglitazone", "CCc1ccc(CCOc2ccc(CC3SC(=O)NC3=O)cc2)nc1", "antidiabetic"),
    ("losartan", "CCCCc1nc(Cl)c(CO)n1Cc1ccc(-c2ccccc2-c2nnn[nH]2)cc1", "antihypertensive"),
]

PASS, FAIL = "PASS", "FAIL"
results: list[tuple[str, str, str]] = []


def check(name: str, fn) -> None:
    try:
        detail = fn()
        results.append((PASS, name, detail or ""))
    except AssertionError as exc:
        results.append((FAIL, name, f"assertion: {exc}"))
    except Exception as exc:  # noqa: BLE001
        results.append((FAIL, name, f"{type(exc).__name__}: {exc}"))
        traceback.print_exc()


def main() -> int:
    from chemrob.optimize import optimize
    from chemrob.predict import ChemRobPredictor
    from chemrob.standardize import parse_user_structure

    print("Loading bundle ...")
    predictor = ChemRobPredictor()
    meta = predictor.bundle.metadata or {}
    print(f"  trained on {meta.get('n_molecules')} molecules; "
          f"{len(predictor.bundle.activity.trained_tasks)} activity heads\n")

    # --- input handling
    check("parse SMILES", lambda: parse_user_structure(PANEL[0][1]).smiles[:40])
    check("parse InChI", lambda: parse_user_structure(
        "InChI=1S/C9H8O4/c1-6(10)13-8-5-3-2-4-7(8)9(11)12/h2-5H,1H3,(H,11,12)"
    ).smiles)
    check("reject nonsense", lambda: (
        "ok" if not parse_user_structure("not a molecule").ok else
        (_ for _ in ()).throw(AssertionError("garbage input was accepted"))
    ))
    check("salt stripped to parent", lambda: (
        parse_user_structure("CN(C)CCOC(c1ccccc1)c1ccccc1.Cl").smiles
    ))

    # --- full report on the panel
    #
    # These are marketed drugs, so most of them are IN ChEMBL and therefore in
    # the training set. Their predictions are a plumbing check and a memorisation
    # check - NOT evidence of accuracy. Training-set membership is printed for
    # each one so the distinction cannot be missed; the honest accuracy numbers
    # are the scaffold-split metrics from `scripts/evaluate.py`.
    training_set = set(predictor.bundle.ad.smiles) if predictor.bundle.ad else set()

    for name, smi, expected in PANEL:
        def run(smi=smi, expected=expected, name=name):
            rep = predictor.predict(smi, explain=True)
            assert rep["ok"], rep.get("error")
            assert rep["activity_predictions"], "no activity predictions returned"
            assert rep["properties"]["qed"] >= 0
            probs = {r["class_key"]: r["probability"] for r in rep["activity_predictions"]}
            for p in probs.values():
                assert 0.0 <= p <= 1.0, f"probability out of range: {p}"
            top = rep["activity_predictions"][0]
            rank = [r["class_key"] for r in rep["activity_predictions"]].index(expected) + 1 \
                if expected in probs else -1
            seen = "IN-TRAIN" if rep["input"]["standardized_smiles"] in training_set else "unseen"
            return (f"top={top['class_key']}({top['probability']:.2f}) "
                    f"{expected}=p{probs.get(expected, float('nan')):.2f} rank {rank}/14 "
                    f"[{seen}]")
        check(f"predict {name}", run)

    # --- genuinely held-out check
    def holdout_check():
        """
        Score real test-split molecules - cores the model has never seen - and
        report agreement with their recorded labels. This is the only part of
        this script that says anything about accuracy.
        """
        import numpy as np
        from chemrob.config import CLASS_KEYS
        from chemrob.dataset import load_dataset

        ds = load_dataset()
        te = ds.test_idx[:400]
        smis = [ds.smiles[i] for i in te]
        probs = predictor.score_smiles(smis)
        Y = ds.Y_class[te]

        correct = total = 0
        for j, key in enumerate(CLASS_KEYS):
            thr = predictor.threshold_for(key)
            m = ~np.isnan(Y[:, j]) & ~np.isnan(probs[:, j])
            if not m.any():
                continue
            pred = (probs[m, j] >= thr).astype(float)
            correct += int((pred == Y[m, j]).sum())
            total += int(m.sum())
        assert total > 0, "no labelled held-out cells to score"
        assert any(s not in training_set for s in smis), "held-out sample leaked into training"
        return f"{correct}/{total} labelled cells correct ({100*correct/total:.1f}%) on unseen scaffolds"

    check("held-out scaffold accuracy", holdout_check)

    # --- disease anchoring
    check("disease anchoring", lambda: (
        lambda rep: (
            (_ for _ in ()).throw(AssertionError("no disease context"))
            if not rep.get("disease_context") else
            f"resolved to {rep['disease_context']['matched']['name']}, "
            f"top ranked {rep['ranked_for_disease'][0]['class_key']}"
        )
    )(predictor.predict(PANEL[5][1], disease="visceral leishmaniasis", explain=False)))

    check("unknown disease warns", lambda: (
        lambda rep: (
            "warned"
            if any("did not match" in w for w in rep["warnings"])
            else (_ for _ in ()).throw(AssertionError("no warning for unknown disease"))
        )
    )(predictor.predict(PANEL[0][1], disease="zzzz nonexistent", explain=False)))

    # --- explainability
    def explain_check():
        rep = predictor.predict(PANEL[1][1], explain=True)
        ex = rep["explanation"]
        assert ex is not None, "no explanation produced"
        assert len(ex["atom_scores"]) > 0
        assert ex["groups"], "no group attribution"
        top = ex["groups"][0]
        return f"top group '{top['name']}' total={top['total_contribution']:+.4f}"
    check("attribution", explain_check)

    def svg_check():
        rep = predictor.predict(PANEL[1][1], explain=True, make_svg=True)
        svg = rep["explanation"]["svg"]
        assert svg.startswith("<?xml") or "<svg" in svg
        return f"{len(svg)} bytes of SVG"
    check("highlight SVG", svg_check)

    # --- out-of-domain detection
    def ood_check():
        rep = predictor.predict("C1CC2CCC1(CCCCCCCCCC)C2", explain=False)
        assert rep["ok"]
        return f"verdict={rep['applicability_domain']['verdict']}"
    check("applicability domain on odd chemistry", ood_check)

    # --- scaffold head
    def scaffold_check():
        rep = predictor.predict(PANEL[2][1], explain=False)
        assert rep["scaffold_predictions"], "scaffold head returned nothing"
        top = rep["scaffold_predictions"][0]
        return f"core '{rep['input']['scaffold'][:34]}' -> {top['class_key']} {top['probability']:.2f}"
    check("core-ring classifier", scaffold_check)

    # --- target head
    def target_check():
        rep = predictor.predict(PANEL[0][1], explain=False)
        assert rep["target_predictions"], "target head returned nothing"
        t = rep["target_predictions"][0]
        return f"{t['name']} {t['probability']:.2f}"
    check("target likelihood head", target_check)

    # --- optimizer
    def optimize_check():
        rep = optimize(predictor, PANEL[1][1], "anti_inflammatory",
                       n_suggestions=5, rounds=1)
        assert rep["ok"], rep.get("error")
        assert rep["suggestions"], "optimizer produced no suggestions"
        s = rep["suggestions"][0]
        assert s["smiles"] != rep["parent"]["smiles"]
        return (f"{rep['n_candidates_evaluated']} evaluated, best "
                f"{s['predicted_probability']:.3f} ({s['delta_vs_parent']:+.3f}) "
                f"via {s['source']}")
    check("optimization engine", optimize_check)

    def optimize_rounds_check():
        rep = optimize(predictor, PANEL[6][1], "antibacterial",
                       n_suggestions=3, rounds=2, beam_width=2)
        assert rep["ok"], rep.get("error")
        return f"{len(rep['rounds'])} rounds, {rep['n_candidates_evaluated']} candidates"
    check("iterative optimization loop", optimize_rounds_check)

    def bad_class_check():
        try:
            optimize(predictor, PANEL[0][1], "not_a_class")
        except ValueError:
            return "rejected unknown class"
        raise AssertionError("unknown class was accepted")
    check("optimizer rejects bad class", bad_class_check)

    # --- report
    check("terminal rendering", lambda: (
        lambda text: f"{len(text.splitlines())} lines"
    )(__import__("chemrob.report", fromlist=["report"]).render_prediction(
        predictor.predict(PANEL[0][1], disease="chronic myeloid leukaemia")
    )))

    # --- summary
    print("\n" + "=" * 92)
    n_fail = 0
    for status, name, detail in results:
        if status == FAIL:
            n_fail += 1
        print(f"[{status}] {name:<42} {detail}")
    print("=" * 92)
    print(f"{len(results) - n_fail} passed, {n_fail} failed")
    return 1 if n_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
