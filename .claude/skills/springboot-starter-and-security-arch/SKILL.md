---
name: springboot-starter-and-security-arch
description: "Architect Java 21 / Spring Boot 3.4+ multi-module starters and BOMs (@AutoConfiguration, host DataSource & TransactionManager participation, isolated Flyway migrations), Keycloak OIDC multi-tenant resolvers, service-layer Maker-Checker enforcement, keyed-hash PII masking, and STRIDE / OWASP ASVS Level 2 THREAT-MODEL.md documents. Use when designing Spring Boot starters, multi-tenant security, or threat models. Adapted from affaan-m/ECC (springboot-patterns, springboot-security, java-coding-standards)."
---

# Spring Boot Starter & Security Architecture

Design zero-friction Spring Boot 3.4+ embeddable starters and bank-grade multi-tenant security controls.

## When to Use
- Designing `*-spring-boot-starter`, `cockpit-spring-boot-starter`, and `bpm-bom` in `horus-Agentic-BPM`.
- Designing OIDC/Keycloak integration, RBAC permissions, maker-checker enforcement, PII masking, or `THREAT-MODEL.md` for any Banking Accelerator.

---

## 1. Spring Boot 3.4+ Embeddable Starter Architecture (`TR-EMB-01..04`)

### Auto-Configuration Rules (No Component-Scan Pollution)
1. **Explicit `AutoConfiguration.imports`:** Register configurations in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` using `@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})`. NEVER rely on `@ComponentScan` inside a library starter.
2. **Host `DataSource` & `TransactionManager` Participation:**
   - Bind only to the host's `DataSource` / `JdbcClient` / `PlatformTransactionManager` (`@ConditionalOnBean(DataSource.class)`). Never instantiate a private `HikariDataSource`.
   - Use `@ConditionalOnMissingBean` on SPI beans (`TenantResolver`, `CallerResolver`, `BusinessCalendar`, `RuleFunctionRegistry`, `Clock`) so hosts can override cleanly.
3. **Isolated Flyway Execution (`rle_schema_history`, `pre_schema_history`):**
   - Run engine migrations via a dedicated `Flyway.configure().dataSource(hostDataSource).table("rle_schema_history").baselineOnMigrate(true)` initializer bean (`SmartInitializingSingleton` or `FlywayMigrationStrategy` hook) so engine migrations never collide with the host application's `flyway_schema_history`!
4. **BOM & Dependency Hygiene (`TR-LIC-01`):**
   - Publish `bpm-bom` (`<packaging>pom</packaging>` with `<dependencyManagement>`) pinning all module versions.
   - Enforce Maven Enforcer + License plugin allowing Apache 2.0, MIT, BSD families only; generate CycloneDX SBOM on every release (`TR-SEC-06`).

---

## 2. Java 21 Coding Standards for Financial & Engine Code
- **Constructor injection only** (no field `@Autowired`).
- **`record` & `sealed interface`** for immutable commands, AST nodes, events, and DTOs.
- **Boundary validation:** Use Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Size`) on REST inputs; never expose internal entities directly in `@RestController` responses.
- **No secrets in code, YAML, or logs (`TR-SEC`):** Credentials, HMAC masking peppers, and OIDC secrets come from environment variables / Vault placeholders only.

---

## 3. Banking Security Controls (OWASP ASVS Level 2 Baseline)

### A. Multi-Tenancy (`TR-SEC-03`, `TR-TEN-01`)
- Derive `tenantId` exclusively from `TenantResolver` backed by Spring Security `JwtAuthenticationToken` claim (e.g. `tenant_id` or Keycloak realm) or a trusted gateway header when explicitly configured.
- Ignore or reject any `tenantId` supplied inside JSON request bodies.

### B. Service-Layer Maker-Checker (`TR-SEC-04`)
- Enforce separation of duties inside the **application/domain service** (`VersionService`, `AdjustmentService`, `TaskService`), NOT only by disabling UI buttons:
  ```java
  if (requesterId.equals(approverId)) {
    throw new MakerCheckerViolationException("Approver cannot be the same identity as requester");
  }
  ```
- Also require a distinct approval permission (e.g., `rules:publish:approve` vs `rules:publish:request`) checked via `@PreAuthorize`.
- Reject replay or double-approval using optimistic locking (`version`) + status transition guard (`PENDING_APPROVAL` → `PUBLISHED`).

### C. Keyed-Hash PII Masking (`TR-SEC-05`)
- Inputs marked `mask: true` in rule definitions or logs MUST be hashed before persistence using **HMAC-SHA256** with a host-configured secret key (`horus.bpm.masking.hmac-key` from env/Vault) — never plain unsalted SHA-256 (vulnerable to rainbow tables on national IDs/phone numbers) and never raw plaintext in logs or Cockpit responses.

### D. Static Cockpit & Security Headers (`TR-SEC-07`)
- Serve Cockpit assets under host authentication (`horus.bpm.cockpit.path`), with strict `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` on API responses, and explicit CORS allowlist when hosted standalone (`ADR-0005`).

---

## 4. Writing `THREAT-MODEL.md` (STRIDE + Exploit Scenarios)
Every Banking Accelerator `THREAT-MODEL.md` must map threats across:
- **Spoofing / Tenancy Escape** (forged tenant header or JWT claim missing validation)
- **Tampering** (expression injection / SpEL gadget, balance race condition, approval replay)
- **Repudiation** (missing immutable audit log, mutable decision history)
- **Information Disclosure** (PII in decision logs, stack trace leaks, cross-tenant query bug)
- **Denial of Service** (ReDoS regex in rule definitions, slow `agentTask` exhausting DB pool)
- **Elevation of Privilege** (self-approval bypassing UI, missing `@PreAuthorize` on `:approve`)
Each finding requires: *Asset → Threat/Exploit Scenario → Control Implemented → Verification Test*.

## HORUS Program Integration
- Used by `program-architect` and `security-reviewer` when designing `*-spring-boot-starter` and authoring `<kind>/<name>/docs/THREAT-MODEL.md`.
- Aligns with `horus-Agentic-BPM/wiki/Multi-Tenancy-and-Security.md` and `TR-EMB`, `TR-SEC`, `TR-LIC`.
