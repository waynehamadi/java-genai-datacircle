# Datacircle functions for the Google Gen AI Java SDK

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives Gemini four functions that call Datacircle's open API, https://api.datacircle.dev/v1. You use them with the [Google Gen AI Java SDK](https://github.com/googleapis/java-genai). You need no key and no account.

| Function | What the function gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/java-genai-datacircle). With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>java-genai-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

With Gradle:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.waynehamadi:java-genai-datacircle:0.1.0")
}
```

You need Java 11 or later. Your app needs `com.google.genai:google-genai` version 1.0.0 or later. The library adds no other dependency.

## Let the SDK call the functions

Put `DatacircleFunctions.tool()` in the config's tools. The model calls a Datacircle function. The SDK runs that call and sends the answer back to the model.

```java
import com.google.genai.Chat;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import dev.datacircle.genai.DatacircleFunctions;
import java.util.List;

Client client = new Client();
GenerateContentConfig config = GenerateContentConfig.builder()
        .tools(List.of(DatacircleFunctions.tool()))
        .build();

Chat chat = client.chats.create("gemini-3.8-flash", config);
System.out.println(chat.sendMessage("Who are the 3 best providers for the email-finder API?").text());
```

`client.models.generateContent("gemini-3.8-flash", question, config)` runs the functions too. The SDK's next major version will run functions in chats only.

The SDK sends the model only the name of each function and the names and types of the function's parameters. The model sees no description. The SDK makes every parameter required. Send an empty string or a limit of 0 to get the default value. To give the model each function's description, run the calls in your own loop. The next section shows the loop.

### Add your own functions

Put your own methods in a tool of their own, beside the Datacircle tool:

```java
public class MyFunctions {
    public static String get_time(String city) {
        return clock.timeIn(city);
    }
}

Tool own = Tool.builder()
        .functions(List.of(MyFunctions.class.getMethod("get_time", String.class)))
        .build();
GenerateContentConfig config = GenerateContentConfig.builder()
        .tools(List.of(DatacircleFunctions.tool(), own))
        .build();
```

The SDK runs only public static methods of public classes. Compile your methods with `-parameters`. The SDK reads the parameter names. Give the config your own functions as methods, never as function declarations. One function declaration in any tool of the config stops every function from running. You can also run your functions in your own loop.

## Run the calls in your own loop

`DatacircleTools` gives the four function declarations, each with a description. `DatacircleTools` also runs the calls. After each call to the model, add the model's turn to your contents. Then add a user turn with the parts from `datacircle.responses(calls)`. Call the model again. Each part answers one call, with the call's name and id.

```java
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import dev.datacircle.genai.DatacircleTools;

DatacircleTools datacircle = new DatacircleTools();
GenerateContentConfig config = GenerateContentConfig.builder()
        .tools(List.of(datacircle.tool()))
        .build();

List<Content> contents = new ArrayList<>();
contents.add(Content.fromParts(Part.fromText("Who are the 3 best providers for the email-finder API?")));
while (true) {
    GenerateContentResponse response = client.models.generateContent("gemini-3.8-flash", contents, config);
    List<FunctionCall> calls = response.functionCalls();
    if (calls == null || calls.isEmpty()) {
        System.out.println(response.text());
        break;
    }
    contents.add(response.candidates().get().get(0).content().get());
    contents.add(Content.builder().role("user").parts(datacircle.responses(calls)).build());
}
```

With `generateContentStream`, add the parts of each chunk to the model's turn. Add the calls of each chunk to `calls`.

### Add your own functions

Put your declarations in a tool of their own, beside `datacircle.tool()`. Give `responses` a function that runs your calls. The function gets each call that is not a Datacircle call. The function gives the response as a map:

```java
GenerateContentConfig config = GenerateContentConfig.builder()
        .tools(List.of(datacircle.tool(), Tool.builder().functionDeclarations(List.of(getTime)).build()))
        .build();

// In the loop:
List<Part> parts = datacircle.responses(calls, call -> call.name().orElse("").equals("get_time")
        ? Map.of("output", clock.timeIn((String) call.args().get().get("city")))
        : null);
```

Gemini wants an answer to every call of a turn. Your function can give `null` for a call. `responses` then answers Gemini with an unknown function name error. `responses` answers an exception from your function with an error. The error holds the exception's message.

## Function answers

Each function answers in short text: one line per API or plan. Each plan's line links to the plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,464 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

A failed call answers with one line. The line starts with `Error:` and gives the reason. In your own loop, `responses` puts the reason in the response's `error` field. `responses` puts each good answer in the `output` field. No function throws an exception. The model reads the error line and can call again.

You can call a function yourself: `datacircle.bestPlans("email-finder", 3)`, `datacircle.listApis("email")`, `datacircle.searchPlans(null, "hunter", "price", 5, null)` and `datacircle.provider("Bright Data")`.

## Options

`new DatacircleTools(baseUrl, timeout)` sets another base URL or another timeout. The defaults are `https://api.datacircle.dev/v1` and 30 seconds.

You can make 60 calls a minute to the API from one IP address. The API's spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./mvnw test                               # offline, on a local server
./mvnw test -Dgenai.version=2.6.0         # on another SDK version
```

## License

MIT
