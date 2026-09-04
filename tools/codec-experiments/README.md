# Coordinate compression experiments

These standalone measurements investigate existing encoder portfolios and rejected new-body hypotheses; they are not production codecs. They preserve the complete `WP:` prefix, header, CRC-16 and contextual base-91 costs in every reported size. No corpus, compiled classes or generated production-source copies are stored here.

## Reproduce

Supply the external JSON array of routes with `waypoints` arrays containing integer `x`, `y`, `z` fields. Python experiments use only Python's standard library.

```sh
python3 tools/codec-experiments/explore.py /path/to/waypoints.json
python3 tools/codec-experiments/optimize_descriptor.py /path/to/waypoints.json
```

`explore.py --output /path/to/results.json` optionally saves individual route lengths. Its baseline frame/ASCII implementation is checked against all 27 pinned golden payloads. Its baseline aggregate matched the actual Java writer at 75,674 characters. Python and Java DEFLATE can differ when their zlib versions differ.

The Java harness uses already compiled production classes and their runtime dependencies. Supply a file containing the test runtime classpath and a Java 25 JDK home; the runner does not build the project or modify production classes.

Generate the classpath once using Java 25: `./gradlew writeCodecExperimentClasspath --max-workers=2`. It writes `build/reports/codec-experiment-classpath.txt` for the selected Minecraft target. Regenerate it after switching targets or checkouts.

```sh
python3 tools/codec-experiments/run_existing.py /path/to/waypoints.json \
  --classpath-file /path/to/test-runtime-classpath.txt \
  --java-home /path/to/jdk-25
```

The runner copies the current sparse codec into a temporary directory, renames it and adds quotient as another direct coordinate candidate. It fails if the expected source insertion points change. The original production decoder checks every candidate's decoded waypoints, group name, zone, gradient/load modes, route kind, default radius and skip-ahead setting. Generated sources and classes are removed afterward.

The bare/general comparison uses newly created anonymous, unknown-zone, manual-color, sequence groups and fresh `Waypoint.at(x,y,z)` points. Reusing a rich input group would preserve metadata and structural flags in the general codec, invalidating the comparison.

## Findings on the supplied 302 routes

The corpus contains 31,418 points. It is the previously used/pinned corpus family, not a newly held-out evaluation. All totals below include complete ASCII shares. Prefix sets take up to the first N points of each existing route; they are correlated synthetic slices, not additional independent routes. The actual input routes range from 3 to 5,000 points.

| Existing-format candidate | Baseline chars | Best portfolio chars | Saved | Winning routes |
|---|---:|---:|---:|---:|
| Bare kind 2 plus normalized general kind 0 | 75,674 | 75,612 | 62 (0.082%) | 7 |
| Sparse: one hide-beacon flag per route | 77,531 | 77,449 | 82 (0.106%) | 17 |
| Sparse: one subwaypoint per route | 77,145 | 77,075 | 70 (0.091%) | 15 |
| Sparse: one precise point per route | 77,912 | 77,830 | 82 (0.105%) | 15 |
| Sparse: every seventh point is a subwaypoint | 80,091 | 80,073 | 18 (0.022%) | 7 |

The sparse input flags/precision above are deliberately injected synthetic cases, not observed features of the supplied corpus. Those rows compare against complete public-route export selection, so gains already available from another existing content kind are not counted twice. The largest bare/general gain was 23 characters on one route; the largest sparse gain was 18. General mode had no wins on any 2-, 3-, 5-, 10- or 20-point prefix set. For the one-hide-beacon sparse case, those prefix sets saved respectively 0, 0, 1, 5 and 4 characters across all 302 slices.

Single-pass Java timings were exploratory and included JIT/cache/order effects, not controlled benchmark results. The full-corpus bare encoder took about 41 ms and an additional general search about 180 ms. The sparse candidate took about 47–61 ms per 302-route synthetic set. Those values do not measure a user-perceptible latency regression or a stable throughput ratio. The general candidate adds its existing complete mode search; quotient adds its bounded combinatorial parameter search and direct grammar candidates. Neither changes decoding cost for an existing wire.

## Rejected new-body experiments

`explore.py` sizes an escaped direct descriptor using per-axis predictors (previous point, extrapolated previous delta, point two steps back, or previous point plus the delta two steps back), first-order Rice in blocks of 16/32/64, and X/Z diagonal delta transforms. The 10-bit descriptor cost and every parameter/control bit are included.

| Optional new candidate | Corpus characters saved | Winning routes |
|---|---:|---:|
| Per-axis predictor | 25 (0.033%) | 8 |
| Block Rice 16 | 3 | 2 |
| Block Rice 32 | 7 | 2 |
| Block Rice 64 | 18 | 1 |
| Diagonal deltas | 4 | 4 |
| Combined portfolio | 45 (0.059%) | 13 |

The combined portfolio saves respectively 1, 0, 2 and 4 characters across the 302 slices at prefix lengths 2, 3, 5 and 10. This does not justify adding a new wire grammar. These proposed body bytes are sizing prototypes: no production parser, canonical rejection checks, hostile-input validation or complete proposed-body decoder was implemented. Their escaped descriptors are currently invalid V10 inputs and must not be exported.

