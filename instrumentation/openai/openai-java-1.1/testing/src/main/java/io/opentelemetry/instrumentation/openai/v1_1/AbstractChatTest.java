/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.openai.v1_1;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_FREQUENCY_PENALTY;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_PRESENCE_PENALTY;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_SEED;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_STOP_SEQUENCES;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_TOP_P;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_SYSTEM;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_TOKEN_TYPE;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues.OPENAI;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiTokenTypeIncubatingValues.COMPLETION;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiTokenTypeIncubatingValues.INPUT;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonObject;
import com.openai.core.JsonValue;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatText;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.recording.RecordingExtension;
import io.opentelemetry.sdk.testing.assertj.SpanDataAssert;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(AbstractChatTest.TestType.class)
public abstract class AbstractChatTest {
  enum TestType {
    SYNC,
    SYNC_FROM_ASYNC,
    ASYNC,
    ASYNC_FROM_SYNC,
  }

  protected static final String INSTRUMENTATION_NAME = "io.opentelemetry.openai-java-1.1";

  private static final String API_URL = "https://api.openai.com/v1";

  protected static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");

  protected static final String TEST_CHAT_MODEL = "gpt-4o-mini";
  protected static final String TEST_CHAT_RESPONSE_MODEL = "gpt-4o-mini-2024-07-18";
  protected static final String TEST_CHAT_INPUT =
      "Answer in up to 3 words: Which ocean contains Bouvet Island?";

  @RegisterExtension static final RecordingExtension recording = new RecordingExtension(API_URL);

  protected abstract InstrumentationExtension getTesting();

  protected abstract OpenAIClient wrap(OpenAIClient client);

  protected abstract OpenAIClientAsync wrap(OpenAIClientAsync client);

  protected final OpenAIClient getRawClient() {
    OpenAIOkHttpClient.Builder builder =
        OpenAIOkHttpClient.builder().baseUrl("http://localhost:" + recording.getPort());
    if (recording.isRecording()) {
      builder.apiKey(System.getenv("OPENAI_API_KEY"));
    } else {
      builder.apiKey("unused");
    }
    return builder.build();
  }

  protected final OpenAIClientAsync getRawClientAsync() {
    OpenAIOkHttpClientAsync.Builder builder =
        OpenAIOkHttpClientAsync.builder().baseUrl("http://localhost:" + recording.getPort());
    if (recording.isRecording()) {
      builder.apiKey(System.getenv("OPENAI_API_KEY"));
    } else {
      builder.apiKey("unused");
    }
    return builder.build();
  }

  protected final OpenAIClient getClient() {
    return wrap(getRawClient());
  }

  protected final OpenAIClientAsync getClientAsync() {
    return wrap(getRawClientAsync());
  }

  protected abstract List<Consumer<SpanDataAssert>> maybeWithTransportSpan(
      Consumer<SpanDataAssert> span);

  @Parameter TestType testType;

  protected final ChatCompletion doCompletions(ChatCompletionCreateParams params) {
    return doCompletions(params, getClient(), getClientAsync());
  }

  protected final ChatCompletion doCompletions(
      ChatCompletionCreateParams params, OpenAIClient client, OpenAIClientAsync clientAsync) {
    switch (testType) {
      case SYNC:
        return client.chat().completions().create(params);
      case SYNC_FROM_ASYNC:
        return clientAsync.sync().chat().completions().create(params);
      case ASYNC:
      case ASYNC_FROM_SYNC:
        OpenAIClientAsync cl = testType == TestType.ASYNC ? clientAsync : client.async();
        try {
          return cl.chat()
              .completions()
              .create(params)
              .thenApply(
                  res -> {
                    assertThat(Span.fromContextOrNull(Context.current())).isNull();
                    return res;
                  })
              .join();
        } catch (CompletionException e) {
          if (e.getCause() instanceof OpenAIIoException) {
            throw ((OpenAIIoException) e.getCause());
          }
          throw e;
        }
    }
    throw new AssertionError();
  }

  protected final List<ChatCompletionChunk> doCompletionsStreaming(
      ChatCompletionCreateParams params) {
    return doCompletionsStreaming(params, getClient(), getClientAsync());
  }

