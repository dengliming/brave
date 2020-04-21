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

import brave.SpanCustomizer;
import brave.internal.Nullable;
import brave.propagation.TraceContext;

/**
 * Use this to control the request data recorded for an {@link TraceContext#sampledLocal() sampled
 * RPC client or server span}.
 *
 * <p>Here's an example that only sets the span name, with no tags:
 * <pre>{@code
 * rpcTracing = rpcTracing.toBuilder()
 *   .clientRequestParser((req, context, span) -> {
 *     String method = req.method();
 *     if (method != null) span.name(method);
 *   }).build();
 * }</pre>
 *
 * @see RpcResponseParser
 */
// @FunctionalInterface: do not add methods as it will break api
public interface RpcRequestParser {
  RpcRequestParser DEFAULT = new Default();

  /**
   * Implement to choose what data from the rpc request are parsed into the span representing it.
   *
   * @see Default
   */
  void parse(RpcRequest request, TraceContext context, SpanCustomizer span);

  /**
   * The default data policy sets the span name to the RPC method and adds the "rpc.method" and
   * "rpc.path" tags.
   */
  // Eventhough the default span name is the method, we have no way of knowing that a user hasn't
  // overwritten the name to something else. If that occurs during response parsing, it is too late
  // to go back and get the rpc method. Adding rpc method by default ensures span naming doesn't
  // prevent basic RPC info from being visible. A cost of this is another tag, but it is small with
  // very limited cardinality. Moreover, users who care strictly about size can override this.
  class Default implements RpcRequestParser {
    /**
     * This sets the span name to the RPC method and adds the "rpc.method" and "rpc.service" tags.
     *
     * <p>If you only want to change the span name, subclass and override {@link
     * #spanName(RpcRequest, TraceContext)}.
     */
    @Override public void parse(RpcRequest req, TraceContext context, SpanCustomizer span) {
      String name = spanName(req, context);
      if (name != null) span.name(name);
      RpcTags.METHOD.tag(req, context, span);
      RpcTags.SERVICE.tag(req, context, span);
    }

    /**
     * Returns the span name of the request or null if the data needed is unavailable. Defaults to
     * the rpc method.
     */
    @Nullable protected String spanName(RpcRequest req, TraceContext context) {
      return req.method();
    }
  }
}
