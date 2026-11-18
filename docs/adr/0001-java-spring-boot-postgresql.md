# ADR-0001: Java 21, Spring Boot starters, PostgreSQL

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
The engines are embedded libraries. The stack must match where adopters already are, keep the footprint small, and support long maintenance windows.

## Decision
Java 21 as the baseline with Java 25 in the CI matrix; framework-free `*-core` modules; Spring Boot 3.4+ starters for integration; PostgreSQL 14+ as the first and, for 0.x, only storage backend behind a storage SPI; Spring JDBC rather than JPA; Flyway for migrations.

## Options considered
1. Java and Spring Boot (chosen): largest overlap with target hosts; starters make one-dependency embedding possible.
2. Kotlin: pleasant, but adds a runtime to every host.
3. JPA/Hibernate for storage: hidden queries and entity lifecycle are liabilities inside a library; plain SQL keeps locking and schema prefixes explicit.
4. Database-agnostic from day one: doubles the test matrix before the design is proven; the SPI keeps it possible later.

## Consequences
Hosts outside Spring need manual wiring (documented later). Multi-database support is a later milestone. Core modules stay auditable and small.