  protected final List<ChatCompletionChunk> doCompletionsStreaming(
      ChatCompletionCreateParams params, OpenAIClient client, OpenAIClientAsync clientAsync) {
    switch (testType) {
      case SYNC:
        try (StreamResponse<ChatCompletionChunk> result =
            client.chat().completions().createStreaming(params)) {
          return result.stream().collect(Collectors.toList());
        }
      case SYNC_FROM_ASYNC:
        try (StreamResponse<ChatCompletionChunk> result =
            clientAsync.sync().chat().completions().createStreaming(params)) {
          return result.stream().collect(Collectors.toList());
        }
      case ASYNC:
      case ASYNC_FROM_SYNC:
        {
          OpenAIClientAsync cl = testType == TestType.ASYNC ? clientAsync : client.async();
          AsyncStreamResponse<ChatCompletionChunk> stream =
              cl.chat().completions().createStreaming(params);
          List<ChatCompletionChunk> result = new ArrayList<>();
          stream.subscribe(result::add);
          try {
            stream.onCompleteFuture().join();
          } catch (CompletionException e) {
            if (e.getCause() instanceof OpenAIIoException) {
              throw ((OpenAIIoException) e.getCause());
            }
            throw e;
          }
          return result;
        }
    }
    throw new AssertionError();
  }

  @Test
  void basic() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .build();

