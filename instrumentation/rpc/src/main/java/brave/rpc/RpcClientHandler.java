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
import brave.Tracer;
import brave.internal.Nullable;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Injector;
import brave.sampler.SamplerFunction;

/**
 * This standardizes a way to instrument rpc clients, particularly in a way that encourages use of
 * portable customizations via {@link RpcClientParser}.
 *
 * <p>This is an example of synchronous instrumentation:
 * <pre>{@code
 * Span span = handler.handleSend(request);
 * Throwable error = null;
 * try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
 *   // any downstream code can see Tracer.currentSpan() or use Tracer.currentSpanCustomizer()
 *   response = invoke(request);
 * } catch (RuntimeException | Error e) {
 *   error = e;
 *   throw e;
 * } finally {
 *   handler.handleReceive(response, error, span);
 * }
 * }</pre>
 *
 * @since 5.10
 */
public final class RpcClientHandler extends RpcHandler<RpcClientRequest, RpcClientResponse> {
  /** @since 5.10 */
  public static RpcClientHandler create(RpcTracing rpcTracing,
    Injector<RpcClientRequest> injector) {
    if (rpcTracing == null) throw new NullPointerException("rpcTracing == null");
    if (injector == null) throw new NullPointerException("injector == null");
    return new RpcClientHandler(rpcTracing, injector);
  }

  final Tracer tracer;
  final Injector<RpcClientRequest> injector;
  final SamplerFunction<RpcRequest> sampler;

  RpcClientHandler(RpcTracing rpcTracing, Injector<RpcClientRequest> injector) {
    super(
      Span.Kind.CLIENT,
      rpcTracing.tracing().currentTraceContext(),
      rpcTracing.clientParser()
    );
    this.tracer = rpcTracing.tracing().tracer();
    this.injector = injector;
    this.sampler = rpcTracing.clientSampler();
  }

  /**
   * Starts the client span after assigning it a name and tags. This {@link
   * Injector#inject(TraceContext, Object) injects} the trace context onto the request before
   * returning.
   *
   * <p>Call this before sending the request on the wire.
   *
   * @since 5.10
   */
  public Span handleSend(RpcClientRequest request) {
    if (request == null) throw new NullPointerException("request == null");
    return handleSend(request, tracer.nextSpan(sampler, request));
  }

  /**
   * Like {@link #handleSend(RpcClientRequest)}, except explicitly controls the span representing
   * the request.
   *
   * @see Tracer#nextSpan(SamplerFunction, Object)
   * @since 5.10
   */
  public Span handleSend(RpcClientRequest request, Span span) {
    if (request == null) throw new NullPointerException("request == null");
    if (span == null) throw new NullPointerException("span == null");
    injector.inject(span.context(), request);
    return handleStart(request, span);
  }

  @Override
  void parseResponse(@Nullable RpcClientResponse response, @Nullable Throwable error, Span span) {
    if (response != null) response.parseRemoteIpAndPort(span);
    super.parseResponse(response, error, span);
  }

  /**
   * Finishes the client span after assigning it tags according to the response or error.
   *
   * <p>This is typically called once the response headers are received, and after the span is
   * {@link brave.Tracer.SpanInScope#close() no longer in scope}.
   *
   * @since 5.10
   */
  public void handleReceive(RpcClientResponse response, @Nullable Throwable error, Span span) {
    handleFinish(response, error, span);
  }
}
