package io.github.dhluong90.bpm.expr.internal;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.EvaluationProfile;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * {@code urn:horus-bpm:expr:v1} through the {@link ExpressionLanguage} SPI: the same parser and evaluator the Rule
 * Engine compiles decision tables with.
 * <ul>
 *   <li>A {@linkplain TypeEnvironment#declared(Map) declared} environment allows its names only; an unknown name is a
 *       compile error.</li>
 *   <li>An {@linkplain TypeEnvironment#untyped() untyped} environment allows any name and dotted path, read from the
 *       context at evaluation; values of the wrong type for an operator then fail ({@link Evaluator#strict()}), so a
 *       condition never silently does not hold. A three-letter upper-case word after a number is a currency
 *       ({@code 50000 VND}), never a name.</li>
 * </ul>
 * {@code today()} is the context clock's date in the zone given here (UTC by default); {@code now()} is the clock.
 * Without a clock either one fails. Immutable and thread-safe.
 */
public final class HorusExprV1 implements ExpressionLanguage {

    /** The language id. */
    public static final String ID = "urn:horus-bpm:expr:v1";

    /**
     * The id of the FEEL dialect, the OMG namespace of DMN 1.2 FEEL that BPMN files carry as {@code language}; the
     * other DMN versions' FEEL URIs and {@code feel} are aliases of it, resolved by the language factory.
     */
    public static final String FEEL_ID = "https://www.omg.org/spec/DMN/20191111/FEEL/";

    private final Evaluator evaluator;
    private final Evaluator strictEvaluator;
    private final ZoneId zone;
    private final boolean feel;

    /** Half-even money rounding, the default string bound and regex limits, {@code today()} in UTC. */
    public HorusExprV1() {
        this(new Evaluator(RoundingMode.HALF_EVEN), ZoneOffset.UTC);
    }

    /**
     * @param evaluator the evaluator, with the host's rounding and bounds
     * @param zone the zone of {@code today()}
     */
    public HorusExprV1(Evaluator evaluator, ZoneId zone) {
        this(evaluator, zone, false);
    }

    /**
     * The FEEL dialect of a condition or a script (see {@link Parser#parseFeelWithPaths}): the same tree, the same
     * evaluator and the same null, number and money rules, with FEEL's spelling.
     *
     * @param evaluator the evaluator, with the host's rounding and bounds
     * @param zone the zone of {@code today()}
     * @return the language, with the id {@link #FEEL_ID}
     */
    public static HorusExprV1 feel(Evaluator evaluator, ZoneId zone) {
        return new HorusExprV1(evaluator, zone, true);
    }

    private HorusExprV1(Evaluator evaluator, ZoneId zone, boolean feel) {
        this.feel = feel;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.strictEvaluator = evaluator.strict();
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    @Override
    public String id() {
        return feel ? FEEL_ID : ID;
    }

    @Override
    public CompiledExpression compile(String source, TypeEnvironment types) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(types, "types must not be null");
        if (source.isBlank()) {
            throw new ExpressionInvalidException("An expression must not be blank", 0);
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        if (types.isDeclared()) {
            types.names().keySet().forEach(name -> {
                slots.put(name, names.size());
                names.add(name);
            });
        }
        // The clock slots are resolved after the names, so they never collide with one.
        String today = Expr.ClockFunction.TODAY.slotName();
        String now = Expr.ClockFunction.NOW.slotName();
        Expr tree;
        try {
            if (types.isDeclared()) {
                if (feel) {
                    throw new ExpressionInvalidException("FEEL is read over process variables only; a rule set"
                            + " declares its inputs and uses horus-expr-v1 or DMN tables", 0);
                }
                tree = Parser.parse(source, name -> clockSlot(name, today, now, names.size(), slots));
            } else {
                java.util.function.Function<ToIntFunction<String>, Expr> parse = resolver -> feel
                        ? Parser.parseFeelWithPaths(source, resolver) : Parser.parseWithPaths(source, resolver);
                tree = parse.apply(name -> {
                    if (name.equals(today) || name.equals(now)) {
                        return clockSlot(name, today, now, -1, slots);
                    }
                    if (!feel && name.matches("[A-Z]{3}")) {
                        return -1;
                    }
                    return slots.computeIfAbsent(name, n -> {
                        names.add(n);
                        return CLOCK_SLOTS + names.size() - 1;
                    });
                });
            }
        } catch (ExpressionException e) {
            throw new ExpressionInvalidException(e.getMessage(), e.position());
        }
        boolean declared = types.isDeclared();
        return new Compiled(source, tree, List.copyOf(names), declared, resultType(tree));
    }

    /** Untyped: the two clock slots come first. Declared: they follow the declared names. */
    private static final int CLOCK_SLOTS = 2;

    private static int clockSlot(String name, String today, String now, int declaredCount, Map<String, Integer> slots) {
        if (declaredCount < 0) {
            return name.equals(today) ? 0 : name.equals(now) ? 1 : -1;
        }
        if (name.equals(today)) {
            return declaredCount;
        }
        if (name.equals(now)) {
            return declaredCount + 1;
        }
        return slots.getOrDefault(name, -1);
    }

    private static ValueType resultType(Expr tree) {
        return switch (tree) {
            case Expr.Literal literal -> switch (literal.value()) {
                case null -> ValueType.ANY;
                case String s -> ValueType.STRING;
                case Boolean b -> ValueType.BOOLEAN;
                case java.math.BigDecimal d -> ValueType.NUMBER;
                case io.github.dhluong90.bpm.expr.api.Money m -> ValueType.MONEY;
                case java.time.LocalDate d -> ValueType.DATE;
                case Instant i -> ValueType.DATETIME;
                default -> ValueType.ANY;
            };
            case Expr.Binary binary -> switch (binary.op()) {
                case EQ, NE, LT, LE, GT, GE, AND, OR -> ValueType.BOOLEAN;
                default -> ValueType.ANY;
            };
            case Expr.Unary unary -> unary.op() == Expr.UnaryOp.NOT ? ValueType.BOOLEAN : ValueType.ANY;
            case Expr.InList inList -> ValueType.BOOLEAN;
            case Expr.Between between -> ValueType.BOOLEAN;
            case Expr.IsNull isNull -> ValueType.BOOLEAN;
            case Expr.Matches matches -> ValueType.BOOLEAN;
            case Expr.Clock clock -> clock.function() == Expr.ClockFunction.TODAY ? ValueType.DATE : ValueType.DATETIME;
            default -> ValueType.ANY;
        };
    }

    private final class Compiled implements CompiledExpression {

        private final String source;
        private final Expr tree;
        private final List<String> names;
        private final boolean declared;
        private final ValueType resultType;

        Compiled(String source, Expr tree, List<String> names, boolean declared, ValueType resultType) {
            this.source = source;
            this.tree = tree;
            this.names = names;
            this.declared = declared;
            this.resultType = resultType;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public ValueType resultType() {
            return resultType;
        }

        @Override
        public EvaluationProfile profile() {
            return EvaluationProfile.COMPILED;
        }

        @Override
        public Object evaluate(EvaluationContext context) {
            Objects.requireNonNull(context, "context must not be null");
            Object[] frame = new Object[names.size() + CLOCK_SLOTS];
            int first = declared ? 0 : CLOCK_SLOTS;
            for (int i = 0; i < names.size(); i++) {
                frame[first + i] = context.value(names.get(i));
            }
            Instant clock = context.clock();
            int todaySlot = declared ? names.size() : 0;
            frame[todaySlot] = clock == null ? null : clock.atZone(zone).toLocalDate();
            frame[todaySlot + 1] = clock;
            try {
                return (declared ? evaluator : strictEvaluator).forDecision().evaluate(tree, frame);
            } catch (EvaluationException e) {
                throw new ExpressionEvaluationException(e.getMessage());
            }
        }

        @Override
        public String toString() {
            return source;
        }
    }
}
