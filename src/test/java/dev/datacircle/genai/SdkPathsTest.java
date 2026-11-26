package dev.datacircle.genai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.Chat;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The four functions through the SDK's own calls, on a scripted Gemini API and a fake Datacircle API. */
public class SdkPathsTest {

    private static final String MODEL = "gemini-3.8-flash";
    private static final String FOUR_AND_OURS = "CALL datacircle_list_apis {\"query\": \"email\"}\n"
            + "CALL datacircle_best_plans {\"api\": \"email-finder\", \"limit\": 2}\n"
            + "CALL datacircle_search_plans {\"api\": \"\", \"query\": \"hunter\", \"sort\": \"price\", \"limit\": 5, \"offset\": 0}\n"
            + "CALL datacircle_provider {\"provider\": \"Bright Data\"}\n"
            + "CALL get_time {\"city\": \"Paris\"}";

    private static Client client;

    private DatacircleTools tools;

    /** An app's own function, run by the SDK beside the four: a public method of a public class, as the SDK calls it. */
    public static String get_time(String city) {
        return "12:00 in " + city;
    }

    @BeforeAll
    static void start() throws IOException {
        FakeDatacircle.start();
        ScriptedGemini.start();
        client = Client.builder()
                .apiKey("test-key")
                .httpOptions(HttpOptions.builder().baseUrl(ScriptedGemini.baseUrl()).build())
                .build();
    }

    @AfterAll
    static void stop() {
        DatacircleFunctions.use(new DatacircleTools());
        ScriptedGemini.stop();
        FakeDatacircle.stop();
    }

    @BeforeEach
    void setUp() {
        tools = FakeDatacircle.tools();
        DatacircleFunctions.use(tools);
        ScriptedGemini.requests.clear();
    }

    @Test
    void automaticFunctionCallingRunsEachFunctionBesideAnAppsOwn() throws Exception {
        GenerateContentResponse response = client.models.generateContent(MODEL, FOUR_AND_OURS, afcConfig());
        assertDone(response.text());
        assertEquals(2, ScriptedGemini.requests.size());
        assertEquals(Arrays.asList("datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans",
                "datacircle_provider", "get_time"), ScriptedGemini.declared(ScriptedGemini.requests.get(0)));
        assertEquals(Arrays.asList("/v1/apis", "/v1/apis/email-finder/best?limit=2",
                "/v1/plans?sort=price&limit=5&offset=0&query=hunter", "/v1/providers/bright-data"),
                FakeDatacircle.requests);
    }

    @Test
    void automaticFunctionCallingGivesAWrongArgumentBackToTheModel() {
        GenerateContentResponse response = client.models.generateContent(MODEL,
                "CALL datacircle_best_plans {\"api\": \"nope\", \"limit\": 0}", afcConfig());
        assertTrue(response.text().startsWith("DONE {\"result\":\"Error: Datacircle API request failed: HTTP 404."),
                response.text());
    }

    @Test
    void aChatRunsTheFunctionsOnEachTurn() {
        Chat chat = client.chats.create(MODEL, afcConfig());
        assertDone(chat.sendMessage(FOUR_AND_OURS).text());
        GenerateContentResponse second = chat.sendMessage("CALL datacircle_provider {\"provider\": \"bright-data\"}");
        assertTrue(second.text().startsWith("DONE {\"result\":\"Bright Data (https://brightdata.com) sells 2 APIs."),
                second.text());
        // The second turn's request holds the first turn whole: the question, the calls, their answers, the answer.
        JsonNode contents = ScriptedGemini.requests.get(2).path("contents");
        assertEquals(5, contents.size(), contents.toString());
    }

    @Test
    void anAppsOwnLoopAnswersEveryCallWithItsId() {
        DatacircleTools datacircle = tools;
        Tool own = Tool.builder().functionDeclarations(Arrays.asList(FunctionDeclaration.builder()
                .name("get_time")
                .description("The time in a city.")
                .parameters(Schema.builder().type(Type.Known.OBJECT)
                        .properties(Map.of("city", Schema.builder().type(Type.Known.STRING).build())).build())
                .build())).build();
        GenerateContentConfig config = GenerateContentConfig.builder().tools(Arrays.asList(datacircle.tool(), own)).build();
        List<Content> contents = new ArrayList<>();
        contents.add(Content.builder().role("user").parts(Arrays.asList(Part.fromText(FOUR_AND_OURS))).build());
        GenerateContentResponse response = client.models.generateContent(MODEL, contents, config);
        assertEquals(5, response.functionCalls().size());
        contents.add(response.candidates().get().get(0).content().get());
        contents.add(Content.builder().role("user").parts(datacircle.responses(response.functionCalls(),
                call -> Map.of("output", get_time(String.valueOf(call.args().get().get("city")))))).build());
        response = client.models.generateContent(MODEL, contents, config);
        assertTrue(response.text().startsWith("DONE {\"output\":\"- Employee email (email-finder)"), response.text());
        assertTrue(response.text().endsWith("{\"output\":\"12:00 in Paris\"}"), response.text());
        JsonNode sent = ScriptedGemini.requests.get(1).path("contents").get(2).path("parts");
        for (int i = 0; i < 5; i++) {
            assertEquals("call_" + i, sent.get(i).path("functionResponse").path("id").asText());
        }
        assertEquals(Arrays.asList("datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans",
                "datacircle_provider", "get_time"), ScriptedGemini.declared(ScriptedGemini.requests.get(0)));
        JsonNode search = ScriptedGemini.requests.get(0).path("tools").get(0).path("functionDeclarations").get(2);
        assertEquals("[\"score\",\"provider\",\"price\"]",
                search.path("parameters").path("properties").path("sort").path("enum").toString());
    }

