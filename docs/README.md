# Submission documents

| Document | What it is |
|---|---|
| [architecture.md](architecture.md) | Mermaid views drawn from this code: component/deployment, happy-path and failure sequences, the Quarantine state machine and one ER diagram per service |
| [business-rules.md](business-rules.md) | BR1 to BR4 as testable invariants, each mapped to the class that enforces it and the unit, HTTP, script and restart evidence that proves it |
| [demo-runbook.md](demo-runbook.md) | Presenter's script: five terminals, the copy-pasteable curl sequence for the happy path, the three challenge scripts with the output lines to point at, and the on-camera troubleshooting table |
| `openapi/` | The four API contracts: `intake.yaml`, `quarantine.yaml`, `scanner.yaml`, `release.yaml` |

The report itself, the video links, the team contribution table, the individual
reflections and the AI-use declaration are still to be assembled. Keep the report PDF at
no more than 15 main pages excluding appendices and export the diagrams from
`architecture.md` into it.

Every claim in the report has to be traceable, so cite the evidence folder rather than
restating the result:

| Assignment item | Evidence |
|---|---|
| Challenge 1, concurrency control on a contended resource | [`../evidence/challenge-1-concurrency`](../evidence/challenge-1-concurrency) — `scripts/concurrency.py` |
| Challenge 2, idempotency | [`../evidence/challenge-2-idempotency`](../evidence/challenge-2-idempotency) — `scripts/idempotency.sh` |
| Challenge 3A, service-to-service resilience | [`../evidence/challenge-3a-resilience`](../evidence/challenge-3a-resilience) — `scripts/resilience.py` |
| Persistence across a restart | [`../evidence/tests`](../evidence/tests) — the four `*-persistence.txt` files |
| The whole transaction end to end | `../evidence/tests/e2e-run.txt` — `scripts/e2e.sh` |
| Test suite | `../evidence/tests` — Surefire reports for all four services |

Each service documents its own API, data design, configuration and limitations in
`../intake-service/README.md`, `../quarantine-service/README.md`,
`../scanner-service/README.md` and `../release-service/README.md`.
