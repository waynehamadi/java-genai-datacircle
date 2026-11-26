package dev.datacircle.genai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted Gemini API on 127.0.0.1: generateContent and streamGenerateContent. The user's last text holds lines
 * {@code CALL <function> <JSON args>}: the model's next turn calls them all at once, each call with an id; the turn
 * after it answers {@code DONE } and each function response as compact JSON, joined by {@code " | "}. As Gemini does,
 * function responses that don't answer the calls before them one for one (their count, names and, when a response has
 * one, ids) are refused with a 400. Each request's body is kept.
 */
final class ScriptedGemini {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CALL = Pattern.compile("^CALL (\\w+) (.*)$", Pattern.MULTILINE);

    static HttpServer server;
    static final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ScriptedGemini::handle);
        server.start();
    }

    static void stop() {
        server.stop(0);
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The function names each request declared, in order. */
    static List<String> declared(JsonNode request) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) {
            for (JsonNode declaration : tool.path("functionDeclarations")) {
                names.add(declaration.path("name").asText());
            }
        }
        return names;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(body);
        boolean stream = exchange.getRequestURI().getPath().contains(":streamGenerateContent");
        String refused = check(body.path("contents"));
        if (refused != null) {
            send(exchange, 400, "application/json", "{\"error\": {\"code\": 400, \"message\": "
                    + MAPPER.writeValueAsString(refused) + ", \"status\": \"INVALID_ARGUMENT\"}}");
            return;
        }
        ArrayNode parts = answer(body.path("contents"));
        if (!stream) {
            send(exchange, 200, "application/json", MAPPER.writeValueAsString(response(parts, true)));
            return;
        }
        List<ArrayNode> chunks = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("text")) {
                String text = part.get("text").asText();
                int half = Math.min(15, text.length());
                chunks.add(MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("text", text.substring(0, half))));
                chunks.add(MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("text", text.substring(half))));
            }
        }
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                chunks.add(MAPPER.createArrayNode().add(part));
            }
        }
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            events.append("data: ").append(MAPPER.writeValueAsString(response(chunks.get(i), i == chunks.size() - 1)))
                    .append("\r\n\r\n");
        }
        send(exchange, 200, "text/event-stream", events.toString());
    }

    private static ArrayNode answer(JsonNode contents) throws IOException {
        int lastUser = -1;
        for (int i = 0; i < contents.size(); i++) {
            JsonNode content = contents.get(i);
            if ("user".equals(content.path("role").asText()) && text(content) != null) {
                lastUser = i;
            }
        }
        String prompt = lastUser < 0 ? "" : text(contents.get(lastUser));
        ArrayNode parts = MAPPER.createArrayNode();
        Matcher calls = CALL.matcher(prompt);
        boolean answered = false;
        List<String> responses = new ArrayList<>();
        for (int i = lastUser + 1; i < contents.size(); i++) {
            answered |= "model".equals(contents.get(i).path("role").asText());
            for (JsonNode part : contents.get(i).path("parts")) {
                if (part.has("functionResponse")) {
                    responses.add(MAPPER.writeValueAsString(part.get("functionResponse").path("response")));
                }
            }
        }
        if (!answered) {
            int n = 0;
            while (calls.find()) {
                ObjectNode call = MAPPER.createObjectNode().put("id", "call_" + n++).put("name", calls.group(1));
                call.set("args", MAPPER.readTree(calls.group(2)));
                parts.add(MAPPER.createObjectNode().set("functionCall", call));
            }
            if (n > 0) {
                return parts;
            }
            parts.add(MAPPER.createObjectNode().put("text", "DONE no call"));
            return parts;
        }
        parts.add(MAPPER.createObjectNode().put("text", "DONE " + String.join(" | ", responses)));
        return parts;
    }

    /** Gemini's 400 for function responses that don't answer the model's calls before them, or null. */
    private static String check(JsonNode contents) {
        for (int i = 0; i < contents.size(); i++) {
            List<JsonNode> made = new ArrayList<>();
            for (JsonNode part : contents.get(i).path("parts")) {
                if (part.has("functionCall")) {
                    made.add(part.get("functionCall"));
                }
            }
            if (!"model".equals(contents.get(i).path("role").asText()) || made.isEmpty()) {
                continue;
            }
            if (i + 1 >= contents.size()) {
                return "A model turn of function calls is the last content: its responses are missing.";
            }
            List<JsonNode> answers = new ArrayList<>();
            for (JsonNode part : contents.get(i + 1).path("parts")) {
                if (part.has("functionResponse")) {
                    answers.add(part.get("functionResponse"));
                }
            }
            if (answers.size() != made.size()) {
                return "Please ensure that the number of function response parts is equal to the number of function "
                        + "call parts of the function call turn.";
            }
            for (int j = 0; j < made.size(); j++) {
                JsonNode call = made.get(j);
                JsonNode answer = answers.get(j);
                boolean wrongId = answer.has("id") && !answer.path("id").asText().equals(call.path("id").asText());
                if (!answer.path("name").asText().equals(call.path("name").asText()) || wrongId) {
                    return "Function response " + answer.path("name").asText() + " answers no call: "
                            + call.path("name").asText();
                }
                if (!answer.path("response").isObject()) {
                    return "A function response's response must be an object.";
                }
            }
        }
        return null;
    }

    private static String text(JsonNode content) {
        StringBuilder text = null;
        for (JsonNode part : content.path("parts")) {
            if (part.has("text")) {
                text = (text == null ? new StringBuilder() : text).append(part.get("text").asText());
            }
        }
        return text == null ? null : text.toString();
    }

    private static ObjectNode response(ArrayNode parts, boolean last) {
        ObjectNode candidate = MAPPER.createObjectNode();
        candidate.set("content", MAPPER.createObjectNode().put("role", "model").set("parts", parts));
        candidate.put("index", 0);
        if (last) {
            candidate.put("finishReason", "STOP");
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.set("candidates", MAPPER.createArrayNode().add(candidate));
        response.put("modelVersion", "scripted");
        return response;
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
