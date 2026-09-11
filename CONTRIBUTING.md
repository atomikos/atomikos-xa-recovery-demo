# Contributing

Thanks for your interest! This is a small, focused demo: it shows Atomikos
recovering an in-doubt XA transaction after a fault injected with
[j-xa-tester](https://github.com/rrobetti/j-xa-tester). The most valuable
contribution is **a new failure scenario**.

## Scope: spec-level scenarios only

This repo accepts only **generic, spec-level** scenarios — behaviours any
conformant JTA/XA transaction manager must exhibit. It is a showcase, not a
transaction-manager conformance or hardening suite.

**The test to apply:** *could you derive this scenario purely from the
JTA/XA specifications plus a textbook, without knowing anything
implementation-specific?* If yes, it is in scope. If it depends on a
provider-specific quirk, an implementation-specific timing or race window, or
any behaviour the specs leave open, it is **out of scope here**.

- **In scope** — commit fails after all resources voted to commit → the TM
  recovers to commit; a resource fails at prepare → all roll back; `recover()`
  returns in-doubt Xids → the TM resolves them; a heuristic outcome is
  reported.
- **Out of scope** — provider-specific driver/broker behaviour;
  implementation-specific timing or race windows; heuristic-resolution
  nuances or anything requiring knowledge beyond the JTA/XA specs. Genuinely
  generic, provider-neutral capabilities belong upstream in
  [j-xa-tester](https://github.com/rrobetti/j-xa-tester); pull requests that
  go beyond spec-level behaviour here will be declined.

Pull requests are reviewed against this rule.

## Add a scenario

XA can fail in many ways. This repo demonstrates one (fail-before-commit).
Good additions include:

- fail-*after*-prepare,
- a fault during **recovery** (fail the first recovery commit, then let it
  succeed),
- a **rollback** path (fault on `rollback`),
- a **heuristic** outcome.

To add one:

1. **Fork** this repository.
2. Use the existing classes as templates:
   - `src/main/java/demo/Phase1Fail.java` / `Phase2Recover.java` for a
     crash + restart scenario (a pair of `main()` methods), or
   - `src/test/java/demo/FailBeforeCommitSelfHealsTest.java` for a
     single-JVM scenario expressible as a JUnit 5 test.
3. Point j-xa-tester at a different operation and phase (see its
   `XaRules` / `@XaFault`), keep the narration clear, and make sure the
   injected fault actually fires (the JUnit 5 extension checks this for you).
4. Add a short entry to the README describing what your scenario shows.
5. Open a **pull request**.

## Where things belong

- **Atomikos-specific scenarios and demo improvements** → here.
- **Generic, provider-neutral capabilities** (a new adapter, a core
  feature of the fault-injection engine itself) → upstream in
  [j-xa-tester](https://github.com/rrobetti/j-xa-tester).

## Ground rules

- Keep it runnable on the **free, open-source** Atomikos
  TransactionsEssentials and an embedded database (H2), so anyone can
  reproduce it with `mvn`.
- JDK 17+, Maven. Keep dependencies minimal.
- By contributing, you agree your contribution is licensed under
  [Apache-2.0](LICENSE).

Found a case where Atomikos does **not** recover correctly? That's a great
contribution too — open an issue with the scenario, and we'll look at it.
