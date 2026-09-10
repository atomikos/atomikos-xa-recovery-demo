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

## Why the timeout matters

Atomikos' recovery thread deliberately refuses to force-complete a
`COMMITTING` transaction until *that transaction's own timeout* has
expired — it doesn't want to race a commit that might still be legitimately
in flight on another thread or node. `Phase1Fail` sets a 5-second timeout on
just this one transaction (`UserTransactionManager.setTransactionTimeout`)
so `Phase2Recover`'s recovery is prompt enough to watch; production
deployments would instead size this off their real `default_jta_timeout`.

## Two recovery paths, two ways of running them

There are actually two different "Atomikos recovers" stories here, and only
one of them fits inside a JUnit test:

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

- **Crash + restart recovery** (`Phase1Fail` / `Phase2Recover`): a genuine
  process crash and a second, independent JVM reading the leftover
  transaction log. This is the more dramatic (and more realistic) failure
  mode, but it fundamentally can't be a JUnit test — there's no way to
  `Runtime.getRuntime().halt()` the JVM a test is running in without also
  killing the test runner. It's a pair of `main()` methods instead; see
  "Running it" below.

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

To start over:

```bash
mvn -q exec:java -Dexec.mainClass=demo.Reset
```

## Layout

| File | Role |
|---|---|
| `src/main/java/demo/TmConfig.java` | Atomikos config shared by both phases — log directory and `tm_unique_name` must match across runs for recovery to find its own log |
| `src/main/java/demo/Resources.java` | Wraps each H2 `XADataSource` with `FaultInjectingJdbc.wrap(...)` before handing it to `AtomikosDataSourceBean` |
| `src/main/java/demo/Phase1Fail.java` | Runs the transfer, injects the fault, hard-kills the JVM the instant it fires |
| `src/main/java/demo/Phase2Recover.java` | Fresh JVM, no fault, watches Atomikos recover |
| `src/main/java/demo/Db.java` | Plain JDBC helpers for seeding/inspecting the two databases from outside the distributed transaction |
| `src/test/java/demo/FailBeforeCommitSelfHealsTest.java` | `@XaTest`/`@XaFault`-based JUnit 5 test for the same-JVM self-heal path |

## Add your own scenario

This demo covers one failure mode (fail-before-commit). XA has many more —
fail-after-prepare, a fault during recovery, a rollback path, a heuristic
outcome. **Fork this repo and add one**: use `Phase1Fail` / `Phase2Recover`
and `FailBeforeCommitSelfHealsTest` as templates, point j-xa-tester at a
different operation and phase, and send a pull request. See
[`CONTRIBUTING.md`](CONTRIBUTING.md).

Improvements that are purely generic and provider-neutral (a new adapter, a
core capability) belong upstream in
[j-xa-tester](https://github.com/rrobetti/j-xa-tester) itself, not here.

## Going to production?

Atomikos TransactionsEssentials (used here) is free and open source. When you
move to production — especially on Kubernetes or the cloud — you may want
shared-database recovery that survives replaced nodes (LogCloud), HA, and
commercial support with indemnification. That's
[Atomikos ExtremeTransactions](https://www.atomikos.com/) — start a
[free trial](https://www.atomikos.com/Main/ExtremeTransactionsFreeTrial).

## Credits & license

Built on [j-xa-tester](https://github.com/rrobetti/j-xa-tester) by Rogerio
Robetti. Licensed under [Apache-2.0](LICENSE).
