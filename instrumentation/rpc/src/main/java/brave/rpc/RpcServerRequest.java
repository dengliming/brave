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

/**
 * Marks an interface for use in {@link RpcServerHandler#handleReceive(RpcServerRequest)}. This
 * gives a standard type to consider when parsing an incoming context.
 *
 * @see RpcServerResponse
 * @since 5.8
 */
public abstract class RpcServerRequest extends RpcRequest {
  @Override public final Span.Kind spanKind() {
    return Span.Kind.SERVER;
  }

  /**
   * Override and return true when it is possible to parse the {@link Span#remoteIpAndPort(String,
   * int) remote IP and port} from the {@link #unwrap() delegate}. Defaults to false.
   *
   * @since 5.10
   */
  // This is on the request object because by server requests happen after the network connection
  // of the client.
  public boolean parseRemoteIpAndPort(Span span) {
    return false;
  }
}
