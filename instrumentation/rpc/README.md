# brave-instrumentation-rpc

This is a helper for RPC libraries such as gRPC and Dubbo. Specifically, this
includes samplers for clients and servers, configured with `RpcTracing`.

The `RpcTracing` class holds a reference to a tracing component,
instructions on what to put into rpc spans, and sampling policy.

## Span data policy
By default, the following are added to both rpc client and server spans:
* Span.name is the rpc method in lowercase: ex "zipkin.proto3.spanservice/report"
* Tags/binary annotations:
  * "rpc.method", eg "zipkin.proto3.SpanService/Report"
  * "error", when there is an exception
* Remote IP and port information

Naming and tags are configurable in a library-agnostic way. For example,
the same `RpcTracing` component configures gRPC or Apache Dubbo identically.

For example, to change the tagging policy for clients, you can do
something like this:

```java
rpcTracing = rpcTracing.toBuilder()
    .clientParser(new RpcClientParser() {
      @Override public void request(RpcClientRequest request, SpanCustomizer customizer) {
        customizer.name(spanName(request)); // default span name
        // your additional tags
      }
    })
    .build();

grpc = GrpcTracing.create(rpcTracing);
dubbo = DubboTracing.create(rpcTracing);
```

If you just want to control span naming policy based on the request,
override `spanName` in your client or server parser.

Ex:
```java
overrideSpanName = new RpcClientParser() {
  @Override public String spanName(RpcClientRequest request) {
    Object unwrapped = request.unwrap();
    // If using JAX-RS, maybe we want to use the resource method
    if (unwrapped instanceof ResourceInfo) {
      return ((ResourceInfo) unwrapped).getResourceMethod().getName().toLowerCase();
    }
    // If not using framework-specific knowledge, we can use rpc
    // attributes or go with the default.
    return super.spanName(request);
  }
};
```

Note that span name can be overwritten any time, for example, when
parsing the response, which is the case when route-based names are used.

## Sampling Policy
The default sampling policy is to use the default (trace ID) sampler for
client and server requests.

For example, if there's a incoming request that has no trace IDs in its
headers, the sampler indicated by `RpcTracing.Builder.serverSampler`
decides whether or not to start a new trace. Once a trace is in progress, it is
used for any outgoing messages (client requests).

On the other hand, you may have outgoing requests didn't originate from a
server. For example, bootstrapping your application might call a discovery
service. In this case, the policy defined by `RpcTracing.Builder.clientSampler`
decides if a new trace will be started or not.

You can change the sampling policy by specifying it in the `RpcTracing`
component. The default implementation `RpcRuleSampler` allows you to
declare rules based on declare rules based on RPC properties and apply an
appropriate sampling rate.

Ex. Here's a sampler that traces 100 "Report" requests per second. This
doesn't start new traces for requests to the scribe service. Other
requests will use a global rate provided by the tracing component.

```java
import static brave.rpc.RpcRequestMatchers.*;

rpcTracingBuilder.serverSampler(RpcRuleSampler.newBuilder()
  .putRule(serviceEquals("scribe"), Sampler.NEVER_SAMPLE)
  .putRule(methodEquals("Report"), RateLimitingSampler.create(100))
  .build());
```

# Developing new instrumentation

Check for [instrumentation written here](../) and [Zipkin's list](https://zipkin.io/pages/tracers_instrumentation.html)
before rolling your own Rpc instrumentation! Besides documentation here,
you should look at the [core library documentation](../../brave/README.md) as it
covers topics including propagation. You may find our [feature tests](src/test/java/brave/rpc/features) helpful, too.

## Rpc Client

The first step in developing rpc client instrumentation is implementing
a `RpcClientRequest` and `RpcClientResponse` for your native library.
This ensures users can portably control tags using `RpcClientParser`.

Next, you'll need to indicate how to insert trace IDs into the outgoing
request. Often, this is as simple as `MyClientRequest::setHeader`, a
library specific method on a subclass of `RpcClientRequest`.

Ex.
```java
final class MyClientRequest extends RpcClientRequest {

  final ActualRequest delegate;
--
  void setHeader(String name, String value) {
    return delegate.setHeader(name, value);
  }
}
```

With these request and response types implemented, you have the most important
parts needed to trace your client library. You'll likely initialize the
following in a constructor like so:

```java
MyTracingFilter(RpcTracing rpcTracing) {
  tracer = rpcTracing.tracing().tracer();
  injector = rpcTracing.tracing().propagation().injector(MyClientRequest::setHeader);
  handler = RpcClientHandler.create(rpcTracing, injector);
}
```

### Synchronous Interceptors

Synchronous interception is the most straight forward instrumentation.
You generally need to...
1. Start the span and add trace headers to the request
2. Put the span in scope so things like log integration works
3. Invoke the request
4. Catch any errors
5. Complete the span

```java
Span span = handler.handleSend(request); // 1.
Throwable error = null;
SpanInScope scope = tracer.withSpanInScope(span); // 2.
try {
  response = invoke(request); // 3.
} catch (RuntimeException | Error e) {
  error = e; // 4.
  throw e;
} finally {
  handler.handleReceive(response, error, span); // 5.
  scope.close();
}
```

## Rpc Server

The first step in developing rpc server instrumentation is implementing
`brave.RpcServerRequest` and `brave.RpcServerResponse` for your native
library. This ensures your instrumentation can extract headers, sample and
control tags.

To implement header extraction, you add a library specific method like
`MyServerRequest::getHeader`, to your subclass of `RpcServerRequest`.

Ex.
```java
final class MyServerRequest extends RpcServerRequest {

  final ActualRequest delegate;
--
  String getHeader(String name) {
    return delegate.getHeader(name);
  }
}
```

With these request and response types implemented, you have the most important
parts needed to trace your server library. You'll likely initialize the
following in a constructor like so:

```java
MyTracingInterceptor(RpcTracing rpcTracing) {
  tracer = rpcTracing.tracing().tracer();
  extractor = rpcTracing.tracing().propagation().extractor(MyServerRequest::getHeader);
  handler = RpcServerHandler.create(rpcTracing, extractor);
}
```

### Synchronous Interceptors

Synchronous interception is the most straight forward instrumentation.
You generally need to...
1. Extract any trace IDs from headers and start the span
2. Put the span in scope so things like log integration works
3. Invoke the request
4. Catch any errors
5. Complete the span

```java
Span span = handler.handleReceive(request); // 1.
Throwable error = null;
SpanInScope scope = tracer.withSpanInScope(span); // 2.
try {
  response = invoke(request); // 3.
} catch (RuntimeException | Error e) {
  error = e; // 4.
  throw e;
} finally {
  handler.handleSend(response, error, span); // 5.
  scope.close();
}
```
