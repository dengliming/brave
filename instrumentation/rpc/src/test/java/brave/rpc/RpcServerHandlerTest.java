/*
 * Copyright 2013-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.rpc;

import brave.Span;
import brave.SpanCustomizer;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.SamplerFunction;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RpcServerHandlerTest {
  List<zipkin2.Span> spans = new ArrayList<>();

  RpcTracing rpcTracing;
  RpcServerHandler handler;

  @Mock SamplerFunction<RpcRequest> sampler;
  @Mock TraceContext.Extractor<RpcServerRequest> extractor;
  @Spy RpcServerParser parser = spy(new RpcServerParser());
  @Mock(answer = CALLS_REAL_METHODS) RpcServerRequest request;
  @Mock(answer = CALLS_REAL_METHODS) RpcServerResponse response;

  @Before public void init() {
    rpcTracing = RpcTracing.newBuilder(Tracing.newBuilder().spanReporter(spans::add).build())
      .serverSampler(sampler).serverParser(parser).build();
    handler = RpcServerHandler.create(rpcTracing, extractor);
  }

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void handleStart_parsesRpcMethod() {
    brave.Span span = mock(brave.Span.class);
    brave.SpanCustomizer customizer = mock(brave.SpanCustomizer.class);
    when(span.kind(Span.Kind.SERVER)).thenReturn(span);
    when(request.method()).thenReturn("users.UserService/GetUserToken");
    when(span.customizer()).thenReturn(customizer);

    handler.handleStart(request, span);

    verify(customizer).name("users.UserService/GetUserToken");
    verify(customizer).tag("rpc.method", "users.UserService/GetUserToken");
    verifyNoMoreInteractions(customizer);
  }

  @Test public void handleReceive_defaultRequest() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    // request sampler abstains (trace ID sampler will say true)
    when(sampler.trySample(request)).thenReturn(null);

    Span newSpan = handler.handleReceive(request);
    assertThat(newSpan.isNoop()).isFalse();
    assertThat(newSpan.context().shared()).isFalse();
  }

  @Test public void handleReceive_defaultsToMakeNewTrace() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    // request sampler abstains (trace ID sampler will say true)
    when(sampler.trySample(request)).thenReturn(null);

    Span newSpan = handler.handleReceive(request);
    assertThat(newSpan.isNoop()).isFalse();
    assertThat(newSpan.context().shared()).isFalse();
  }

  @Test public void handleReceive_reusesTraceId() {
    rpcTracing = RpcTracing.newBuilder(
      Tracing.newBuilder().supportsJoin(false).spanReporter(spans::add).build())
      .serverSampler(sampler).serverParser(parser).build();

    Tracer tracer = rpcTracing.tracing().tracer();
    handler = RpcServerHandler.create(rpcTracing, extractor);

    TraceContext incomingContext = tracer.nextSpan().context();
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(incomingContext));

    assertThat(handler.handleReceive(request).context())
      .extracting(TraceContext::traceId, TraceContext::parentId, TraceContext::shared)
      .containsOnly(incomingContext.traceId(), incomingContext.spanId(), false);
  }

  @Test public void handleReceive_reusesSpanIds() {
    TraceContext incomingContext = rpcTracing.tracing().tracer().nextSpan().context();
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(incomingContext));

    assertThat(handler.handleReceive(request).context())
      .isEqualTo(incomingContext.toBuilder().shared(true).build());
  }

  @Test public void handleReceive_honorsSamplingFlags() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.NOT_SAMPLED));

    assertThat(handler.handleReceive(request).isNoop())
      .isTrue();
  }

  @Test public void handleReceive_makesRequestBasedSamplingDecision_flags() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    // request sampler says false eventhough trace ID sampler would have said true
    when(sampler.trySample(request)).thenReturn(false);

    assertThat(handler.handleReceive(request).isNoop())
      .isTrue();
  }

  @Test public void handleReceive_makesRequestBasedSamplingDecision_context() {
    Tracer tracer = rpcTracing.tracing().tracer();
    TraceContext incomingContext = tracer.nextSpan().context().toBuilder().sampled(null).build();
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(incomingContext));

    // request sampler says false eventhough trace ID sampler would have said true
    when(sampler.trySample(request)).thenReturn(false);

    assertThat(handler.handleReceive(request).isNoop())
      .isTrue();
  }

  @Test public void externalTimestamps() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    when(sampler.trySample(request)).thenReturn(null);

    when(request.startTimestamp()).thenReturn(123000L);
    when(response.finishTimestamp()).thenReturn(124000L);

    Span span = handler.handleReceive(request);
    handler.handleSend(response, null, span);

    assertThat(spans.get(0).durationAsLong()).isEqualTo(1000L);
  }

  @Test public void handleReceive() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    handler.handleReceive(request);

    verify(sampler).trySample(request);
  }

  @Test public void handleReceive_parser() {
    when(extractor.extract(request))
      .thenReturn(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    when(sampler.trySample(request)).thenReturn(null);

    handler.handleReceive(request);

    verify(parser).request(eq(request), any(SpanCustomizer.class));
  }
}
