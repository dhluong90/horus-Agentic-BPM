export interface ChipExemption { selector: string; why: string }
export interface ChipFound { element: string; text: string; radius: number }
export const CHIP_EXEMPT: readonly ChipExemption[];
export function detectChips(exempt: readonly ChipExemption[]): ChipFound[];
