/*
 * Copyright 2013-2020 The OpenZipkin Authors
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
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.Sampler;
import brave.sampler.SamplerFunction;
import brave.sampler.SamplerFunctions;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RpcServerHandlerTest {
  List<zipkin2.Span> spans = new ArrayList<>();
  TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(1L).sampled(true).build();

  RpcTracing rpcTracing;
  RpcServerHandler handler;

  @Mock Extractor<RpcServerRequest> extractor;
  @Mock(answer = CALLS_REAL_METHODS) RpcServerRequest request;
  @Mock(answer = CALLS_REAL_METHODS) RpcServerResponse response;

  @Before public void init() {
    init(rpcTracingBuilder(tracingBuilder()));
  }

  void init(RpcTracing.Builder builder) {
    close();
    rpcTracing = builder.build();
    handler = RpcServerHandler.create(rpcTracing, extractor);
    when(request.service()).thenReturn("zipkin.proto3.SpanService");
    when(request.method()).thenReturn("Report");
  }

  RpcTracing.Builder rpcTracingBuilder(Tracing.Builder tracingBuilder) {
    return RpcTracing.newBuilder(tracingBuilder.build());
  }

  Tracing.Builder tracingBuilder() {
    return Tracing.newBuilder().spanReporter(spans::add);
  }

  @After public void close() {
    Tracing current = Tracing.current();
    if (current != null) current.close();
  }

  @Test public void handleReceive_traceIdSamplerSpecialCased() {
    when(extractor.extract(request)).thenReturn(TraceContextOrSamplingFlags.EMPTY);
    Sampler sampler = mock(Sampler.class);

    init(rpcTracingBuilder(tracingBuilder().sampler(sampler))
      .serverSampler(SamplerFunctions.deferDecision()));

    assertThat(handler.handleReceive(request).isNoop()).isTrue();

    verify(sampler).isSampled(anyLong());
  }

  @Test public void handleReceive_neverSamplerSpecialCased() {
    when(extractor.extract(request)).thenReturn(TraceContextOrSamplingFlags.EMPTY);
    Sampler sampler = mock(Sampler.class);

    init(rpcTracingBuilder(tracingBuilder().sampler(sampler))
      .serverSampler(SamplerFunctions.neverSample()));

    assertThat(handler.handleReceive(request).isNoop()).isTrue();

    verifyNoMoreInteractions(sampler);
  }

  @Test public void handleReceive_samplerSeesRpcServerRequest() {
    when(extractor.extract(request)).thenReturn(TraceContextOrSamplingFlags.EMPTY);
    SamplerFunction<RpcRequest> serverSampler = mock(SamplerFunction.class);
    init(rpcTracingBuilder(tracingBuilder()).serverSampler(serverSampler));

    handler.handleReceive(request);

    verify(serverSampler).trySample(request);
  }

  @Test public void externalTimestamps() {
    when(extractor.extract(request)).thenReturn(TraceContextOrSamplingFlags.EMPTY);
    when(request.startTimestamp()).thenReturn(123000L);
    when(response.finishTimestamp()).thenReturn(124000L);

    Span span = handler.handleReceive(request);
    handler.handleSend(response, null, span);

    assertThat(spans.get(0).durationAsLong()).isEqualTo(1000L);
  }

  @Test public void handleSend_finishesSpanEvenIfUnwrappedNull() {
    Span span = mock(Span.class);
    when(span.context()).thenReturn(context);
    when(span.customizer()).thenReturn(span);

    handler.handleSend(mock(RpcServerResponse.class), null, span);

    verify(span).isNoop();
    verify(span).context();
    verify(span).customizer();
    verify(span).finish();
    verifyNoMoreInteractions(span);
  }

  @Test public void handleSend_finishesSpanEvenIfUnwrappedNull_withError() {
    Span span = mock(Span.class);
    when(span.context()).thenReturn(context);
    when(span.customizer()).thenReturn(span);

    Exception error = new RuntimeException("peanuts");

    handler.handleSend(mock(RpcServerResponse.class), error, span);

    verify(span).isNoop();
    verify(span).context();
    verify(span).customizer();
    verify(span).error(error);
    verify(span).finish();
    verifyNoMoreInteractions(span);
  }

  @Test public void handleSend_oneOfResponseError() {
    Span span = mock(Span.class);

    assertThatThrownBy(() -> handler.handleSend(null, null, span))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Either the response or error parameters may be null, but not both");
  }
}
