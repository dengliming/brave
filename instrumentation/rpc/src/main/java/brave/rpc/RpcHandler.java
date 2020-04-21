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
import brave.internal.Nullable;

abstract class RpcHandler<Req extends RpcRequest, Resp extends RpcResponse> {
  final RpcRequestParser requestParser;
  final RpcResponseParser responseParser;

  RpcHandler(RpcRequestParser requestParser, RpcResponseParser responseParser) {
    this.requestParser = requestParser;
    this.responseParser = responseParser;
  }

  Span handleStart(Req request, Span span) {
    if (span.isNoop()) return span;

    try {
      parseRequest(request, span);
    } finally {
      // all of the above parsing happened before a timestamp on the span
      long timestamp = request.startTimestamp();
      if (timestamp == 0L) {
        span.start();
      } else {
        span.start(timestamp);
      }
    }
    return span;
  }

  void parseRequest(Req request, Span span) {
    span.kind(request.spanKind());
    request.parseRemoteIpAndPort(span);
    requestParser.parse(request, span.context(), span.customizer());
  }

  void parseResponse(Resp response, Span span) {
    responseParser.parse(response, span.context(), span.customizer());
  }

  void handleFinish(@Nullable Resp response, @Nullable Throwable error, Span span) {
    if (response == null && error == null) {
      throw new IllegalArgumentException(
        "Either the response or error parameters may be null, but not both");
    }

    if (span.isNoop()) return;

    if (error != null) {
      span.error(error); // Ensures MutableSpan.error() for FinishedSpanHandler

      if (response == null) { // There's nothing to parse: finish and return;
        span.finish();
        return;
      }
    }

    try {
      parseResponse(response, span);
    } finally {
      long finishTimestamp = response.finishTimestamp();
      if (finishTimestamp == 0L) {
        span.finish();
      } else {
        span.finish(finishTimestamp);
      }
    }
  }
}
