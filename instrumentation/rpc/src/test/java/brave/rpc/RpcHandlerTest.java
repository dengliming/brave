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
import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RpcHandlerTest {
  CurrentTraceContext currentTraceContext = ThreadLocalCurrentTraceContext.create();
  TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(10L).build();
  TraceContext context2 = TraceContext.newBuilder().traceId(1L).spanId(11L).build();
  @Mock brave.Span span;
  @Mock SpanCustomizer spanCustomizer;
  @Mock RpcRequest request;
  @Mock RpcResponse response;
  RpcHandler<RpcRequest, RpcResponse> handler;

  @Before public void init() {
    handler = new RpcHandler<>(Span.Kind.SERVER, currentTraceContext, new RpcParser<>());
    when(span.context()).thenReturn(context);
    when(span.customizer()).thenReturn(spanCustomizer);
  }

  @Test public void handleStart_nothingOnNoop_success() {
    when(span.isNoop()).thenReturn(true);

    handler.handleStart(request, span);

    verify(span, never()).start();
  }

  @Test public void parsesInNoScope() {
    handler = new RpcHandler<>(Span.Kind.SERVER, currentTraceContext,
      new RpcParser<RpcRequest, RpcResponse>() {
        @Override public void request(RpcRequest request, SpanCustomizer customizer) {
          assertThat(currentTraceContext.get()).isNotNull();
        }

        @Override
        public void response(RpcResponse response, Throwable error, SpanCustomizer customizer) {
          assertThat(currentTraceContext.get()).isNotNull();
        }
      });
    handler.handleStart(request, span);
    handler.handleFinish(response, null, span);
  }

  @Test public void handleFinish_nothingOnNoop_success() {
    when(span.isNoop()).thenReturn(true);

    handler.handleFinish(response, null, span);

    verify(span, never()).finish();
  }

  @Test public void handleFinish_nothingOnNoop_error() {
    when(span.isNoop()).thenReturn(true);

    handler.handleFinish(null, new RuntimeException("drat"), span);

    verify(span, never()).finish();
  }

  @Test public void handleFinish_finishesWithSpanInScope() {
    doAnswer(invocation -> {
      assertThat(currentTraceContext.get()).isEqualTo(span.context());
      return null;
    }).when(span).finish();

    handler.handleFinish(response, null, span);
  }

  @Test public void handleFinish_finishesWithSpanInScope_resettingIfNecessary() {
    try (CurrentTraceContext.Scope ws = currentTraceContext.newScope(context2)) {
      handleFinish_finishesWithSpanInScope();
    }
  }

  @Test public void handleFinish_finishedEvenIfAdapterThrows() {
    when(response.errorMessage()).thenThrow(new RuntimeException());

    assertThatThrownBy(() -> handler.handleFinish(response, null, span))
      .isInstanceOf(RuntimeException.class);

    verify(span).finish();
  }
}