    @Test
    void anAppsOwnLoopOnAStream() throws Exception {
        GenerateContentConfig config = GenerateContentConfig.builder().tools(Arrays.asList(tools.tool())).build();
        List<Content> contents = new ArrayList<>();
        contents.add(Content.builder().role("user").parts(Arrays.asList(Part.fromText(
                "CALL datacircle_best_plans {\"api\": \"email-finder\"}\nCALL datacircle_list_apis {}"))).build());
        List<Part> turn = new ArrayList<>();
        List<FunctionCall> calls = new ArrayList<>();
        try (ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream(MODEL, contents, config)) {
            for (GenerateContentResponse chunk : stream) {
                turn.addAll(chunk.candidates().get().get(0).content().get().parts().get());
                if (chunk.functionCalls() != null) {
                    calls.addAll(chunk.functionCalls());
                }
            }
        }
        assertEquals(2, calls.size());
        contents.add(Content.builder().role("model").parts(turn).build());
        contents.add(Content.builder().role("user").parts(tools.responses(calls)).build());
        StringBuilder text = new StringBuilder();
        try (ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream(MODEL, contents, config)) {
            for (GenerateContentResponse chunk : stream) {
                text.append(chunk.text());
            }
        }
        assertTrue(text.toString().startsWith("DONE {\"output\":\"Employee email (email-finder): the best 1"), text.toString());
        assertTrue(text.toString().contains(" | {\"output\":\"- Employee email (email-finder)"), text.toString());
    }

    @Test
    void anAsyncCallRunsTheFunctionsOrLeavesThemToTheApp() {
        GenerateContentResponse response = client.async.models.generateContent(MODEL, FOUR_AND_OURS, afcConfig()).join();
        if (response.functionCalls() != null && !response.functionCalls().isEmpty()) {
            // An SDK whose async calls run no function: the app answers them.
            List<Content> contents = new ArrayList<>();
            contents.add(Content.builder().role("user").parts(Arrays.asList(Part.fromText(FOUR_AND_OURS))).build());
            contents.add(response.candidates().get().get(0).content().get());
            contents.add(Content.builder().role("user").parts(tools.responses(response.functionCalls(),
                    call -> Map.of("output", get_time(String.valueOf(call.args().get().get("city")))))).build());
            GenerateContentConfig plain = GenerateContentConfig.builder().tools(Arrays.asList(tools.tool())).build();
            response = client.async.models.generateContent(MODEL, contents, plain).join();
        }
        assertTrue(response.text().startsWith("DONE {\""), response.text());
        assertTrue(response.text().contains("12:00 in Paris"), response.text());
        assertFalse(response.text().contains("\"error\""), response.text());
    }

    private static GenerateContentConfig afcConfig() {
        try {
            Tool ours = DatacircleFunctions.tool();
            Tool own = Tool.builder()
                    .functions(Arrays.asList(SdkPathsTest.class.getMethod("get_time", String.class))).build();
            return GenerateContentConfig.builder()
                    .tools(Arrays.asList(ours, own))
                    .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().maximumRemoteCalls(5).build())
                    .build();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertDone(String text) {
        assertTrue(text.startsWith("DONE {\"result\":\"- Employee email (email-finder): 5,444 plans"), text);
        assertTrue(text.contains(" | {\"result\":\"Employee email (email-finder): the best 1 of 5,444 plans."), text);
        assertTrue(text.contains(" | {\"result\":\"Found 2 plans; showing 2 to 2."), text);
        assertTrue(text.contains(" | {\"result\":\"Bright Data (https://brightdata.com) sells 2 APIs."), text);
        assertTrue(text.endsWith(" | {\"result\":\"12:00 in Paris\"}"), text);
    }
}
