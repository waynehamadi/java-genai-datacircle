package dev.datacircle.genai;

import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Functions for the <a href="https://github.com/googleapis/java-genai">Google Gen AI Java SDK</a> on the open
 * <a href="https://datacircle.dev">Datacircle</a> API, which benchmarks the GTM APIs (email finders, company
 * enrichment, LinkedIn data and more) and ranks every plan of each by its score out of 100 and its price per 1,000.
 *
 * <p>For a loop of your own: {@link #tool()} gives the four function declarations as one {@link Tool} for a
 * {@code GenerateContentConfig}'s tools, beside your own, and {@link #responses(List)} answers the model's function
 * calls. For the SDK's automatic function calling, use {@link DatacircleFunctions#tool()}. The API needs no key and
 * no account. It allows 60 calls a minute from one address. Its spec is at
 * {@code https://api.datacircle.dev/v1/openapi.json}.
 *
 * <pre>{@code
 * DatacircleTools datacircle = new DatacircleTools();
 * GenerateContentConfig config = GenerateContentConfig.builder().tools(List.of(datacircle.tool())).build();
 * GenerateContentResponse response = client.models.generateContent("gemini-3.8-flash", contents, config);
 * List<Part> answers = datacircle.responses(response.functionCalls());
 * }</pre>
 */
public final class DatacircleTools {

    /** The four functions' names, as the model calls them. */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans", "datacircle_provider"));

    private static final String LIMIT = "Number of plans from 1 through 50. 10 when left out";
    private static final String ERROR = "Error: ";
    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_PROVIDER_APIS = 100;
    private static final String NEW_LINE = "\n";

    private final DatacircleClient client;

    /** Functions on {@code https://api.datacircle.dev/v1}, with a 30 s timeout. */
    public DatacircleTools() {
        this(DatacircleClient.DEFAULT_BASE_URL, Duration.ofSeconds(30));
    }

    /**
     * Functions on another address of the API, or with another timeout.
     *
     * @param baseUrl the API's base URL, {@code https://api.datacircle.dev/v1} when null
     * @param timeout the connection and answer timeout
     */
    public DatacircleTools(String baseUrl, Duration timeout) {
        this.client = new DatacircleClient(baseUrl, timeout);
    }

    /**
     * The four function declarations, each with its description and parameters, in the order of {@link #NAMES}.
     *
     * @return a new list of the four, one you can add your own declarations to
     */
    public List<FunctionDeclaration> declarations() {
        List<FunctionDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("datacircle_list_apis", "List the GTM APIs that Datacircle ranks (email finder, "
                        + "company enrichment, LinkedIn profile and more), each with its slug, how many plans and "
                        + "providers it has, and its best plan.",
                property("query", Type.Known.STRING, false, "Words that must all appear in the API's name or slug, "
                        + "like email or linkedin. Leave it out to list every API")));
        declarations.add(declaration("datacircle_best_plans", "Get the best plans of one GTM API, best first by "
                        + "Datacircle's score out of 100, with each plan's price per 1,000 and its page.",
                property("api", Type.Known.STRING, true, "The API's slug, as datacircle_list_apis gives it, like "
                        + "email-finder"),
                property("limit", Type.Known.INTEGER, false, LIMIT).range(1, 50.0)));
        declarations.add(declaration("datacircle_search_plans", "Search the plans of the GTM APIs on Datacircle by "
                        + "API, and by words in the provider's or plan's name, sorted by score (best first), provider "
                        + "(A to Z) or price (lowest $ per 1,000 first).",
                property("api", Type.Known.STRING, false, "The API's name or slug, like email-finder. A provider's "
                        + "name goes in query. Every API when left out"),
                property("query", Type.Known.STRING, false, "Words that must all appear in the provider's or plan's "
                        + "name, like hunter. Every plan when left out"),
                property("sort", Type.Known.STRING, false, "Sort: score, provider or price. score when left out")
                        .values("score", "provider", "price"),
                property("limit", Type.Known.INTEGER, false, LIMIT).range(1, 50.0),
                property("offset", Type.Known.INTEGER, false, "Number of found plans to skip first. 0 when left out")
                        .range(0, null)));
        declarations.add(declaration("datacircle_provider", "Get one provider's rank on each GTM API it sells on "
                        + "Datacircle, with its best plan there. A provider is a company that sells the APIs, like "
                        + "Hunter or Bright Data.",
                property("provider", Type.Known.STRING, true, "The provider's name or slug, like Bright Data or "
                        + "bright-data")));
        return declarations;
    }

    /**
     * The four function declarations as one tool, for a {@code GenerateContentConfig}'s tools beside your own, when you
     * run the model's function calls in a loop of your own.
     *
     * @return a tool of {@link #declarations()}
     */
    public Tool tool() {
        return Tool.builder().functionDeclarations(declarations()).build();
    }

    /**
     * Says whether a function's name is one of the four.
     *
     * @param name the function's name
     * @return true for one of {@link #NAMES}
     */
    public boolean handles(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * Calls one of the four functions by its name on the model's arguments.
     *
     * @param name the function's name
     * @param arguments the call's arguments, {@code functionCall.args()}; null is none
     * @return the function's answer as text, a line that starts with {@code Error:} when it fails, or empty when the
     *     name is none of the four
     */
    public Optional<String> call(String name, Map<String, ?> arguments) {
        if (!handles(name)) {
            return Optional.empty();
        }
        Map<String, ?> args = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
        try {
            switch (name) {
                case "datacircle_list_apis":
                    return Optional.of(listApis(string(args, "query")));
                case "datacircle_best_plans":
                    return Optional.of(bestPlans(string(args, "api"), integer(args, "limit")));
                case "datacircle_search_plans":
                    return Optional.of(searchPlans(string(args, "api"), string(args, "query"), string(args, "sort"),
                            integer(args, "limit"), integer(args, "offset")));
                default:
                    return Optional.of(provider(string(args, "provider")));
            }
        } catch (IllegalArgumentException e) {
            return Optional.of(formatError(e));
        }
    }

    /**
     * Runs one function call of the model, when it calls one of the four.
     *
     * @param call the function call, one of {@code response.functionCalls()}
     * @return the part that answers it, with the call's name and id: its response {@code {"output": text}}, or
     *     {@code {"error": text}} when the call fails. Empty when the call is of another function.
     */
    public Optional<Part> response(FunctionCall call) {
        if (call == null) {
            return Optional.empty();
        }
        String name = call.name().orElse(null);
        return call(name, call.args().orElse(null)).map(text -> part(call, text));
    }

    /**
     * Answers each function call of the model's turn, in order, one part each, for the next user turn. Gemini wants
     * every call of a turn answered, so a call of a function that is not one of the four is answered with an error the
     * model reads.
     *
     * @param calls the model's function calls, {@code response.functionCalls()}; null is none
     * @return the parts that answer them, empty when there are none: the model's answer
     */
    public List<Part> responses(List<FunctionCall> calls) {
        return responses(calls, null);
    }

    /**
     * Answers each function call of the model's turn, in order, one part each, for the next user turn: the four
     * functions' calls by their API, the others by {@code own}.
     *
     * @param calls the model's function calls, {@code response.functionCalls()}; null is none
     * @param own runs a call of one of your own functions and gives its response, a JSON object as a map. Its null, or
     *     a null {@code own}, answers that no function has that name. What it throws is answered as an error the
     *     model reads.
     * @return the parts that answer them, empty when there are none: the model's answer
     */
    public List<Part> responses(List<FunctionCall> calls, Function<FunctionCall, Map<String, Object>> own) {
        List<Part> parts = new ArrayList<>();
        if (calls == null) {
            return parts;
        }
        for (FunctionCall call : calls) {
            if (call == null) {
                continue;
            }
            Optional<Part> ours = response(call);
            parts.add(ours.isPresent() ? ours.get() : ownPart(call, own));
        }
        return parts;
    }

    /**
     * {@code datacircle_list_apis}: the GTM APIs Datacircle ranks, each with its slug, its counts and its best plan.
     *
     * @param query words that must all appear in the API's name or slug, or null for every API
     * @return the APIs, one a line, or a line that starts with {@code Error:}
     */
    public String listApis(String query) {
        try {
            return formatApis(client.listApis(), query);
        } catch (RuntimeException e) {
            return formatError(e);
        }
    }

    /**
     * {@code datacircle_best_plans}: one API's best plans, best first by score.
     *
     * @param api the API's slug, like {@code email-finder}, or its name, like {@code Email finder}
     * @param limit how many plans, 1 to 50, or null for 10
     * @return the ranked plans, one a line, or a line that starts with {@code Error:}
     */
    public String bestPlans(String api, Integer limit) {
        try {
            return formatBestPlans(client.bestPlans(api, limit));
        } catch (RuntimeException e) {
            return formatError(e) + (isStatus(e, 404) ? " Call datacircle_list_apis for every API's slug." : "");
        }
    }

    /**
     * {@code datacircle_search_plans}: the plans of every API, or of one, by provider or plan name.
     *
     * @param api the API's name or slug, or null for every API
     * @param query words that must all appear in the provider's or plan's name, or null for every plan
     * @param sort {@code score}, {@code provider} or {@code price}, or null for score
     * @param limit how many plans, 1 to 50, or null for 10
     * @param offset how many found plans to skip first, or null for 0
     * @return the plans found, one a line, or a line that starts with {@code Error:}
     */
    public String searchPlans(String api, String query, String sort, Integer limit, Integer offset) {
        try {
            return formatSearch(client.searchPlans(api, query, sort, limit, offset), offset == null ? 0 : offset);
        } catch (RuntimeException e) {
            boolean unknownApi = api != null && !api.isBlank() && (isStatus(e, 400) || isStatus(e, 404));
            return formatError(e) + (unknownApi ? " Call datacircle_list_apis for every API's name and slug." : "");
        }
    }

    /**
     * {@code datacircle_provider}: one provider's rank on each API it sells.
     *
     * @param provider the provider's name or slug, like {@code Bright Data} or {@code bright-data}
     * @return the provider's ranks, one API a line, or a line that starts with {@code Error:}
     */
    public String provider(String provider) {
        try {
            return formatProvider(client.provider(provider));
        } catch (RuntimeException e) {
            return formatError(e);
        }
    }

    private static FunctionDeclaration declaration(String name, String description, Param... params) {
        Map<String, Schema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Param param : params) {
            properties.put(param.name, param.schema.build());
            if (param.required) {
                required.add(param.name);
            }
        }
        Schema.Builder parameters = Schema.builder().type(Type.Known.OBJECT).properties(properties);
        if (!required.isEmpty()) {
            parameters.required(required);
        }
        return FunctionDeclaration.builder().name(name).description(description).parameters(parameters.build()).build();
    }

    private static Param property(String name, Type.Known type, boolean required, String description) {
        return new Param(name, required, Schema.builder().type(type).description(description));
    }

    /** One parameter of a function's declaration. */
    private static final class Param {
        final String name;
        final boolean required;
        final Schema.Builder schema;

        Param(String name, boolean required, Schema.Builder schema) {
            this.name = name;
            this.required = required;
            this.schema = schema;
        }

        Param range(double minimum, Double maximum) {
            schema.minimum(minimum);
            if (maximum != null) {
                schema.maximum(maximum);
            }
            return this;
        }

        Param values(String... values) {
            schema.enum_(Arrays.asList(values));
            return this;
        }
    }

    /** The part that answers a call of one of the four: {@code {"output": text}}, or {@code {"error": text}}. */
    private static Part part(FunctionCall call, String text) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (text.startsWith(ERROR)) {
            response.put("error", text.substring(ERROR.length()));
        } else {
            response.put("output", text);
        }
        return functionResponse(call, response);
    }

    private static Part ownPart(FunctionCall call, Function<FunctionCall, Map<String, Object>> own) {
        Map<String, Object> response;
        try {
            response = own == null ? null : own.apply(call);
        } catch (RuntimeException e) {
            response = new LinkedHashMap<>();
            response.put("error", formatError(e).substring(ERROR.length()));
        }
        if (response == null) {
            response = new LinkedHashMap<>();
            response.put("error", "no function is named " + call.name().orElse("") + " here.");
        }
        return functionResponse(call, response);
    }

    /** A function response with the call's name and, when the call has one, its id. */
    private static Part functionResponse(FunctionCall call, Map<String, Object> response) {
        FunctionResponse.Builder answer = FunctionResponse.builder().name(call.name().orElse("")).response(response);
        if (call.id().isPresent()) {
            answer.id(call.id().get());
        }
        return Part.builder().functionResponse(answer.build()).build();
    }

    private static String string(Map<String, ?> args, String name) {
        Object value = args.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number) {
            return number(value).toPlainString();
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static Integer integer(Map<String, ?> args, String name) {
        Object value = args.get(name);
        if (value == null || value instanceof String && ((String) value).isBlank()) {
            return null;
        }
        try {
            BigDecimal number = value instanceof Number ? number(value) : new BigDecimal(((String) value).trim());
            return number.intValueExact();
        } catch (ArithmeticException | ClassCastException | NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    /** A number as Jackson or the JSON reader gives it: Integer, Long, Double, BigInteger or BigDecimal. */
    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException("not a number");
            }
            return BigDecimal.valueOf(d);
        }
        return new BigDecimal(value.toString());
    }

    private static String formatApis(Object apis, String query) {
        if (!(apis instanceof List)) {
            throw unexpected("the list of APIs is not a list");
        }
        List<?> list = (List<?>) apis;
        if (list.isEmpty()) {
            return "The Datacircle API returned no APIs.";
        }
        List<String> words = words(query);
        List<String> lines = new ArrayList<>();
        for (Object api : list) {
            String name = required(api, "api");
            String slug = required(api, "slug");
            if (!matchesAll(name + " " + slug, words)) {
                continue;
            }
            StringBuilder line = new StringBuilder("- ")
                    .append(truncate(name))
                    .append(" (")
                    .append(slug)
                    .append("): ")
                    .append(count(field(api, "plans"), "plan"))
                    .append(" from ")
                    .append(count(field(api, "providers"), "provider"))
                    .append('.');
            Object best = field(api, "best");
            if (best instanceof Map) {
                line.append(" Best: ")
                        .append(truncate(required(best, "provider")))
                        .append(", ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), null))
                        .append('.');
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            return "No API's name or slug holds every word of \"" + query.trim() + "\". Leave the query empty to list "
                    + list.size() + " APIs.";
        }
        return String.join(NEW_LINE, lines) + NEW_LINE + lines.size() + " of " + list.size()
                + " APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.";
    }

    private static String formatBestPlans(Object response) {
        List<?> plans = list(response, "plans");
        StringBuilder result = new StringBuilder()
                .append(required(response, "api"))
                .append(" (")
                .append(required(response, "slug"))
                .append("): ");
        if (plans.isEmpty()) {
            return result.append("no plans found.").toString();
        }
        result.append("the best ")
                .append(plans.size())
                .append(" of ")
                .append(count(field(response, "total"), "plan"))
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, false));
        }
        return result.toString();
    }

    private static String formatSearch(Object response, int requestedOffset) {
        List<?> plans = list(response, "plans");
        Object total = field(response, "total");
        Object offsetField = field(response, "offset");
        int offset = offsetField instanceof BigDecimal ? ((BigDecimal) offsetField).intValue() : requestedOffset;
        if (plans.isEmpty()) {
            if (total instanceof BigDecimal && ((BigDecimal) total).signum() > 0) {
                return "Found " + count(total, "plan") + ", but the offset " + offset
                        + " is past the last one. Use an offset below " + ((BigDecimal) total).toPlainString() + ".";
            }
            return "No plans found.";
        }
        StringBuilder result = new StringBuilder("Found ")
                .append(count(total, "plan"))
                .append("; showing ")
                .append(offset + 1)
                .append(" to ")
                .append(offset + plans.size())
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, true));
        }
        return result.toString();
    }

    private static String formatProvider(Object response) {
        List<?> apis = list(response, "apis");
        StringBuilder result = new StringBuilder(required(response, "provider"));
        String website = text(response, "website");
        if (!website.isEmpty()) {
            result.append(" (").append(website).append(')');
        }
        if (apis.isEmpty()) {
            return result.append(" sells no API on Datacircle.").toString();
        }
        result.append(" sells ").append(plural(apis.size(), "API")).append(". Its rank among each API's providers:");
        int shown = 0;
        for (Object api : apis) {
            if (shown == MAX_PROVIDER_APIS) {
                result.append(NEW_LINE)
                        .append("And ")
                        .append(plural(apis.size() - shown, "more API"))
                        .append('.');
                break;
            }
            shown++;
            Object rank = field(api, "rank");
            List<?> plans = list(api, "plans");
            result.append(NEW_LINE)
                    .append("- ")
                    .append(truncate(required(api, "api")))
                    .append(" (")
                    .append(required(api, "slug"))
                    .append("): ")
                    .append(rank instanceof BigDecimal ? "rank " + ((BigDecimal) rank).intValue() : "not ranked")
                    .append(", ")
                    .append(plural(plans.size(), "plan"))
                    .append('.');
            Object best = bestScored(plans);
            if (best != null) {
                result.append(" Best: ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), field(best, "per")))
                        .append('.');
            }
        }
        return result.toString();
    }

    private static Object bestScored(List<?> plans) {
        Object best = null;
        BigDecimal bestScore = null;
        for (Object plan : plans) {
            Object score = field(plan, "score");
            if (score instanceof BigDecimal && (bestScore == null || ((BigDecimal) score).compareTo(bestScore) > 0)) {
                best = plan;
                bestScore = (BigDecimal) score;
            }
        }
        return best;
    }

    private static String formatPlan(Object plan, boolean withApi) {
        StringBuilder line = new StringBuilder();
        Object rank = field(plan, "rank");
        boolean ranked = rank instanceof BigDecimal;
        line.append(!withApi && ranked ? ((BigDecimal) rank).intValue() + ". " : "- ")
                .append(truncate(required(plan, "provider")))
                .append(", ")
                .append(truncate(required(plan, "plan")));
        if (withApi) {
            line.append(" (").append(truncate(required(plan, "api"))).append(')');
        }
        line.append(": ");
        if (withApi && ranked) {
            line.append("rank ").append(((BigDecimal) rank).intValue()).append(", ");
        }
        line.append(score(field(plan, "score")))
                .append(", ")
                .append(price(field(plan, "perThousand"), field(plan, "per")));
        Object notFound = field(plan, "notFoundPerThousand");
        if (notFound instanceof BigDecimal) {
            line.append(", ").append(dollars((BigDecimal) notFound)).append(" per 1,000 not found");
        }
        String billing = text(plan, "billing");
        if (!billing.isEmpty()) {
            line.append(", ").append(billing);
        }
        Object latency = field(plan, "latencyMs");
        if (latency instanceof BigDecimal) {
            line.append(", median ").append(((BigDecimal) latency).longValue()).append(" ms");
        }
        line.append('.');
        String page = text(plan, "page");
        if (!page.isEmpty()) {
            line.append(' ').append(page);
        }
        return line.toString();
    }

    private static String score(Object score) {
        return score instanceof BigDecimal
                ? "score " + ((BigDecimal) score).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        + "/100"
                : "not scored";
    }

    private static String price(Object perThousand, Object per) {
        if (!(perThousand instanceof BigDecimal)) {
            return "no listed price";
        }
        String unit = per instanceof String && !((String) per).isBlank() ? " " + per + "s" : "";
        return dollars((BigDecimal) perThousand) + " per 1,000" + unit;
    }

    private static String dollars(BigDecimal amount) {
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private static String count(Object number, String word) {
        return number instanceof BigDecimal ? plural(((BigDecimal) number).longValue(), word) : "? " + word + "s";
    }

    private static String plural(long count, String word) {
        return String.format(Locale.ROOT, "%,d", count) + " " + word + (count == 1 ? "" : "s");
    }

    private static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(fold(query).trim().split("[\\s-]+"));
    }

    private static boolean matchesAll(String text, List<String> words) {
        String folded = fold(text);
        return words.stream().allMatch(folded::contains);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void appendIfPresent(StringBuilder result, String label, String value) {
        if (!value.isEmpty()) {
            result.append(' ').append(label).append(": ").append(value);
        }
    }

    private static Object field(Object node, String name) {
        return node instanceof Map ? ((Map<?, ?>) node).get(name) : null;
    }

    private static String required(Object node, String name) {
        Object value = field(node, name);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw unexpected(name + " is missing");
        }
        return (String) value;
    }

    private static List<?> list(Object node, String name) {
        Object value = field(node, name);
        if (!(value instanceof List)) {
            throw unexpected(name + " is not a list");
        }
        return (List<?>) value;
    }

    private static String text(Object node, String name) {
        Object value = field(node, name);
        return value instanceof String && !((String) value).isBlank() ? (String) value : "";
    }

    private static DatacircleException unexpected(String what) {
        return new DatacircleException("The Datacircle API returned an answer of an unexpected shape: " + what
                + ". Retry later, or read https://api.datacircle.dev/v1/openapi.json.");
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
    }

    private static boolean isStatus(RuntimeException error, int statusCode) {
        return error instanceof DatacircleException && ((DatacircleException) error).statusCode() == statusCode;
    }

    private static String formatError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Error: the Datacircle API request failed." : "Error: " + message;
    }
}
