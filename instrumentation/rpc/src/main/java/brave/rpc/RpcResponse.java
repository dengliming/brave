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

import brave.Clock;
import brave.Span;
import brave.propagation.TraceContext;

/**
 * Abstract response type used for parsing and sampling of rpc clients and servers.
 *
 * @see RpcClientResponse
 * @see RpcServerResponse
 * @since 5.10
 */
// TODO: once merged, refactor out brave.Response similar to brave.Request and backport HTTP to it
abstract class RpcResponse {
  /**
   * Returns the underlying rpc response object. Ex. {@code javax.servlet.rpc.RpcServletResponse}
   *
   * <p>Note: Some implementations are composed of multiple types, such as a response and an object
   * representing the matched route. Moreover, an implementation may change the type returned due to
   * refactoring. Unless you control the implementation, cast carefully (ex using {@code instance
   * of}) instead of presuming a specific type will always be returned.
   *
   * @since 5.10
   */
  public abstract Object unwrap();

  /**
   * The timestamp in epoch microseconds of the end of this request or zero to take this implicitly
   * from the current clock. Defaults to zero.
   *
   * <p>This is helpful in two scenarios: late parsing and avoiding redundant timestamp overhead.
   * For example, you can asynchronously handle span completion without losing precision of the
   * actual end.
   *
   * <p>Note: Overriding has the same problems as using {@link Span#finish(long)}. For
   * example, it can result in negative duration if the clock used is allowed to correct backwards.
   * It can also result in misalignments in the trace, unless {@link brave.Tracing.Builder#clock(Clock)}
   * uses the same implementation.
   *
   * @see RpcRequest#startTimestamp()
   * @see brave.Span#finish(long)
   * @see brave.Tracing#clock(TraceContext)
   * @since 5.10
   */
  public long finishTimestamp() {
    return 0L;
  }

  @Override public String toString() {
    Object unwrapped = unwrap();
    // unwrap() returning null is a bug. It could also return this. don't NPE or stack overflow!
    if (unwrapped == null || unwrapped == this) return getClass().getSimpleName();
    return getClass().getSimpleName() + "{" + unwrapped + "}";
  }
}
