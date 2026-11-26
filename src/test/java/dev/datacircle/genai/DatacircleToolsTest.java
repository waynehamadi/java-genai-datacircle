package dev.datacircle.genai;

import static dev.datacircle.genai.FakeDatacircle.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatacircleToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatacircleTools tools;

    @BeforeAll
    static void start() throws IOException {
        FakeDatacircle.start();
    }

    @AfterAll
    static void stop() {
        FakeDatacircle.stop();
    }

    @BeforeEach
    void setUp() {
        tools = FakeDatacircle.tools();
    }

    @Test
    void listsApisAndFiltersThemByWords() {
        assertEquals(
                "- Employee email (email-finder): 5,444 plans from 300 providers. Best: Emelia, 100,000 credits per "
                        + "month, score 76.6/100, $4.99 per 1,000.\n"
                        + "- Company profile (linkedin-company): 1 plan from 1 provider.\n"
                        + "2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.",
                tools.listApis(null));
        assertTrue(call("datacircle_list_apis", "{\"query\": \"EMAIL finder\"}").contains("1 of 2 APIs."));
        assertTrue(call("datacircle_list_apis", "{\"query\": \"\"}").contains("2 of 2 APIs."));
        assertEquals(
                "No API's name or slug holds every word of \"zzz\". Leave the query empty to list 2 APIs.",
                call("datacircle_list_apis", "{\"query\": \" zzz \"}"));
    }

    @Test
    void givesTheBestPlansOfAnApiByItsSlugOrName() {
        String best = "Employee email (email-finder): the best 1 of 5,444 plans. "
                + "Sheet: https://datacircle.dev/email-finder-api-pricing\n"
                + "1. Emelia, Pro: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly, "
                + "median 812 ms. https://datacircle.dev/find-email-apis/emelia/pro";
        assertEquals(best, tools.bestPlans(" Email-Finder ", 3));
        assertEquals(best, tools.bestPlans("Email finder API", 3));
        assertEquals(best, tools.bestPlans("émail finder apis", null));
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3", "/v1/apis/email-finder/best?limit=3",
                "/v1/apis/email-finder/best?limit=10"), requests);
    }

    @Test
    void searchesThePlans() {
        assertEquals(
                "Found 2 plans; showing 2 to 2.\n"
                        + "- Hunter, Starter (People search): rank 18, not scored, no listed price. "
                        + "https://datacircle.dev/p/hunter",
                tools.searchPlans("people search", "hunter io", "Price", 5, 1));
        assertEquals(List.of("/v1/plans?sort=price&limit=5&offset=1&api=people+search&query=hunter+io"), requests);
    }

    @Test
    void givesAProvidersRanks() {
        assertEquals(
                "Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:\n"
                        + "- Employee profile (linkedin-profile): rank 3, 2 plans. Best: Growth, score 71.3/100, "
                        + "$1.05 per 1,000 records.\n"
                        + "- SERP (serp): not ranked, 0 plans.",
                call("datacircle_provider", "{\"provider\": \"Bright Data\"}"));
        assertEquals(List.of("/v1/providers/bright-data"), requests);
    }

    @Test
    void callTakesTheArgumentsAsTheSdkReadsThem() {
        assertTrue(call("datacircle_search_plans",
                        "{\"api\": null, \"query\": \"hunter\", \"sort\": null, \"limit\": \"5\", \"offset\": null}")
                .startsWith("Found 2 plans"));
        assertEquals(List.of("/v1/plans?sort=score&limit=5&offset=0&query=hunter"), requests);
        assertTrue(call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 2.0}")
                .startsWith("Employee email (email-finder): the best 1"));
        assertTrue(tools.call("datacircle_list_apis", null).get().contains("2 of 2 APIs"));
        assertEquals(Optional.empty(), tools.call("get_weather", Collections.emptyMap()));
        assertEquals(Optional.empty(), tools.call(null, null));
    }

    @Test
    void saysWhatWentWrong() {
        assertEquals(
                "Error: Datacircle API request failed: HTTP 404. no API has the slug nope. "
                        + "Call datacircle_list_apis for every API's slug.",
                call("datacircle_best_plans", "{\"api\": \"nope\", \"limit\": null}"));
        assertEquals(
                "Error: Datacircle API request failed: HTTP 429. too many calls. The API allows 60 calls a minute "
                        + "from one address. Retry in a minute.",
                call("datacircle_search_plans", "{\"query\": \"busy\"}"));
        assertEquals("Error: The Datacircle API returned invalid JSON.", call("datacircle_best_plans", "{\"api\": \"broken\"}"));
        assertEquals("Error: limit must be between 1 and 50", call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 51}"));
        assertEquals("Error: limit must be a whole number", call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 2.5}"));
        assertEquals("Error: sort must be one of [price, provider, score]", call("datacircle_search_plans", "{\"sort\": \"x\"}"));
        assertEquals("Error: offset must be 0 or more", call("datacircle_search_plans", "{\"offset\": -1}"));
        assertEquals("Error: api must not be empty", call("datacircle_best_plans", "{\"api\": \" \"}"));
        assertEquals("Error: api must be an API's slug, like email-finder. Call datacircle_list_apis for every "
                + "API's slug.", call("datacircle_best_plans", "{\"api\": \"API\"}"));
        assertEquals("Error: provider must not be empty", call("datacircle_provider", "{}"));
        assertEquals("Error: query must be a string", call("datacircle_list_apis", "{\"query\": [\"email\"]}"));
        assertTrue(call("datacircle_provider", "{\"provider\": \"北京\"}")
                .startsWith("Error: Datacircle writes each provider's name in Latin letters"));
        assertTrue(requests.stream().noneMatch(r -> r.contains("limit=51")));
    }

    @Test
    void saysWhenTheApiIsUnreachable() {
        DatacircleTools offline = new DatacircleTools("http://127.0.0.1:1/v1", Duration.ofSeconds(2));
        assertEquals(
                "Error: The Datacircle API refused the connection. Check the network and the base URL.",
                offline.listApis(null));
    }

    @Test
    void declarationsAreFourWithTheirParameters() throws Exception {
        List<FunctionDeclaration> declarations = tools.declarations();
        assertEquals(DatacircleTools.NAMES,
                declarations.stream().map(d -> d.name().get()).collect(Collectors.toList()));
        for (FunctionDeclaration declaration : declarations) {
            assertFalse(declaration.description().get().isBlank());
            assertEquals("OBJECT", declaration.parameters().get().type().get().toString());
        }
        Schema search = declarations.get(2).parameters().get();
        assertEquals(List.of("api", "query", "sort", "limit", "offset"), new ArrayList<>(search.properties().get().keySet()));
        assertEquals("INTEGER", search.properties().get().get("limit").type().get().toString());
        assertEquals(1.0, search.properties().get().get("limit").minimum().get());
        assertEquals(50.0, search.properties().get().get("limit").maximum().get());
        assertEquals(List.of("score", "provider", "price"), search.properties().get().get("sort").enum_().get());
        assertFalse(search.required().isPresent());
        assertEquals(List.of("api"), declarations.get(1).parameters().get().required().get());
        assertEquals(List.of("provider"), declarations.get(3).parameters().get().required().get());

        // As the SDK sends them to Gemini.
        String json = tools.tool().toJson();
        assertTrue(json.contains("\"functionDeclarations\":[{"), json);
        assertTrue(json.contains("\"name\":\"datacircle_list_apis\""), json);
        assertTrue(json.contains("\"enum\":[\"score\",\"provider\",\"price\"]"), json);
        assertTrue(json.contains("\"required\":[\"api\"]"), json);

        // A new list each time, for your own declarations beside them.
        assertNotSame(declarations, tools.declarations());
        declarations.add(FunctionDeclaration.builder().name("get_time").build());
        assertEquals(4, tools.declarations().size());
        assertTrue(tools.handles("datacircle_provider"));
        assertFalse(tools.handles("get_time"));
        assertFalse(tools.handles(null));
    }

    @Test
    void responseAnswersACallWithItsNameAndId() {
        FunctionResponse answer = tools.response(call("c1", "datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 1}"))
                .get().functionResponse().get();
        assertEquals("c1", answer.id().get());
        assertEquals("datacircle_best_plans", answer.name().get());
        assertTrue(String.valueOf(answer.response().get().get("output")).startsWith("Employee email (email-finder)"));

        FunctionResponse failed = tools.response(call(null, "datacircle_best_plans", "{\"api\": \"nope\"}"))
                .get().functionResponse().get();
        assertFalse(failed.id().isPresent());
        assertEquals(List.of("error"), new ArrayList<>(failed.response().get().keySet()));
        assertTrue(String.valueOf(failed.response().get().get("error")).startsWith("Datacircle API request failed: HTTP 404."));

        assertEquals(Optional.empty(), tools.response(call("c2", "get_time", "{}")));
        assertEquals(Optional.empty(), tools.response(null));
    }

    @Test
    void responsesAnswerEachCallInOrderOnePartEach() {
        List<FunctionCall> calls = Arrays.asList(
                call("a", "datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 3}"),
                call("b", "get_time", "{\"city\": \"Paris\"}"),
                call("c", "datacircle_provider", "{\"provider\": \"Bright Data\"}"),
                call("d", "get_news", "{}"),
                call("e", "get_weather", null));
        List<Part> parts = tools.responses(calls, call -> {
            String name = call.name().get();
            if (name.equals("get_time")) {
                return Map.of("output", "12:00 in " + call.args().get().get("city"));
            }
            if (name.equals("get_weather")) {
                throw new IllegalStateException("the weather service is down");
            }
            return null;
        });
        assertEquals(5, parts.size());
        List<FunctionResponse> answers = parts.stream().map(p -> p.functionResponse().get()).collect(Collectors.toList());
        assertEquals(List.of("a", "b", "c", "d", "e"), answers.stream().map(a -> a.id().get()).collect(Collectors.toList()));
        assertEquals(List.of("datacircle_best_plans", "get_time", "datacircle_provider", "get_news", "get_weather"),
                answers.stream().map(a -> a.name().get()).collect(Collectors.toList()));
        assertTrue(String.valueOf(answers.get(0).response().get().get("output")).startsWith("Employee email"));
        assertEquals(Map.of("output", "12:00 in Paris"), answers.get(1).response().get());
        assertTrue(String.valueOf(answers.get(2).response().get().get("output")).startsWith("Bright Data"));
        assertEquals(Map.of("error", "no function is named get_news here."), answers.get(3).response().get());
        assertEquals(Map.of("error", "the weather service is down"), answers.get(4).response().get());
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3", "/v1/providers/bright-data"), requests);

        assertEquals(Map.of("error", "no function is named get_time here."),
                tools.responses(List.of(call("x", "get_time", "{}"))).get(0).functionResponse().get().response().get());
        assertEquals(List.of(), tools.responses(null));
        assertEquals(List.of(), tools.responses(Collections.emptyList()));
    }

    @Test
    void theStaticFunctionsAreWhatTheSdkReads() throws Exception {
        List<Method> methods = DatacircleFunctions.methods();
        assertEquals(DatacircleTools.NAMES, methods.stream().map(Method::getName).collect(Collectors.toList()));
        assertEquals(List.of("api", "query", "sort", "limit", "offset"),
                Arrays.stream(methods.get(2).getParameters()).map(p -> {
                    assertTrue(p.isNamePresent());
                    return p.getName();
                }).collect(Collectors.toList()));
        assertEquals(methods, DatacircleFunctions.tool().functions().get());
        FunctionDeclaration declared = FunctionDeclaration.fromMethod(methods.get(1));
        assertEquals("datacircle_best_plans", declared.name().get());
        assertEquals(List.of("api", "limit"), declared.parameters().get().required().get());

        DatacircleFunctions.use(tools);
        try {
            assertTrue(DatacircleFunctions.datacircle_list_apis("").contains("2 of 2 APIs."));
            assertTrue(DatacircleFunctions.datacircle_best_plans("email-finder", 0).startsWith("Employee email"));
            assertTrue(DatacircleFunctions.datacircle_search_plans("", "hunter", "", 0, 0).startsWith("Found 2 plans"));
            assertTrue(DatacircleFunctions.datacircle_provider("Bright Data").startsWith("Bright Data"));
            assertEquals("Error: limit must be between 1 and 50", DatacircleFunctions.datacircle_best_plans("email-finder", -1));
            assertEquals(List.of("/v1/apis", "/v1/apis/email-finder/best?limit=10",
                    "/v1/plans?sort=score&limit=10&offset=0&query=hunter", "/v1/providers/bright-data"), requests);
        } finally {
            DatacircleFunctions.use(new DatacircleTools());
        }
    }

    private String call(String name, String arguments) {
        return tools.call(name, args(arguments)).get();
    }

    private static FunctionCall call(String id, String name, String arguments) {
        FunctionCall.Builder call = FunctionCall.builder().name(name);
        if (id != null) {
            call.id(id);
        }
        if (arguments != null) {
            call.args(args(arguments));
        }
        return call.build();
    }

    /** The arguments as the SDK reads them with Jackson: Integer, Long, Double, String, List, Map, null. */
    static Map<String, Object> args(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
