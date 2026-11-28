// The part of jsdom the MIWG render spec uses to read a model's diagram (jsdom ships without types; it is already a dev dependency of the Vitest setup).
declare module 'jsdom' {
  export class JSDOM {
    constructor(source: string, options?: { contentType?: string });
    readonly window: { readonly document: Document };
  }
}
