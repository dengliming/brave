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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RpcParserTest {
  @Mock SpanCustomizer customizer;
  @Mock RpcServerRequest request;
  @Mock RpcServerResponse response;
  RpcParser<RpcServerRequest, RpcServerResponse> parser = new RpcParser<>();

  @Test public void spanName_isRpcMethod() {
    when(request.method()).thenReturn("Zipkin.proto3.SpanService/Report");

    assertThat(parser.spanName(request))
      .isEqualTo(
        "Zipkin.proto3.SpanService/Report"); // note: in practice this will become lowercase
  }

  @Test public void request_tagsRpcMethod() {
    when(request.method()).thenReturn("Zipkin.proto3.SpanService/Report");
    when(request.method()).thenReturn("Zipkin.proto3.SpanService/Report");

    parser.request(request, customizer);

    verify(request, times(2)).method();
    verify(customizer).name("Zipkin.proto3.SpanService/Report");
    verify(customizer).tag("rpc.method", "Zipkin.proto3.SpanService/Report");
    verifyNoMoreInteractions(request, customizer);
  }

  @Test public void response_tagsNothing() {
    parser.response(response, null, customizer);

    verify(response).errorMessage();
    verifyNoMoreInteractions(response, customizer);
  }

  /** Nothing is tagged because error parsing of the runtime exception happens prior */
  @Test public void response_tagsErrorMessage() {
    when(response.errorMessage()).thenReturn("UNAVAILABLE");
    parser.response(response, new RuntimeException("drat"), customizer);

    verify(response).errorMessage();
    verify(customizer).tag("rpc.error_message", "UNAVAILABLE");
    verify(customizer).tag("error", "UNAVAILABLE");
    verifyNoMoreInteractions(response, customizer);
  }

  /** Nothing is tagged because error parsing of the runtime exception happens prior */
  @Test public void response_missingErrorMessage_tagsNothing() {
    parser.response(response, new RuntimeException("drat"), customizer);

    verify(response).errorMessage();
    verifyNoMoreInteractions(response, customizer);
  }
}
