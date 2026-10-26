# Submission documents

The existing implementation review and ordered next steps are in
[submission-checklist.md](submission-checklist.md). That review applies to
`/Users/amrurama/Desktop/assignment`, not to this scaffold.

Add architecture and sequence diagrams, ER diagrams per service, API examples and
the final report here as work proceeds. Export
diagrams into the report PDF, which must contain no more than 15 main pages
excluding appendices. Add the video and Google Drive links and complete the team
contribution table, individual reflections and AI-use declaration.
[business-rules.md](business-rules.md) already maps BR1 to BR4 to the code and the
tests that prove them.

All four services declare their business APIs in `openapi/intake.yaml`,
`openapi/quarantine.yaml`, `openapi/scanner.yaml` and `openapi/release.yaml`. Each
service's implementation, rule traceability, ER diagram, testing and limitations
are documented in its
README: `../intake-service/README.md`, `../quarantine-service/README.md`,
`../scanner-service/README.md` and
`../release-service/README.md`.
