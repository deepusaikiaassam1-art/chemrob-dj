Superseded results - DO NOT QUOTE
=================================

These files describe earlier builds and were left in artifacts/ where they
could be mistaken for current results.

  ensemble_metrics.json    baseline vs D-MPNN, ChEMBL+CO-ADD build (14 classes,
                           50 targets). The D-MPNN checkpoint it used has not
                           been retrained since; regenerating it means retraining
                           the graph network on the current build.

  version_comparison.json  v3 vs v4 on a common test set, i.e. the measurement
                           that CO-ADD added +0.0016. Historically correct and
                           still the right way to compare two bundles, but it is
                           about a pair of builds neither of which is current.

The current build is described by metadata.json, metrics.json, validation.json
and ablation.json in the parent directory. Nothing here is comparable to those.

  dmpnn_metrics.json       D-MPNN test metrics, 14 classes.
  ensemble_weights.json    Per-class blend weights, 14 classes.

Both carry 14 heads against the current build's 17, so loading them against the
current bundle would silently misalign class indices.
