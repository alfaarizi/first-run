# ADR-012: JSpecify annotations enforced by NullAway

Date: 2026-07-06

## Status

Accepted

## Context

The server threads untrusted input through eleven modules, so a
`NullPointerException` is a real failure mode worth ruling out at compile time
rather than in production. Spring Framework 7 declares its own nullness with
JSpecify annotations, so the type information already exists at the framework
boundary, and the question is whether we declare and enforce it in our code too.

## Decision

Adopt the stack Spring itself uses. Every package is `@NullMarked` through its
`package-info.java`, making non-null the default, and genuinely optional values
carry `@Nullable`. NullAway, an Error Prone plugin, enforces the annotations at
compile time, scoped to `com.firstrunhq` and failing the build across main and
test sources, so the check rides `./mvnw verify` and gates CI for every editor.
NullAway's experimental JSpecify mode stays off because the stable
`AnnotatedPackages` mechanism already honors `@NullMarked`, and the editor's own
JDT null analysis is turned off so the build stays the one authority.

## Consequences

A whole category of bug becomes a compile error, and the nullness of every
signature is documented in a type IntelliJ and Kotlin read natively. The cost is
a sub-10% build-time overhead from Error Prone, the `--add-exports` flags in
`.mvn/jvm.config` the compiler needs on JDK 17+, and the discipline of marking
new nullable values as they appear.

The rejected alternatives were:

- Eclipse JDT IDE null analysis: editor-only and pessimistic at library
  boundaries, so it produces noise without reaching CI.
- Checker Framework: sound, but its build cost and annotation burden outweigh a
  solo ten-week build, where NullAway covers the NPE case for less.
- Spring's deprecated `org.springframework.lang` annotations: superseded by
  JSpecify in Spring Framework 7, a dead path to start on.
- Main-sources-only enforcement (Spring's and Guava's choice): a test is code
  too, and NullAway's assertion handling makes checking tests cheap.
