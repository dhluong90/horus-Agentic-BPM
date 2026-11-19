/**
 * DMN 1.5 decision tables to and from schema version 1 rule sets: a hardened XML reader, the FEEL subset of table
 * cells, and the translation to and from {@code horus-expr-v1} cells. A DMN import is stored as the lowest schema
 * version that can express it (TR-STD-03) and goes through the same checks, hash and four-eyes approval as YAML.
 */
package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;