`optimize_descriptor.py` measures a separate hypothetical version-11 Rice form. It includes Rice descriptor cost when choosing parameters, compares the frozen common tuples, and allows ordinary `k=0` for a short all-zero axis instead of forcing the eight-bit constant-axis sentinel. The actual V10 decoder requires its existing canonical parameter choice; this cannot be emitted as an encoder-only V10 change. The experiment changes the header's version nibble and recomputes the full CRC/text, choosing the old complete V10 share whenever shorter.

That hypothetical portfolio saves 75 corpus characters (0.10%; 63 winning routes). For 2/3/5/10-point prefixes it saves 381/359/277/173 characters (6.50%/5.41%/3.44%/1.52%); typical individual gains are only one or two characters. It checks invertibility of the coordinate predictor arithmetic, not a version-11 wire decoder. A new decoder/version and backward-import tests would be necessary before adoption. No format change was made for these gains.

## Adopted common-export eligibility improvement

The common names/colors/zone projection is materially different from the rejected coordinate experiments. The previous selector required every export field to be enabled before trying compact kind 1, even when disabling radii, flags and group metadata would leave every actual field unchanged. Reusing the already defined full compact body is safe for that narrowly gated case.

`CompactProjectionExperiment.java` compares the former general-only path against the existing full compact candidate. It uses `CodecRouteCorpus` and `CodecRouteBenchmarkTest.commonProjection`, verifies every decoded waypoint record and supported group setting against the general projection, and retains the general wire whenever shorter. Run it with the same classpath/JDK arguments as above plus `--common-compact`:

```sh
python3 tools/codec-experiments/run_existing.py /path/to/waypoints.json \
  --classpath-file /path/to/test-runtime-classpath.txt \
  --java-home /path/to/jdk-25 --common-compact
```

The gate requires names, colors and zone enabled; radii, waypoint flags and group metadata disabled; no label; a regular manual/sequence group with default radius, skip-ahead and palette settings; zero point radii; 24-bit RGB; and flags/precision identical to what the requested projection retains. Existing compact name, coordinate and temporary-point eligibility still applies. It does not copy or rewrite source metadata, alter the decoder, or change any canonical body.

| Common projection | Previous general chars | Best portfolio chars | Saved |
|---|---:|---:|---:|
| 302 complete routes | 177,664 | 93,529 | 84,135 (47.36%) |
| 302 up-to-1-point prefixes | 12,554 | 12,527 | 27 (0.22%) |
| 302 up-to-2-point prefixes | 16,085 | 13,970 | 2,115 (13.15%) |
| 302 up-to-3-point prefixes | 18,393 | 15,100 | 3,293 (17.90%) |
| 302 up-to-5-point prefixes | 22,159 | 17,192 | 4,967 (22.42%) |
| 302 up-to-10-point prefixes | 31,100 | 21,974 | 9,126 (29.34%) |

All 302 actual common groups pass the gate: compact wins on 266, ties on two, and loses on 34, where the existing general form remains available. The standalone prefix sets here are up-to-N slices of all records; they differ from the main benchmark's deduplicated, exact-N synthetic cohort.

An exploratory before-change run used four warmups and seven alternating measurement passes: median complete corpus export was 275.6 ms for the public general-only path and 296.2 ms when also trying compact (+7.46%). This is an in-process observation, not a controlled latency claim. The durable harness forces the old general writer explicitly so it continues to demonstrate the size difference after the production selector changes; its timing excludes some public entry-point bookkeeping.

## Decision

Adopt only the bounded common-export eligibility improvement. Retain the coordinate and envelope algorithms: their other existing-format opportunities are too small on this evidence to justify expanding every export's search, and sparse gains are measured only on synthetic metadata. Tiny-route body changes save about one character per share and require a new format/canonical contract. Keep the rejected evidence for future work if a representative new workload or a concrete latency/length requirement changes that tradeoff.

## Envelope and tiny-route audit

`EnvelopeAudit.java`, `EnvelopeRichAudit.java`, and `TinyEnvelopeAudit.java` measure the current transport using the production Java codecs and strict `CodecRouteCorpus` loader. They keep their own compiled classes and outputs outside the repository:

```sh
JAVA_HOME=/path/to/jdk-25 \
  bash tools/codec-experiments/run-envelope-audit.sh \
  /path/to/route-array.json \
  /path/to/runtime-classpath.txt \
  /tmp/waypointer-codec-envelope
```

The runtime classpath must point to already-compiled production classes and dependencies. The script runs no Gradle task. Outputs are `results.csv` (bare/full routes and tiny prefixes), `rich-results.csv` (full-fidelity and no-names profiles), and `tiny-version11-results.csv` (an explicitly incompatible, rejected count-in-header hypothesis). Baseline files under `baselines/envelope-*-8e85aea.csv` preserve measurements without committing the source corpus.

The transport sweep saves only five bare characters across 302 routes and one character for each richer profile. It has no compatible tiny-prefix gain. The hypothetical new version saves 84 characters across the 59 actual eligible small routes and is not recommended. See [the complete envelope audit](../../docs/release/1.10-codec-envelope-review.md) for framing costs, byte counts, verification, and the existing compact no-names canonical-selection compatibility hazard.