    ChatCompletion response = doCompletions(params);
    String content = "Atlantic Ocean";
    assertThat(response.choices().get(0).message().content()).hasValue(content);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 22L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 2L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(22.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(2.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log -> {
              log.hasAttributesSatisfyingExactly(
                      equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                  .hasSpanContext(spanCtx)
                  .hasBody(
                      Value.of(
                          KeyValue.of("finish_reason", Value.of("stop")),
                          KeyValue.of("index", Value.of(0)),
                          KeyValue.of(
                              "message", Value.of(KeyValue.of("content", Value.of(content))))));
            });
  }

  @Test
  void testDeveloperMessage() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(
                Arrays.asList(
                    createDeveloperMessage(
                        "You are an assistant which just answers every query with tomato"),
                    createUserMessage("Say something")))
            .model(TEST_CHAT_MODEL)
            .build();

    ChatCompletion response = doCompletions(params);
    String content = "Tomato.";
    assertThat(response.choices().get(0).message().content()).hasValue(content);

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.system.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("role", Value.of("developer")),
                            KeyValue.of(
                                "content",
                                Value.of(
                                    "You are an assistant which just answers every query with tomato")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of("Say something")))),
            log -> {
              log.hasAttributesSatisfyingExactly(
                      equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                  .hasSpanContext(spanCtx)
                  .hasBody(
                      Value.of(
                          KeyValue.of("finish_reason", Value.of("stop")),
                          KeyValue.of("index", Value.of(0)),
                          KeyValue.of(
                              "message", Value.of(KeyValue.of("content", Value.of(content))))));
            });
  }

  @Test
  void allTheClientOptions() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .frequencyPenalty(0.0)
            .maxCompletionTokens(100)
            .presencePenalty(0.0)
            .temperature(1.0)
            .topP(1.0)
            .stopOfStrings(singletonList("foo"))
            .seed(100L)
            .responseFormat(ResponseFormatText.builder().build())
            .build();

    ChatCompletion response = doCompletions(params);
    String content = "Southern Ocean.";
    assertThat(response.choices().get(0).message().content()).hasValue(content);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                equalTo(GEN_AI_REQUEST_SEED, 100L),
                                equalTo(GEN_AI_REQUEST_FREQUENCY_PENALTY, 0.0),
                                equalTo(GEN_AI_REQUEST_MAX_TOKENS, 100L),
                                equalTo(GEN_AI_REQUEST_PRESENCE_PENALTY, 0.0),
                                equalTo(GEN_AI_REQUEST_STOP_SEQUENCES, singletonList("foo")),
                                equalTo(GEN_AI_REQUEST_TEMPERATURE, 1.0),
                                equalTo(GEN_AI_REQUEST_TOP_P, 1.0),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 22L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 3L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(22.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(3.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log -> {
              log.hasAttributesSatisfyingExactly(
                      equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                  .hasSpanContext(spanCtx)
                  .hasBody(
                      Value.of(
                          KeyValue.of("finish_reason", Value.of("stop")),
                          KeyValue.of("index", Value.of(0)),
                          KeyValue.of(
                              "message", Value.of(KeyValue.of("content", Value.of(content))))));
            });
  }

  @Test
  void multipleChoices() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .n(2)
            .build();

    ChatCompletion response = doCompletions(params);
    String content1 = "South Atlantic Ocean.";
    assertThat(response.choices().get(0).message().content()).hasValue(content1);
    String content2 = "Atlantic Ocean.";
    assertThat(response.choices().get(1).message().content()).hasValue(content2);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop", "stop")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 22L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 7L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(22.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(7.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message", Value.of(KeyValue.of("content", Value.of(content1)))))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(1)),
                            KeyValue.of(
                                "message", Value.of(KeyValue.of("content", Value.of(content2)))))));
  }

  @Test
  void toolCalls() {
    List<ChatCompletionMessageParam> chatMessages = new ArrayList<>();
    chatMessages.add(createSystemMessage("You are a helpful assistant providing weather updates."));
    chatMessages.add(createUserMessage("What is the weather in New York City and London?"));

    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(chatMessages)
            .model(TEST_CHAT_MODEL)
            .addTool(buildGetWeatherToolDefinition())
            .build();

    ChatCompletion response = doCompletions(params);

    assertThat(response.choices().get(0).message().content()).isEmpty();

    List<ChatCompletionMessageToolCall> toolCalls =
        response.choices().get(0).message().toolCalls().get();
    assertThat(toolCalls).hasSize(2);
    String newYorkCallId =
        toolCalls.stream()
            .filter(call -> call.function().arguments().contains("New York"))
            .map(ChatCompletionMessageToolCall::id)
            .findFirst()
            .get();
    String londonCallId =
        toolCalls.stream()
            .filter(call -> call.function().arguments().contains("London"))
            .map(ChatCompletionMessageToolCall::id)
            .findFirst()
            .get();

    assertThat(newYorkCallId).startsWith("call_");
    assertThat(londonCallId).startsWith("call_");

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("tool_calls")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 67L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 46L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(67)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(46.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.system.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of(
                                    "You are a helpful assistant providing weather updates.")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of("What is the weather in New York City and London?")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("tool_calls")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message",
                                Value.of(
                                    KeyValue.of(
                                        "tool_calls",
                                        Value.of(
                                            Value.of(
                                                KeyValue.of(
                                                    "function",
                                                    Value.of(
                                                        KeyValue.of(
                                                            "name", Value.of("get_weather")),
                                                        KeyValue.of(
                                                            "arguments",
                                                            Value.of(
                                                                "{\"location\": \"New York City\"}")))),
                                                KeyValue.of("id", Value.of(newYorkCallId)),
                                                KeyValue.of("type", Value.of("function"))),
                                            Value.of(
                                                KeyValue.of(
                                                    "function",
                                                    Value.of(
                                                        KeyValue.of(
                                                            "name", Value.of("get_weather")),
                                                        KeyValue.of(
                                                            "arguments",
                                                            Value.of(
                                                                "{\"location\": \"London\"}")))),
                                                KeyValue.of("id", Value.of(londonCallId)),
                                                KeyValue.of("type", Value.of("function"))))))))));

    getTesting().clearData();

    ChatCompletionMessageParam assistantMessage = createAssistantMessage(toolCalls);

    chatMessages.add(assistantMessage);
    chatMessages.add(createToolMessage("25 degrees and sunny", newYorkCallId));
    chatMessages.add(createToolMessage("15 degrees and raining", londonCallId));

    ChatCompletion fullCompletion =
        getClient()
            .chat()
            .completions()
            .create(
                ChatCompletionCreateParams.builder()
                    .messages(chatMessages)
                    .model(TEST_CHAT_MODEL)
                    .build());

    ChatCompletion.Choice finalChoice = fullCompletion.choices().get(0);
    String finalAnswer = finalChoice.message().content().get();

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 99L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 25L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(99)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(25.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx1 = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.system.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of(
                                    "You are a helpful assistant providing weather updates.")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of("What is the weather in New York City and London?")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.assistant.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "tool_calls",
                                Value.of(
                                    Value.of(
                                        KeyValue.of(
                                            "function",
                                            Value.of(
                                                KeyValue.of("name", Value.of("get_weather")),
                                                KeyValue.of(
                                                    "arguments",
                                                    Value.of(
                                                        "{\"location\": \"New York City\"}")))),
                                        KeyValue.of("id", Value.of(newYorkCallId)),
                                        KeyValue.of("type", Value.of("function"))),
                                    Value.of(
                                        KeyValue.of(
                                            "function",
                                            Value.of(
                                                KeyValue.of("name", Value.of("get_weather")),
                                                KeyValue.of(
                                                    "arguments",
                                                    Value.of("{\"location\": \"London\"}")))),
                                        KeyValue.of("id", Value.of(londonCallId)),
                                        KeyValue.of("type", Value.of("function"))))))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.tool.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("id", Value.of(newYorkCallId)),
                            KeyValue.of("content", Value.of("25 degrees and sunny")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.tool.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("id", Value.of(londonCallId)),
                            KeyValue.of("content", Value.of("15 degrees and raining")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message",
                                Value.of(KeyValue.of("content", Value.of(finalAnswer)))))));
  }

  @Test
  void connectionError() {
    OpenAIClient client =
        wrap(
            OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:9999/v5")
                .apiKey("testing")
                .maxRetries(0)
                .build());
    OpenAIClientAsync clientAsync =
        wrap(
            OpenAIOkHttpClientAsync.builder()
                .baseUrl("http://localhost:9999/v5")
                .apiKey("testing")
                .maxRetries(0)
                .build());

    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .build();

    Throwable thrown = catchThrowable(() -> doCompletions(params, client, clientAsync));
    assertThat(thrown).isInstanceOf(OpenAIIoException.class);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasException(thrown)
                                .hasAttributesSatisfyingExactly(
                                    equalTo(GEN_AI_SYSTEM, OPENAI),
                                    equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                    equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))));
  }

  @Test
  void stream() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .build();

    List<ChatCompletionChunk> chunks = doCompletionsStreaming(params);

    String fullMessage =
        chunks.stream()
            .map(
                cc -> {
                  if (cc.choices().isEmpty()) {
                    return Optional.<String>empty();
                  }
                  return cc.choices().get(0).delta().content();
                })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.joining());

    String content = "Atlantic Ocean.";
    assertThat(fullMessage).isEqualTo(content);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop"))))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL,
                                                TEST_CHAT_RESPONSE_MODEL)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log -> {
              log.hasAttributesSatisfyingExactly(
                      equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                  .hasSpanContext(spanCtx)
                  .hasBody(
                      Value.of(
                          KeyValue.of("finish_reason", Value.of("stop")),
                          KeyValue.of("index", Value.of(0)),
                          KeyValue.of(
                              "message", Value.of(KeyValue.of("content", Value.of(content))))));
            });
  }

  @Test
  void streamIncludeUsage() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            .build();

    List<ChatCompletionChunk> chunks = doCompletionsStreaming(params);

    String fullMessage =
        chunks.stream()
            .map(
                cc -> {
                  if (cc.choices().isEmpty()) {
                    return Optional.<String>empty();
                  }
                  return cc.choices().get(0).delta().content();
                })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.joining());

    String content = "South Atlantic Ocean";
    assertThat(fullMessage).isEqualTo(content);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop")),
                                equalTo(GEN_AI_USAGE_INPUT_TOKENS, 22L),
                                equalTo(GEN_AI_USAGE_OUTPUT_TOKENS, 3L)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL)))),
            metric ->
                metric
                    .hasName("gen_ai.client.token.usage")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSum(22.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, INPUT)),
                                point ->
                                    point
                                        .hasSum(3.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                            equalTo(GEN_AI_TOKEN_TYPE, COMPLETION)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log -> {
              log.hasAttributesSatisfyingExactly(
                      equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                  .hasSpanContext(spanCtx)
                  .hasBody(
                      Value.of(
                          KeyValue.of("finish_reason", Value.of("stop")),
                          KeyValue.of("index", Value.of(0)),
                          KeyValue.of(
                              "message", Value.of(KeyValue.of("content", Value.of(content))))));
            });
  }

  @Test
  void streamMultipleChoices() {
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .n(2)
            .build();

    List<ChatCompletionChunk> chunks = doCompletionsStreaming(params);

    StringBuilder content1Builder = new StringBuilder();
    StringBuilder content2Builder = new StringBuilder();
    for (ChatCompletionChunk chunk : chunks) {
      if (chunk.choices().isEmpty()) {
        continue;
      }
      ChatCompletionChunk.Choice choice = chunk.choices().get(0);
      switch ((int) choice.index()) {
        case 0:
          content1Builder.append(choice.delta().content().orElse(""));
          break;
        case 1:
          content2Builder.append(choice.delta().content().orElse(""));
          break;
        default:
          // fallthrough
      }
    }

    String content1 = "Atlantic Ocean.";
    assertThat(content1Builder.toString()).isEqualTo(content1);
    String content2 = "South Atlantic Ocean.";
    assertThat(content2Builder.toString()).isEqualTo(content2);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop", "stop"))))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL,
                                                TEST_CHAT_RESPONSE_MODEL)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message", Value.of(KeyValue.of("content", Value.of(content1)))))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(1)),
                            KeyValue.of(
                                "message", Value.of(KeyValue.of("content", Value.of(content2)))))));
  }

  @Test
  void streamToolCalls() {
    List<ChatCompletionMessageParam> chatMessages = new ArrayList<>();
    chatMessages.add(createSystemMessage("You are a helpful assistant providing weather updates."));
    chatMessages.add(createUserMessage("What is the weather in New York City and London?"));

    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(chatMessages)
            .model(TEST_CHAT_MODEL)
            .addTool(buildGetWeatherToolDefinition())
            .build();

    List<ChatCompletionChunk> chunks = doCompletionsStreaming(params);

    List<ChatCompletionMessageToolCall> toolCalls = new ArrayList<>();

    ChatCompletionMessageToolCall.Builder currentToolCall = null;
    ChatCompletionMessageToolCall.Function.Builder currentFunction = null;
    StringBuilder currentArgs = null;

    for (ChatCompletionChunk chunk : chunks) {
      List<ChatCompletionChunk.Choice.Delta.ToolCall> calls =
          chunk.choices().get(0).delta().toolCalls().orElse(emptyList());
      if (calls.isEmpty()) {
        continue;
      }
      for (ChatCompletionChunk.Choice.Delta.ToolCall call : calls) {
        if (call.id().isPresent()) {
          if (currentToolCall != null) {
            currentFunction.arguments(currentArgs.toString());
            currentToolCall.function(currentFunction.build());
            toolCalls.add(currentToolCall.build());
          }
          currentToolCall = ChatCompletionMessageToolCall.builder().id(call.id().get());
          currentFunction = ChatCompletionMessageToolCall.Function.builder();
          currentArgs = new StringBuilder();
        }
        if (call.function().isPresent()) {
          if (call.function().get().name().isPresent()) {
            currentFunction.name(call.function().get().name().get());
          }
          if (call.function().get().arguments().isPresent()) {
            currentArgs.append(call.function().get().arguments().get());
          }
        }
      }
    }
    if (currentToolCall != null) {
      currentFunction.arguments(currentArgs.toString());
      currentToolCall.function(currentFunction.build());
      toolCalls.add(currentToolCall.build());
    }

    String newYorkCallId =
        toolCalls.stream()
            .filter(call -> call.function().arguments().contains("New York"))
            .map(ChatCompletionMessageToolCall::id)
            .findFirst()
            .get();
    String londonCallId =
        toolCalls.stream()
            .filter(call -> call.function().arguments().contains("London"))
            .map(ChatCompletionMessageToolCall::id)
            .findFirst()
            .get();

    assertThat(newYorkCallId).startsWith("call_");
    assertThat(londonCallId).startsWith("call_");

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("tool_calls"))))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL,
                                                TEST_CHAT_RESPONSE_MODEL)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.system.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of(
                                    "You are a helpful assistant providing weather updates.")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of("What is the weather in New York City and London?")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("tool_calls")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message",
                                Value.of(
                                    KeyValue.of(
                                        "tool_calls",
                                        Value.of(
                                            Value.of(
                                                KeyValue.of(
                                                    "function",
                                                    Value.of(
                                                        KeyValue.of(
                                                            "name", Value.of("get_weather")),
                                                        KeyValue.of(
                                                            "arguments",
                                                            Value.of(
                                                                "{\"location\": \"New York City\"}")))),
                                                KeyValue.of("id", Value.of(newYorkCallId)),
                                                KeyValue.of("type", Value.of("function"))),
                                            Value.of(
                                                KeyValue.of(
                                                    "function",
                                                    Value.of(
                                                        KeyValue.of(
                                                            "name", Value.of("get_weather")),
                                                        KeyValue.of(
                                                            "arguments",
                                                            Value.of(
                                                                "{\"location\": \"London\"}")))),
                                                KeyValue.of("id", Value.of(londonCallId)),
                                                KeyValue.of("type", Value.of("function"))))))))));

    getTesting().clearData();

    ChatCompletionMessageParam assistantMessage = createAssistantMessage(toolCalls);

    chatMessages.add(assistantMessage);
    chatMessages.add(createToolMessage("25 degrees and sunny", newYorkCallId));
    chatMessages.add(createToolMessage("15 degrees and raining", londonCallId));

    params =
        ChatCompletionCreateParams.builder()
            .messages(chatMessages)
            .model(TEST_CHAT_MODEL)
            .addTool(buildGetWeatherToolDefinition())
            .build();

    chunks = doCompletionsStreaming(params);

    String finalAnswer =
        chunks.stream()
            .map(
                cc -> {
                  if (cc.choices().isEmpty()) {
                    return Optional.<String>empty();
                  }
                  return cc.choices().get(0).delta().content();
                })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.joining());

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasAttributesSatisfyingExactly(
                                equalTo(GEN_AI_SYSTEM, OPENAI),
                                equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                satisfies(GEN_AI_RESPONSE_ID, id -> id.startsWith("chatcmpl-")),
                                equalTo(GEN_AI_RESPONSE_MODEL, TEST_CHAT_RESPONSE_MODEL),
                                satisfies(
                                    GEN_AI_RESPONSE_FINISH_REASONS,
                                    reasons -> reasons.containsExactly("stop"))))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL),
                                            equalTo(
                                                GEN_AI_RESPONSE_MODEL,
                                                TEST_CHAT_RESPONSE_MODEL)))));

    SpanContext spanCtx1 = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.system.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of(
                                    "You are a helpful assistant providing weather updates.")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "content",
                                Value.of("What is the weather in New York City and London?")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI),
                        equalTo(EVENT_NAME, "gen_ai.assistant.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of(
                                "tool_calls",
                                Value.of(
                                    Value.of(
                                        KeyValue.of(
                                            "function",
                                            Value.of(
                                                KeyValue.of("name", Value.of("get_weather")),
                                                KeyValue.of(
                                                    "arguments",
                                                    Value.of(
                                                        "{\"location\": \"New York City\"}")))),
                                        KeyValue.of("id", Value.of(newYorkCallId)),
                                        KeyValue.of("type", Value.of("function"))),
                                    Value.of(
                                        KeyValue.of(
                                            "function",
                                            Value.of(
                                                KeyValue.of("name", Value.of("get_weather")),
                                                KeyValue.of(
                                                    "arguments",
                                                    Value.of("{\"location\": \"London\"}")))),
                                        KeyValue.of("id", Value.of(londonCallId)),
                                        KeyValue.of("type", Value.of("function"))))))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.tool.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("id", Value.of(newYorkCallId)),
                            KeyValue.of("content", Value.of("25 degrees and sunny")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.tool.message"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("id", Value.of(londonCallId)),
                            KeyValue.of("content", Value.of("15 degrees and raining")))),
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.choice"))
                    .hasSpanContext(spanCtx1)
                    .hasBody(
                        Value.of(
                            KeyValue.of("finish_reason", Value.of("stop")),
                            KeyValue.of("index", Value.of(0)),
                            KeyValue.of(
                                "message",
                                Value.of(KeyValue.of("content", Value.of(finalAnswer)))))));
  }

  @Test
  void streamConnectionError() {
    OpenAIClient client =
        wrap(
            OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:9999/v5")
                .apiKey("testing")
                .maxRetries(0)
                .build());
    OpenAIClientAsync clientAsync =
        wrap(
            OpenAIOkHttpClientAsync.builder()
                .baseUrl("http://localhost:9999/v5")
                .apiKey("testing")
                .maxRetries(0)
                .build());

    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .messages(Collections.singletonList(createUserMessage(TEST_CHAT_INPUT)))
            .model(TEST_CHAT_MODEL)
            .build();

    Throwable thrown = catchThrowable(() -> doCompletionsStreaming(params, client, clientAsync));
    assertThat(thrown).isInstanceOf(OpenAIIoException.class);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    maybeWithTransportSpan(
                        span ->
                            span.hasException(thrown)
                                .hasAttributesSatisfyingExactly(
                                    equalTo(GEN_AI_SYSTEM, OPENAI),
                                    equalTo(GEN_AI_OPERATION_NAME, CHAT),
                                    equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL)))));

    getTesting()
        .waitAndAssertMetrics(
            INSTRUMENTATION_NAME,
            metric ->
                metric
                    .hasName("gen_ai.client.operation.duration")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasSumGreaterThan(0.0)
                                        .hasAttributesSatisfyingExactly(
                                            equalTo(GEN_AI_SYSTEM, "openai"),
                                            equalTo(GEN_AI_OPERATION_NAME, "chat"),
                                            equalTo(GEN_AI_REQUEST_MODEL, TEST_CHAT_MODEL)))));

    SpanContext spanCtx = getTesting().waitForTraces(1).get(0).get(0).getSpanContext();

    getTesting()
        .waitAndAssertLogRecords(
            log ->
                log.hasAttributesSatisfyingExactly(
                        equalTo(GEN_AI_SYSTEM, OPENAI), equalTo(EVENT_NAME, "gen_ai.user.message"))
                    .hasSpanContext(spanCtx)
                    .hasBody(Value.of(KeyValue.of("content", Value.of(TEST_CHAT_INPUT)))));
  }

  protected static ChatCompletionMessageParam createUserMessage(String content) {
    return ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder()
            .content(ChatCompletionUserMessageParam.Content.ofText(content))
            .build());
  }

  private static ChatCompletionMessageParam createDeveloperMessage(String content) {
    return ChatCompletionMessageParam.ofDeveloper(
        ChatCompletionDeveloperMessageParam.builder()
            .content(ChatCompletionDeveloperMessageParam.Content.ofText(content))
            .build());
  }

  protected static ChatCompletionMessageParam createSystemMessage(String content) {
    return ChatCompletionMessageParam.ofSystem(
        ChatCompletionSystemMessageParam.builder()
            .content(ChatCompletionSystemMessageParam.Content.ofText(content))
            .build());
  }

  protected static ChatCompletionMessageParam createAssistantMessage(
      List<ChatCompletionMessageToolCall> toolCalls) {
    return ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder().toolCalls(toolCalls).build());
  }

  protected static ChatCompletionTool buildGetWeatherToolDefinition() {
    Map<String, JsonValue> location = new HashMap<>();
    location.put("type", JsonValue.from("string"));
    location.put("description", JsonValue.from("The location to get the current temperature for"));

    Map<String, JsonValue> properties = new HashMap<>();
    properties.put("location", JsonObject.of(location));

    return ChatCompletionTool.builder()
        .function(
            FunctionDefinition.builder()
                .name("get_weather")
                .parameters(
                    FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty(
                            "required", JsonValue.from(Collections.singletonList("location")))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .putAdditionalProperty("properties", JsonObject.of(properties))
                        .build())
                .build())
        .build();
  }

  protected static ChatCompletionMessageParam createToolMessage(String response, String id) {
    return ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam.builder()
            .toolCallId(id)
            .content(ChatCompletionToolMessageParam.Content.ofText(response))
            .build());
  }
}
