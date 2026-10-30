# Submission documents

| Document | What it is |
|---|---|
| [architecture.md](architecture.md) | Mermaid views drawn from this code: component/deployment, happy-path and failure sequences, the Quarantine state machine and one ER diagram per service |
| [business-rules.md](business-rules.md) | BR1 to BR4 as testable invariants, each mapped to the class that enforces it and the unit, HTTP, script and restart evidence that proves it |
| [demo-runbook.md](demo-runbook.md) | Presenter's script: five terminals, the copy-pasteable curl sequence for the happy path, the challenge scripts with the output lines to point at, and the on-camera troubleshooting table |
| [report-draft.md](report-draft.md) | Draft text laid out on the assignment's ten report sections, including the six design decisions with the alternatives that were rejected, plus the appendix skeletons for the contribution table, reflections and AI-use declaration |
| [pre-submission-checklist.md](pre-submission-checklist.md) | The assignment's ten pre-submission boxes marked pass or outstanding against a named artifact, and the full list of commands to re-run before pushing |
| `openapi/` | The four API contracts: `intake.yaml`, `quarantine.yaml`, `scanner.yaml`, `release.yaml` |

The report itself, the video links, the team contribution table, the individual
reflections and the AI-use declaration are still to be assembled -
[report-draft.md](report-draft.md) has the text and the structure, and
[pre-submission-checklist.md](pre-submission-checklist.md) tracks what is left. Keep the
report PDF at no more than 15 main pages excluding appendices and export the diagrams
from [architecture.md](architecture.md) into it.

Every claim in the report has to be traceable, so cite the evidence folder rather than
restating the result:

| Assignment item | Evidence |
|---|---|
| Challenge 1, concurrency control on a contended resource | [`../evidence/challenge-1-concurrency`](../evidence/challenge-1-concurrency) — `scripts/concurrency.py`, and `scripts/reclaim.py` for the crash case |
| Challenge 2, idempotency | [`../evidence/challenge-2-idempotency`](../evidence/challenge-2-idempotency) — `scripts/idempotency.sh` |
| Challenge 3A, service-to-service resilience | [`../evidence/challenge-3a-resilience`](../evidence/challenge-3a-resilience) — `scripts/resilience.py`, Part E included |
| The release trust boundary | `../evidence/tests/verification-run.txt` — `scripts/verification.py` |
| Persistence across a restart | [`../evidence/tests`](../evidence/tests) — the four `*-persistence.txt` files |
| The whole transaction end to end | `../evidence/tests/e2e-run.txt` — `scripts/e2e.sh` |
| Test suite | `../evidence/tests` — Surefire reports for all four services |
| A stranger can run it | `../evidence/tests/fresh-clone-run.txt` — a clean clone of the branch, built and driven following `README.md` only |

Each service documents its own API, data design, configuration and limitations in
`../intake-service/README.md`, `../quarantine-service/README.md`,
`../scanner-service/README.md` and `../release-service/README.md`.
