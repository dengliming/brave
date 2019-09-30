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

import brave.ScopedSpan;
import brave.SpanCustomizer;
import brave.Tracing;
import brave.propagation.TraceContext;
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
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RpcClientHandlerTest {
  List<Span> spans = new ArrayList<>();

  RpcTracing rpcTracing;
  RpcClientHandler handler;

  @Mock SamplerFunction<RpcRequest> sampler;
  @Mock TraceContext.Injector<RpcClientRequest> injector;
  @Spy RpcClientParser parser = spy(new RpcClientParser());
  @Mock(answer = CALLS_REAL_METHODS) RpcClientRequest request;
  @Mock(answer = CALLS_REAL_METHODS) RpcClientResponse response;

  @Before public void init() {
    rpcTracing = RpcTracing.newBuilder(Tracing.newBuilder().spanReporter(spans::add).build())
      .clientSampler(sampler).clientParser(parser).build();
    handler = RpcClientHandler.create(rpcTracing, injector);
  }

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void handleStart_parsesRpcMethod() {
    brave.Span span = mock(brave.Span.class);
    brave.SpanCustomizer customizer = mock(brave.SpanCustomizer.class);
    when(span.kind(brave.Span.Kind.CLIENT)).thenReturn(span);
    when(request.method()).thenReturn("users.UserService/GetUserToken");
    when(span.customizer()).thenReturn(customizer);

    handler.handleSend(request, span);

    verify(customizer).name("users.UserService/GetUserToken");
    verify(customizer).tag("rpc.method", "users.UserService/GetUserToken");
    verifyNoMoreInteractions(customizer);
  }

  @Test public void handleSend_defaultsToMakeNewTrace() {
    when(sampler.trySample(request)).thenReturn(null);

    assertThat(handler.handleSend(request))
      .extracting(brave.Span::isNoop, s -> s.context().parentId())
      .containsExactly(false, null);
  }

  @Test public void handleSend_makesAChild() {
    ScopedSpan parent = rpcTracing.tracing().tracer().startScopedSpan("test");
    try {
      assertThat(handler.handleSend(request))
        .extracting(brave.Span::isNoop, s -> s.context().parentId())
        .containsExactly(false, parent.context().spanId());
    } finally {
      parent.finish();
    }
  }

  @Test public void handleSend_makesRequestBasedSamplingDecision() {
    // request sampler says false eventhough trace ID sampler would have said true
    when(sampler.trySample(request)).thenReturn(false);

    assertThat(handler.handleSend(request).isNoop())
      .isTrue();
  }

  @Test public void handleSend_injectsTheTraceContext() {
    TraceContext context = handler.handleSend(request).context();

    verify(injector).inject(context, request);
  }

  @Test public void externalTimestamps() {
    when(sampler.trySample(request)).thenReturn(null);
    when(request.startTimestamp()).thenReturn(123000L);
    when(response.finishTimestamp()).thenReturn(124000L);

    brave.Span span = handler.handleSend(request);
    handler.handleReceive(response, null, span);

    assertThat(spans.get(0).durationAsLong()).isEqualTo(1000L);
  }

  @Test public void handleSend_traceIdSamplerSpecialCased() {
    Sampler sampler = mock(Sampler.class);

    handler =
      RpcClientHandler.create(RpcTracing.newBuilder(Tracing.newBuilder().sampler(sampler).build())
        .clientSampler(SamplerFunctions.deferDecision()).build(), injector);

    assertThat(handler.handleSend(request).isNoop()).isTrue();

    verify(sampler).isSampled(anyLong());
  }

  @Test public void handleSend_neverSamplerSpecialCased() {
    Sampler sampler = mock(Sampler.class);

    handler =
      RpcClientHandler.create(RpcTracing.newBuilder(Tracing.newBuilder().sampler(sampler).build())
        .clientSampler(SamplerFunctions.neverSample()).build(), injector);

    assertThat(handler.handleSend(request).isNoop()).isTrue();

    verifyNoMoreInteractions(sampler);
  }

  @Test public void handleSend() {
    when(sampler.trySample(request)).thenReturn(null);

    brave.Span span = handler.handleSend(request);
    handler.handleReceive(response, null, span);

    verify(parser).request(eq(request), any(SpanCustomizer.class));
  }

  @Test public void handleReceive() {
    when(sampler.trySample(request)).thenReturn(null);

    brave.Span span = handler.handleSend(request);
    handler.handleReceive(response, null, span);

    verify(parser).request(eq(request), any(SpanCustomizer.class));
  }
}
