// Generated from cockpit/api/schema/bpm-me.v1.schema.json by scripts/generate-schemas.mjs. Do not edit.
// Regenerate with: npm run schemas
import { z } from "zod"

export const bpmMeV1 = z.object({ "schemaVersion": z.literal(1), "user": z.object({ "id": z.string().min(1), "displayName": z.string().optional() }), "tenant": z.object({ "current": z.string().min(1), "allowed": z.array(z.string()).min(1).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!") }), "permissions": z.array(z.string().regex(new RegExp("^(rules|processes)(:[a-z]+(-[a-z]+)*){1,2}$"))).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!"), "roles": z.array(z.string()).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!").optional(), "groups": z.array(z.string()).refine((arr) => arr.every((item, i) => arr.indexOf(item) == i), "All items must be unique!").optional() }).catchall(z.unknown())
export type BpmMeV1 = z.infer<typeof bpmMeV1>

/** Schema rules the generator cannot express; each is a hand-written, tested refinement. */
export const bpmMeV1Refinements = [] as const;
