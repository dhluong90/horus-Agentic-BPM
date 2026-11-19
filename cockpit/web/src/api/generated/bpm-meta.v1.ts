// Generated from cockpit/api/schema/bpm-meta.v1.schema.json by scripts/generate-schemas.mjs. Do not edit.
// Regenerate with: npm run schemas
import { z } from "zod"

export const bpmMetaV1 = z.object({ "schemaVersion": z.literal(1), "hostId": z.string().max(40).optional(), "modules": z.array(z.object({ "module": z.enum(["rules","processes"]), "engineVersion": z.string().regex(new RegExp("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$")), "apiVersion": z.string().regex(new RegExp("^v[0-9]+$")), "features": z.array(z.string().regex(new RegExp("^[a-z]+(\\.[a-z0-9-]+)+$"))).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!"), "standards": z.object({ "bpmnStage": z.enum(["S0","S1","S2","S3"]).optional(), "bpmnExecutableElements": z.array(z.string().max(60)).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!").optional(), "dmnVersion": z.string().regex(new RegExp("^1\\.[0-9]+$")).optional(), "dmnConformanceLevel": z.union([z.literal(1), z.literal(2), z.literal(3)]).optional(), "expressionLanguages": z.array(z.string().max(100)).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!").optional() }).catchall(z.unknown()).optional(), "settings": z.record(z.string(), z.union([z.boolean(), z.number().int(), z.string()])).optional() }).catchall(z.unknown())).min(1), "tenancy": z.object({ "switching": z.boolean() }), "limits": z.object({ "maxPageSize": z.number().int().lte(200) }) }).catchall(z.unknown())
export type BpmMetaV1 = z.infer<typeof bpmMetaV1>

/** Schema rules the generator cannot express; each is a hand-written, tested refinement. */
export const bpmMetaV1Refinements = [] as const;
