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

import brave.SpanCustomizer;
import brave.Tracing;
import brave.internal.Nullable;

class RpcParser<Req extends RpcRequest, Resp extends RpcResponse> {
  /**
   * Override to change what data from the rpc request are parsed into the span representing it.
   *
   * <p>If you only want to change the span name, you can override {@link #spanName(RpcRequest)}
   * instead.
   *
   * @see #spanName(RpcRequest)
   * @since 5.10
   */
  public void request(Req request, SpanCustomizer customizer) {
    String name = spanName(request);
    if (name != null) customizer.name(name);
    String method = request.method();
    if (method != null) customizer.tag("rpc.method", method); // doesn't change case format
  }

  /**
   * Returns the span name of the request. Defaults to the rpc method.
   *
   * @since 5.10
   */
  protected String spanName(Req request) {
    return request.method();
  }

  /**
   * Override to change what data from the rpc response or error are parsed into the span modeling
   * it. By default, if there is an error, {@link Tracing#errorParser()} is used prior to this
   * method, potentially overridden with {@link #error(String, Throwable, SpanCustomizer)}.
   *
   * <p>Note: Either the response or error parameters may be null, but not both.
   *
   * @since 5.10
   */
  public void response(Resp response, @Nullable Throwable error, SpanCustomizer customizer) {
    String errorMessage = response.errorMessage();
    if (errorMessage != null) customizer.tag("rpc.error_message", errorMessage);
    if (errorMessage != null || error != null) error(errorMessage, error, customizer);
  }

  /**
   * Override to change what data from the RPC error are parsed into the span modeling it. By
   * default, this overrides any error parsed from the exception with the {@code errorMessage}.
   *
   * <p>Note: Either the errorMessage or error parameters may be null, but not both
   *
   * <p>Conventionally associated with the tag key "error"
   */
  protected void error(@Nullable String errorMessage, @Nullable Throwable error,
    SpanCustomizer customizer) {
    if (errorMessage != null) customizer.tag("error", errorMessage);
  }
}
