package dev.datacircle.genai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A fake of the Datacircle API on 127.0.0.1, with each request's path and query logged. */
final class FakeDatacircle {

    static HttpServer server;
    static final List<String> requests = new CopyOnWriteArrayList<>();

    private static final String APIS = "[{\"api\": \"Employee email\", \"slug\": \"email-finder\", \"plans\": 5444,"
            + " \"providers\": 300, \"best\": {\"provider\": \"Emelia\", \"plan\": \"100,000 credits per month\","
            + " \"score\": 76.6, \"perThousand\": 4.99}},"
            + " {\"api\": \"Company profile\", \"slug\": \"linkedin-company\", \"plans\": 1, \"providers\": 1,"
            + " \"best\": null}]";
    private static final String BEST = "{\"api\": \"Employee email\", \"slug\": \"email-finder\", \"total\": 5444,"
            + " \"sheetPage\": \"https://datacircle.dev/email-finder-api-pricing\","
            + " \"plans\": [{\"provider\": \"Emelia\", \"plan\": \"Pro\", \"api\": \"Employee email\", \"rank\": 1,"
            + " \"score\": 76.64, \"perThousand\": 4.99, \"per\": \"call\", \"notFoundPerThousand\": 0,"
            + " \"billing\": \"monthly\", \"latencyMs\": 812,"
            + " \"page\": \"https://datacircle.dev/find-email-apis/emelia/pro\"}]}";
    private static final String PLANS = "{\"total\": 2, \"offset\": 1, \"plans\": [{\"provider\": \"Hunter\","
            + " \"plan\": \"Starter\", \"api\": \"People search\", \"rank\": 18, \"score\": null,"
            + " \"perThousand\": null, \"page\": \"https://datacircle.dev/p/hunter\"}]}";
    private static final String PROVIDER = "{\"provider\": \"Bright Data\", \"slug\": \"bright-data\","
            + " \"website\": \"https://brightdata.com\","
            + " \"apis\": [{\"api\": \"Employee profile\", \"slug\": \"linkedin-profile\", \"rank\": 3,"
            + " \"plans\": [{\"plan\": \"Pay as you go\", \"score\": 60, \"perThousand\": 1.5, \"per\": \"record\"},"
            + " {\"plan\": \"Growth\", \"score\": 71.25, \"perThousand\": 1.05, \"per\": \"record\"}]},"
            + " {\"api\": \"SERP\", \"slug\": \"serp\", \"rank\": null, \"plans\": []}]}";

    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requests.add(target);
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if (path.equals("/v1/apis")) {
                body = APIS;
            } else if (path.equals("/v1/apis/email-finder/best")) {
                body = BEST;
            } else if (path.equals("/v1/apis/broken/best")) {
                body = "{\"api\": ";
            } else if (path.equals("/v1/plans") && target.contains("query=busy")) {
                status = 429;
                body = "{\"error\": \"too many calls\"}";
            } else if (path.equals("/v1/plans")) {
                body = PLANS;
            } else if (path.equals("/v1/providers/bright-data")) {
                body = PROVIDER;
            } else {
                status = 404;
                body = "{\"error\": \"no API has the slug nope\", \"slugs\": [\"email-finder\"]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    static void stop() {
        server.stop(0);
    }

    /** Tools on the fake API, its log of requests cleared. */
    static DatacircleTools tools() {
        requests.clear();
        return new DatacircleTools(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", Duration.ofSeconds(5));
    }
}
