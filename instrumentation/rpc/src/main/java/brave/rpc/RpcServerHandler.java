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
import brave.internal.Nullable;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.SamplerFunction;

/**
 * This standardizes a way to instrument rpc servers, particularly in a way that encourages use of
 * portable customizations via {@link RpcServerParser}.
 *
 * <p>This is an example of synchronous instrumentation:
 * <pre>{@code
 * Span span = handler.handleReceive(request);
 * Throwable error = null;
 * try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
 *   // any downstream code can see Tracer.currentSpan() or use Tracer.currentSpanCustomizer()
 *   response = invoke(request);
 * } catch (RuntimeException | Error e) {
 *   error = e;
 *   throw e;
 * } finally {
 *   handler.handleSend(response, error, span);
 * }
 * }</pre>
 *
 * @since 5.10
 */
public final class RpcServerHandler extends RpcHandler<RpcServerRequest, RpcServerResponse> {
  /** @since 5.10 */
  public static RpcServerHandler create(RpcTracing rpcTracing,
    Extractor<RpcServerRequest> extractor) {
    if (rpcTracing == null) throw new NullPointerException("rpcTracing == null");
    if (extractor == null) throw new NullPointerException("extractor == null");
    return new RpcServerHandler(rpcTracing, extractor);
  }

  final Tracer tracer;
  final Extractor<RpcServerRequest> extractor;
  final SamplerFunction<RpcRequest> sampler;

  RpcServerHandler(RpcTracing rpcTracing, Extractor<RpcServerRequest> extractor) {
    super(
      Span.Kind.SERVER,
      rpcTracing.tracing().currentTraceContext(),
      rpcTracing.serverParser()
    );
    this.tracer = rpcTracing.tracing().tracer();
    this.extractor = extractor;
    this.sampler = rpcTracing.serverSampler();
  }

  /**
   * Conditionally joins a span, or starts a new trace, depending on if a trace context was
   * extracted from the request. Tags are added before the span is started.
   *
   * <p>This is typically called before the request is processed by the actual library.
   *
   * @since 5.10
   */
  public Span handleReceive(RpcServerRequest request) {
    Span span = nextSpan(extractor.extract(request), request);
    return handleStart(request, span);
  }

  /** Creates a potentially noop span representing this request */
  Span nextSpan(TraceContextOrSamplingFlags extracted, RpcServerRequest request) {
    Boolean sampled = extracted.sampled();
    // only recreate the context if the rpc sampler made a decision
    if (sampled == null && (sampled = sampler.trySample(request)) != null) {
      extracted = extracted.sampled(sampled.booleanValue());
    }
    return extracted.context() != null
      ? tracer.joinSpan(extracted.context())
      : tracer.nextSpan(extracted);
  }

  /**
   * Finishes the server span after assigning it tags according to the response or error.
   *
   * <p>This is typically called once the response headers are sent, and after the span is {@link
   * brave.Tracer.SpanInScope#close() no longer in scope}.
   *
   * @see RpcServerParser#response(RpcServerResponse, Throwable, SpanCustomizer)
   * @since 5.10
   */
  public void handleSend(@Nullable RpcServerResponse response, @Nullable Throwable error,
    Span span) {
    handleFinish(response, error, span);
  }
}
