# Release Notes

What shipped in each release. The project has one version for every module, and one tag per release, `vX.Y.Z` (PD-087); see [[Technical Stack]] under Delivery.

| Release | Tag | Date | Modules | Notes |
|---|---|---|---|---|
| 0.1.0 | `v0.1.0` | 22 Nov 2026 (planned) | The modules pinned by `bpm-bom` on the tagged commit, all at `0.1.0`: today `bpm-host-metadata`, `rule-engine-core`, `rule-engine-postgres`, `rule-engine-rest`, `rule-engine-spring-boot-starter`, `cockpit-spring-boot-starter`, and `bpm-bom` itself | First release, published as a GitHub Release with its bill of materials and checksums |
| 0.2.0 | `v0.2.0` | 15 Jan 2027 (planned) | As 0.1.0, at `0.2.0` | **Upgrade note (PD-104): finish a rolling upgrade before relying on approval acknowledgements.** A replica still on 0.1.x approves without asking for an acknowledgement and writes no `acknowledged_warnings` or `masked_by_default_inputs`, so until every replica runs 0.2.0 an approval can reach the decision log without the acknowledgement on the record. Migrations: `rle_setting_seen` and the two acknowledgement columns on `rle_version_history` (numbered at merge, after V13) |

Every release note lists the database migrations it adds (TR-OPS-01) and the "should" requirements it meets ([[Technical Requirements]]).
