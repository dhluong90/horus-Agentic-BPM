package io.github.dhluong90.bpm.expr.feel;

import java.math.BigDecimal;
import java.util.List;

/**
 * The FEEL syntax tree (DMN 1.5 grammar). Parser output: names, not slots; every node carries the offset of its first
 * character in the source, so an error reports line and column. Immutable, no host code, printable back to one normal
 * form by {@link FeelPrinter}. Owned by the parser stream (#197): adding a node is additive; changing one is a
 * contract change. The evaluator never mutates it, and the compiled profile lowers a subset of it to the slot-bound
 * {@code Expr} tree of the library.
 */
public sealed interface FeelNode {

    /** @return offset of the first character in the source, from 0 */
    int offset();

    /** Binary operators in precedence order of the grammar. */
    enum BinaryOp { OR, AND, EQ, NE, LT, LE, GT, GE, ADD, SUB, MUL, DIV, POW }

    /** @param value the number, read exactly, never through double */
    record NumberLiteral(int offset, BigDecimal value) implements FeelNode { }

    /** @param value the string, escapes resolved, no Unicode normalisation */
    record StringLiteral(int offset, String value) implements FeelNode { }

    /** @param value true or false */
    record BooleanLiteral(int offset, boolean value) implements FeelNode { }

    /** The literal {@code null}. */
    record NullLiteral(int offset) implements FeelNode { }

    /** @param text the text of {@code @"..."}, checked by the evaluator's temporal parsing, not by the parser */
    record TemporalLiteral(int offset, String text) implements FeelNode { }

    /** @param name a possibly multi-word name resolved against the scope (spaces collapsed to one) */
    record Name(int offset, String name) implements FeelNode { }

    /** @param source the value read from; @param name the member, or a context key, a date part or a range bound */
    record Path(int offset, FeelNode source, String name) implements FeelNode { }

    /** Arithmetic negation. */
    record Negate(int offset, FeelNode operand) implements FeelNode { }

    /** @param op the operator */
    record Binary(int offset, BinaryOp op, FeelNode left, FeelNode right) implements FeelNode { }

    /** {@code value between low and high}, bounds inclusive. */
    record Between(int offset, FeelNode value, FeelNode low, FeelNode high) implements FeelNode { }

    /** {@code value in tests}: a single test, a list of tests, or a list or range value. */
    record In(int offset, FeelNode value, List<FeelNode> tests) implements FeelNode {
        public In { tests = List.copyOf(tests); }
    }

    /** {@code value instance of type}. */
    record InstanceOf(int offset, FeelNode value, TypeNode type) implements FeelNode { }

    /** {@code if condition then a else b}. */
    record If(int offset, FeelNode condition, FeelNode then, FeelNode otherwise) implements FeelNode { }

    /** One {@code name in source} or {@code name in a..b} binding of {@code for}, {@code some} and {@code every}. */
    record Iteration(String variable, FeelNode source, FeelNode rangeEnd) { }

    /** {@code for i in xs return body}; {@code partial} gives the running list. */
    record For(int offset, List<Iteration> iterations, FeelNode body) implements FeelNode {
        public For { iterations = List.copyOf(iterations); }
    }

    /** {@code some}/{@code every} {@code ... satisfies test}. */
    record Quantified(int offset, boolean every, List<Iteration> iterations, FeelNode test) implements FeelNode {
        public Quantified { iterations = List.copyOf(iterations); }
    }

    /** {@code [a, b]}. */
    record ListLiteral(int offset, List<FeelNode> elements) implements FeelNode {
        public ListLiteral { elements = List.copyOf(elements); }
    }

    /** One context entry; the key is a name or a string. */
    record Entry(String key, FeelNode value) { }

    /** {@code {a: 1, "b c": 2}}; entries in source order, which is the evaluation and the output order. */
    record ContextLiteral(int offset, List<Entry> entries) implements FeelNode {
        public ContextLiteral { entries = List.copyOf(entries); }
    }

    /** A formal parameter with an optional declared type. */
    record Parameter(String name, TypeNode type) { }

    /** {@code function(a, b) body}; {@code external} is parsed so that import can refuse it by name. */
    record FunctionDefinition(int offset, List<Parameter> parameters, FeelNode body, boolean external)
            implements FeelNode {
        public FunctionDefinition { parameters = List.copyOf(parameters); }
    }

    /** An argument, positional when {@code name} is null. */
    record Argument(String name, FeelNode value) { }

    /** {@code f(1, 2)} or {@code f(a: 1)}. */
    record Invocation(int offset, FeelNode callee, List<Argument> arguments) implements FeelNode {
        public Invocation { arguments = List.copyOf(arguments); }
    }

    /** {@code list[predicate]}; a numeric predicate indexes, a boolean predicate filters ({@code item}). */
    record Filter(int offset, FeelNode source, FeelNode predicate) implements FeelNode { }

    /** {@code [a..b]}, {@code (a..b)}, {@code ]a..b[}: either bound may be open or absent. */
    record Range(int offset, boolean startIncluded, FeelNode start, FeelNode end, boolean endIncluded)
            implements FeelNode { }

    /** Unary test {@code -}: matches anything. */
    record AnyTest(int offset) implements FeelNode { }

    /** Unary test {@code < x}, {@code >= x}, {@code = x}, {@code != x}. */
    record ComparisonTest(int offset, BinaryOp op, FeelNode endpoint) implements FeelNode { }

    /** Unary tests {@code not(t1, t2)}. */
    record NotTest(int offset, List<FeelNode> tests) implements FeelNode {
        public NotTest { tests = List.copyOf(tests); }
    }

    /** A type in {@code instance of} and in declared parameters. */
    sealed interface TypeNode {
        /** @param name a qualified name such as {@code number}, {@code days and time duration} or an item definition */
        record Named(String name) implements TypeNode { }

        /** {@code list<T>}. */
        record ListOf(TypeNode element) implements TypeNode { }

        /** {@code context<a: T, ...>}. */
        record ContextOf(List<Parameter> fields) implements TypeNode { }

        /** {@code function<T1, T2> -> R}. */
        record FunctionOf(List<TypeNode> parameters, TypeNode result) implements TypeNode { }

        /** {@code range<T>}. */
        record RangeOf(TypeNode point) implements TypeNode { }
    }
}
