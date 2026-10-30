# Pre-submission checklist

The assignment's own list, checked against this repository. Each box names the artifact
that proves it, so a marker - or a teammate the night before the deadline - can confirm
it without re-deriving the project. Anything not verifiable from the repo is marked as
outstanding rather than ticked.

| # | Requirement | State | Proof |
|---|---|---|---|
| 1 | Fresh clone runs by following the README only | PASS | `evidence/tests/fresh-clone-run.txt` - a clean clone of the pushed branch, built and driven end to end with no instruction other than `README.md` |
| 2 | At least 4 services, each with its own database and no cross-database access | PASS | four `application.yml` files, four file-based H2 URLs and credentials; `docs/architecture.md` data-ownership section; no source file references another service's tables |
| 3 | Every service has `/health`, an OpenAPI spec and environment-based configuration | PASS | `bash scripts/health.sh`; `docs/openapi/{intake,quarantine,scanner,release}.yaml`; four `.env.example` files |
| 4 | At least 4 business rules documented, each mapped to code and a test | PASS | `docs/business-rules.md`, BR1-BR4, each with the enforcing method and named tests |
| 5 | Challenges 1, 2 and the chosen 3 each have a script, output and explanation | PASS | `scripts/concurrency.py` + `reclaim.py`, `scripts/idempotency.sh`, `scripts/resilience.py` (with the Part E bonus) against `evidence/challenge-1-concurrency/`, `challenge-2-idempotency/`, `challenge-3a-resilience/` |
| 6 | Evidence includes at least one failure case and its handling | PASS | stopped Scanner and the `FAILED` settle; `SIGKILL` mid-scan and the reclaim sweep; stopped Quarantine and the release `503`; plus the 400/404/405/409/415/500 matrices in the HTTP tests |
| 7 | Report diagrams are readable and include a sequence diagram with a failure path | PASS | `docs/architecture.md`: component, happy-path sequence, five-block failure sequence, release-verification sequence, state machine, four ER diagrams - all Mermaid source, exported into the PDF |
| 8 | Every member has commits, a contribution entry and a reflection | OUTSTANDING | commit history exists (`git shortlog -sne`); `docs/report-draft.md` appendices A and B are skeletons for the team to fill in |
| 9 | AI-use declaration included | OUTSTANDING | appendix C of `docs/report-draft.md` is the table to complete; it must describe what was actually used and for what |
| 10 | Video is 8-10 minutes and shows live execution | OUTSTANDING | `docs/demo-runbook.md` is the script and timing plan; the recording and its link (report section 10) still have to happen |

## Checks that are ours, not the assignment's

Run these before pushing anything; all of them exit non-zero when an invariant breaks, so
a green run is a test result and not a transcript.

```bash
export JAVA_HOME=$(/usr/libexec/java_home)   # or any JDK 17+
mvn -B -ntp clean package                    # 184 tests; check for zero failures
bash scripts/health.sh                       # with the four services up
bash scripts/e2e.sh                          # 32 asserted steps
python3 scripts/concurrency.py
python3 scripts/reclaim.py
python3 scripts/resilience.py
python3 scripts/verification.py
python3 scripts/persistence.py
python3 scripts/quarantine-persistence.py
python3 scripts/scanner-persistence.py
python3 scripts/release-persistence.py
```

Structural checks worth repeating after any documentation change:

* every `#/components/...` reference in the four OpenAPI files resolves, and every path
  in a spec matches a mapping on its controller;
* every relative link in the Markdown resolves (`docs/`, the service READMEs and the
  evidence folders);
* the counts quoted in `README.md`, the runbook and `docs/report-draft.md` still match
  `mvn clean package`, which is where they drift first.

## Known gaps to state in the report, not hide

These are listed in `docs/report-draft.md` section 9 and in the service READMEs:

* no authentication or authorisation, so a service is trusted as the owner of its state -
  the verification edge closes the practical hole without pretending to close this one;
* synchronous scans and releases that sleep on the request thread during retries;
* `FAILED` is final and `RELEASED` is terminal, with no operator workflow for either;
* H2 in embedded file mode means one process per database, which Assignment 2's
  containerisation will have to address;
* a Scanner process killed mid-scan still leaves its attempt row in progress - the reclaim
  sweep exists in Quarantine, not in Scanner, which is a teammate-owned module.
