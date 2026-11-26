package dev.datacircle.genai;

import com.google.genai.types.Tool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The four Datacircle functions as static methods, for the Google Gen AI Java SDK's automatic function calling: give
 * {@link #tool()} to a {@code GenerateContentConfig}'s tools, and {@code client.models.generateContent} and a chat's
 * {@code sendMessage} run each call the model makes. Each method's name is the function's name the model sees, and
 * its parameter names are kept in the class file ({@code -parameters}), as the SDK reads them.
 *
 * <p>The SDK sends the model only each function's name and its parameters' names and types, and asks for every
 * parameter: an empty string, or a limit of 0, is the default. Each function answers with text, and a line that
 * starts with {@code Error:} when the call fails, so the model can call again. They call
 * {@code https://api.datacircle.dev/v1}, which needs no key and no account.
 *
 * <pre>{@code
 * GenerateContentConfig config = GenerateContentConfig.builder().tools(List.of(DatacircleFunctions.tool())).build();
 * GenerateContentResponse response = client.models.generateContent(
 *         "gemini-3.8-flash", "Who are the 3 best providers for the email-finder API?", config);
 * System.out.println(response.text());
 * }</pre>
 */
public final class DatacircleFunctions {

    private static volatile DatacircleTools tools = new DatacircleTools();

    private DatacircleFunctions() {
    }

    /**
     * The four static methods as one tool, for a {@code GenerateContentConfig}'s tools. Your own functions go beside
     * it as methods in tools of their own: the SDK runs no function when a tool of the config has function
     * declarations.
     *
     * @return a tool of {@link #methods()}
     */
    public static Tool tool() {
        return Tool.builder().functions(methods()).build();
    }

    /**
     * The four static methods, in the order of {@link DatacircleTools#NAMES}.
     *
     * @return a new list of the four, one you can add your own methods to
     */
    public static List<Method> methods() {
        List<Method> methods = new ArrayList<>();
        try {
            methods.add(DatacircleFunctions.class.getMethod("datacircle_list_apis", String.class));
            methods.add(DatacircleFunctions.class.getMethod("datacircle_best_plans", String.class, Integer.class));
            methods.add(DatacircleFunctions.class.getMethod("datacircle_search_plans", String.class, String.class,
                    String.class, Integer.class, Integer.class));
            methods.add(DatacircleFunctions.class.getMethod("datacircle_provider", String.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return methods;
    }

    /**
     * List the GTM APIs that Datacircle ranks (email finder, company enrichment, LinkedIn profile and more), each with
     * its slug, how many plans and providers it has, and its best plan.
     *
     * @param query words that must all appear in the API's name or slug, like {@code email}; empty for every API
     * @return the APIs, one a line, or a line that starts with {@code Error:}
     */
    public static String datacircle_list_apis(String query) {
        return tools.listApis(query);
    }

    /**
     * Get the best plans of one GTM API, best first by Datacircle's score out of 100, with each plan's price per 1,000
     * and its page.
     *
     * @param api the API's slug, like {@code email-finder}, or its name
     * @param limit number of plans from 1 through 50; 0 or null for 10
     * @return the ranked plans, one a line, or a line that starts with {@code Error:}
     */
    public static String datacircle_best_plans(String api, Integer limit) {
        return tools.bestPlans(api, orDefault(limit));
    }

    /**
     * Search the plans of the GTM APIs on Datacircle by API, and by words in the provider's or plan's name, sorted by
     * score (best first), provider (A to Z) or price (lowest $ per 1,000 first).
     *
     * @param api the API's name or slug, like {@code email-finder}; empty for every API
     * @param query words that must all appear in the provider's or plan's name, like {@code hunter}; empty for every
     *     plan
     * @param sort {@code score}, {@code provider} or {@code price}; empty for score
     * @param limit number of plans from 1 through 50; 0 or null for 10
     * @param offset number of found plans to skip first
     * @return the plans found, one a line, or a line that starts with {@code Error:}
     */
    public static String datacircle_search_plans(String api, String query, String sort, Integer limit,
            Integer offset) {
        return tools.searchPlans(api, query, sort, orDefault(limit), offset);
    }

    /**
     * Get one provider's rank on each GTM API it sells on Datacircle, with its best plan there. A provider is a company
     * that sells the APIs, like Hunter or Bright Data.
     *
     * @param provider the provider's name or slug, like {@code Bright Data} or {@code bright-data}
     * @return the provider's ranks, one API a line, or a line that starts with {@code Error:}
     */
    public static String datacircle_provider(String provider) {
        return tools.provider(provider);
    }

    /** For the tests: the functions on another address of the API. */
    static void use(DatacircleTools other) {
        tools = other;
    }

    private static Integer orDefault(Integer limit) {
        return limit != null && limit == 0 ? null : limit;
    }
}
