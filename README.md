# inject fail-before-commit → watch Atomikos recover

[![Java CI with Maven](https://github.com/atomikos/atomikos-xa-recovery-demo/actions/workflows/maven.yml/badge.svg)](https://github.com/atomikos/atomikos-xa-recovery-demo/actions/workflows/maven.yml)

A runnable demo built on [j-xa-tester](https://github.com/rrobetti/j-xa-tester):
a two-phase-commit money transfer across two H2 databases, coordinated by
[Atomikos](https://www.atomikos.com/). j-xa-tester wraps one of the two
enlisted `XAResource`s so a one-shot fault fires the instant Atomikos calls
`commit()` on it — then the process is hard-killed, leaving a genuinely
in-doubt transaction behind. A second, independent JVM run shows Atomikos
resolve it automatically on startup, with no application code involved.

## What this proves

- **Atomicity survives a crash.** No half-applied state is left behind — the
  transfer is either fully absent or fully applied, never one side only.
- **Recovery is automatic on restart.** Atomikos rescans its own log and
  completes the in-doubt transaction by itself — no application code, no
  manual intervention.
- **Against a real XA resource and a real injected fault** (via
  [j-xa-tester](https://github.com/rrobetti/j-xa-tester)), not a mock.
- **On the free, open-source edition** — Atomikos TransactionsEssentials.
  You can reproduce every line of this yourself.

Everything here is **spec-level** — behaviour any conformant JTA/XA
transaction manager must exhibit, demonstrated with Atomikos. Atomikos-specific
production hardening (self-healing recovery, shared-database recovery, HA) is
intentionally out of scope here — see **Going to production?** at the end.

## The scenario

"alice pays rent": `UPDATE accounts SET balance = balance - 200` in
`account-db`, plus an audit row `INSERT INTO ledger_entries ...` in
`ledger-db`, both inside one Atomikos global transaction.

1. **Phase 1** — both resources vote yes at prepare. The instant Atomikos
   calls `commit()` on `account-db`, j-xa-tester throws `XAER_RMFAIL`
   (`FaultInjectingXAResource`, one-shot rule). Left alone, Atomikos would
   quietly retry that same call a few seconds later and self-heal — so
   instead, the moment the failure is recorded, this process is
   `Runtime.getRuntime().halt()`ed: no graceful Atomikos shutdown, no
   further commit attempts. `account-db` is left at its pre-transfer
   balance; `ledger-db`'s commit was never even attempted. Atomikos' own
   transaction log durably records the transaction as `COMMITTING`.
2. **Phase 2** — a fresh JVM, pointed at the same Atomikos log directory
   and the same two resource names, but with no fault injected this time
   (the "outage" is over). Simply starting the transaction manager makes it
   rescan its log, find the leftover `COMMITTING` transaction, and — once
   that transaction's own timeout has elapsed — recommit both resources on
   its own. Watch the balance and ledger count converge in the console
   output.

## A second scenario: presumed abort (rollback by recovery)

`Phase1RollbackFail` / `Phase2RollbackRecover` run the same transfer, but
inject the fault one phase earlier: `ledger-db` is rigged to throw
`XAER_RMFAIL` the instant Atomikos calls `prepare()` on it — i.e. it votes
NO. `account-db` (enlisted first) has already voted YES and is left holding
a prepared-but-unresolved branch, and the process is hard-killed before
Atomikos gets a chance to roll that branch back on its own.

This is a genuinely different recovery path from Phase 1/2's, not just a
smaller version of it: because not every participant voted YES, Atomikos'
coordinator never reaches its recoverable "in-doubt" state and so never
writes anything durable for this transaction to its own log — per the
XA/JTA spec's *presumed abort* rule, the absence of a commit decision on
record is itself sufficient reason to roll back. On restart, Atomikos scans
`account-db` via `XAResource.recover()`, finds a prepared branch matching no
committing decision in its own log, and rolls it back — with no application
code involved.

`Phase2RollbackRecover` also demonstrates a side effect of that dangling
branch you won't see from the balance alone: since a prepared-but-uncommitted
update was never visible to other connections anyway, `account-db`'s row
already *looks* untouched before recovery runs. What actually changes is
that `account-db` is still holding a write lock on alice's row for as long
as the branch stays unresolved — the demo proves this with a plain write
that blocks (lock timeout) before recovery and succeeds the instant
Atomikos rolls the branch back.

Run it the same way:

```bash
mvn -q exec:java -Dexec.mainClass=demo.Phase1RollbackFail
mvn -q exec:java -Dexec.mainClass=demo.Phase2RollbackRecover
```

It uses its own data directory (`./data-rollback`) and Atomikos
`tm_unique_name`, so it never interferes with the fail-before-commit
scenario above — `mvn -q exec:java -Dexec.mainClass=demo.Reset` wipes both.

## Keeping the demo quick

An in-doubt transaction is recovered after its timeout has elapsed, so
`Phase1Fail` sets a short 5-second timeout on just this one transaction
(`UserTransactionManager.setTransactionTimeout`) — enough for
`Phase2Recover`'s recovery to be prompt to watch. Production deployments would
size this off their real `default_jta_timeout`.

`RollbackTmConfig` shortens the recovery timeouts so the rollback is prompt
to watch; production would use its real values.

## Recovery paths, and how each one is run

There are four different "Atomikos recovers" stories here, and two of them
fit inside a JUnit test:

- **Self-heal, no crash** (`FailBeforeCommitSelfHealsTest`): if the process
  is never killed, Atomikos retries a failed commit call on its own, inside
  the very same `commit()` invocation, a few seconds later — the caller
  never sees the failure. This is a single-JVM, single-method scenario, so
  it's written as an ordinary JUnit 5 test using j-xa-tester's own
  `xa-tester-junit5` extension: `@XaTest` provisions a fresh
  `XaScenarioEngine` per test (injectable as a parameter), and `@XaFault`
  declares the one-shot commit failure declaratively instead of building an
  `XaRule` by hand. The extension fails the test in teardown if the fault
  never fires, so a fault that silently didn't trigger can't masquerade as
  a pass. Run it with:

  ```bash
  mvn test
  ```

- **Self-rollback, no crash** (`FailAtPrepareRollsBackAllTest`): the
  single-JVM analog of the presumed-abort scenario. Same fault as
  `Phase1RollbackFail` (ledger-db throws `XAER_RMFAIL` at prepare), but with
  no crash: Atomikos rolls back account-db's already-prepared branch
  synchronously, inside the same `tm.commit()` call, and the caller sees a
  `jakarta.transaction.RollbackException`. This is the ordinary, spec-level
  "a resource fails at prepare → all roll back" behavior — recovery-driven
  presumed abort only enters the picture once a crash prevents that
  in-process rollback from happening, which is what `Phase1RollbackFail` /
  `Phase2RollbackRecover` demonstrate. Also runs with `mvn test`.

- **Crash + restart recovery, commit path** (`Phase1Fail` / `Phase2Recover`):
  a genuine process crash and a second, independent JVM reading the leftover
  transaction log, recovering by *committing* an in-doubt transaction. This
  fundamentally can't be a JUnit test — there's no way to
  `Runtime.getRuntime().halt()` the JVM a test is running in without also
  killing the test runner. It's a pair of `main()` methods instead; see
  "Running it" below.

- **Crash + restart recovery, rollback path** (`Phase1RollbackFail` /
  `Phase2RollbackRecover`): same shape, but recovering by *rolling back* an
  in-doubt transaction — "presumed abort". See "A second scenario" above.

## Prerequisites

- JDK 17+
- Maven 3.9+

[j-xa-tester](https://github.com/rrobetti/j-xa-tester) (and its own
dependency, [j-api-proxy](https://github.com/rrobetti/j-api-proxy)) is
`0.1.0-alpha` and published to Maven Central under `io.github.rrobetti`, so
`mvn` resolves it like any other dependency — no local build step needed.

## Running it

```bash
mvn -q exec:java -Dexec.mainClass=demo.Phase1Fail
```

This prints the transfer, the injected failure, a timeline of every XA call
j-xa-tester recorded, and then halts the JVM (non-zero exit — that's the
simulated crash, not a build failure).

```bash
mvn -q exec:java -Dexec.mainClass=demo.Phase2Recover
```

This starts a brand-new transaction manager against the same
`./data/tmlogs`, polls the two databases for up to 20 seconds, and prints
each poll so you can watch the balance and ledger count go from
inconsistent to consistent once Atomikos' recovery thread commits
`account-db`.

To start over (wipes both scenarios' data directories):

```bash
mvn -q exec:java -Dexec.mainClass=demo.Reset
```

## Layout

| File | Role |
|---|---|
| `src/main/java/demo/TmConfig.java` | Atomikos config for the commit-path scenario — log directory and `tm_unique_name` must match across runs for recovery to find its own log |
| `src/main/java/demo/Resources.java` | Wraps each H2 `XADataSource` with `FaultInjectingJdbc.wrap(...)` before handing it to `AtomikosDataSourceBean` — shared by both scenarios |
| `src/main/java/demo/Phase1Fail.java` | Runs the transfer, injects a fail-before-commit fault, hard-kills the JVM the instant it fires |
| `src/main/java/demo/Phase2Recover.java` | Fresh JVM, no fault, watches Atomikos recover by committing |
| `src/main/java/demo/Db.java` | Plain JDBC helpers for seeding/inspecting the commit-path scenario's two databases |
| `src/main/java/demo/RollbackTmConfig.java` | Atomikos config for the presumed-abort scenario — its own data directory and `tm_unique_name`, independent of `TmConfig` |
| `src/main/java/demo/Phase1RollbackFail.java` | Runs the transfer, injects a fail-at-prepare fault on `ledger-db`, hard-kills the JVM the instant it fires |
| `src/main/java/demo/Phase2RollbackRecover.java` | Fresh JVM, no fault, watches Atomikos recover by rolling back (presumed abort) |
| `src/main/java/demo/RollbackDb.java` | Plain JDBC helpers for the presumed-abort scenario, plus the lock-probe that proves the dangling branch is still held |
| `src/test/java/demo/FailBeforeCommitSelfHealsTest.java` | `@XaTest`/`@XaFault`-based JUnit 5 test for the same-JVM self-heal path |
| `src/test/java/demo/FailAtPrepareRollsBackAllTest.java` | `@XaTest`/`@XaFault`-based JUnit 5 test for the same-JVM self-rollback path |

## Add your own scenario

This demo covers two failure modes so far (fail-before-commit, and
presumed-abort rollback on a failed prepare). XA has more — fail-after-
prepare, a fault during recovery, a heuristic outcome. **Fork this repo and
add one**: use `Phase1Fail`/`Phase2Recover`, `Phase1RollbackFail`/
`Phase2RollbackRecover`, `FailBeforeCommitSelfHealsTest`, and
`FailAtPrepareRollsBackAllTest` as templates, point j-xa-tester at a
different operation and phase, and send a pull request. See
[`CONTRIBUTING.md`](CONTRIBUTING.md).

Improvements that are purely generic and provider-neutral (a new adapter, a
core capability) belong upstream in
[j-xa-tester](https://github.com/rrobetti/j-xa-tester) itself, not here.

## Going to production?

You've just watched Atomikos TransactionsEssentials (free, open source)
recover a crashed XA transaction on its own. Going live is where that
recovery has to hold under real conditions — replaced nodes, no local disk,
HA, an SLA. Atomikos ExtremeTransactions de-risks that step: shared-database
recovery that survives replaced Kubernetes/cloud nodes (LogCloud), HA, and
commercial support with indemnification. Prove it against your own setup with
a [free trial](https://www.atomikos.com/Main/ExtremeTransactionsFreeTrial).

## Credits & license

Built on [j-xa-tester](https://github.com/rrobetti/j-xa-tester) by Rogerio
Robetti. Licensed under [Apache-2.0](LICENSE).
