---
name: process-maps
description: Writing reference bank process maps and posting schemes (PROCESSES.md) the way a banking operations expert would - roles, controls, exceptions, GL postings, operational cycles and regulatory constraints. Use when documenting how a banking product really runs, defining posting schemes, or validating a design against bank practice.
---
# Process maps and posting schemes

## PROCESSES.md structure
1. **Process map** per process `P-nn`: trigger; steps with the role performing each (customer, agent, teller, maker, checker, operations, compliance); decision points; documents captured; controls (limits, maker-checker, dual control, reconciliation checks); exceptions and reversals; SLA.
2. **Posting schemes**: a table per financial event with debit account, credit account, amount basis and notes, including suspense and settlement accounts, fees and taxes, and the reversal entry.
3. **Operational cycles**: beginning of day, end of day, end of month, reconciliation with rails and switches, dormancy, statement cycles, holiday calendars, cut-off times.
4. **Regulatory and compliance constraints**: each named and classed as regulation (must), market practice (should) or one bank's habit (may).

## Standards
- Use BIAN service domains as shared vocabulary where it helps; do not force it.
- Show the unglamorous parts: reversals, partial failures, manual adjustments, audit requests.
- Every control is explicit; a process with no controls listed is incomplete.
- Concrete examples with real currencies, tiers and amounts.

## Validation findings
When reviewing a rule card, design or demo, report: blocking (a bank would reject it or a control is missing), should-fix, note, each with the operational reason.
