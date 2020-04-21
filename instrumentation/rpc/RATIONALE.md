# brave-instrumentation-rpc rationale

## Why doesn't HTTP layer over RPC?
RPC is its own abstraction, that HTTP has RPC-like characteristics is
incidental to our model. Moreover, HTTP is a specified (by httpbis) abstraction
where RPC is largely conventional. To the degree frameworks such as Apache
Dubbo, Thrift and Avro are similar is not due to a specification, in other
words.

The OpenTelemetry group tried to layer HTTP over the gRPC model, and it didn't
work out very well, as you can see in their [status mapping](https://github.com/open-telemetry/opentelemetry-specification/blob/bfb060b23113ba9af492f8c63dd89ecfc500810b/specification/trace/semantic_conventions/http.md#status).

For example, one of the most common HTTP status, 400, was not even mappable to
gRPC. OpenTelemetry mapped HTTP redirect and not available codes to
`Deadline exceeded`. It is a good example of confusion which we can learn from,
as confusing users is a non-goal of telemetry.

Instead, we keep the more defined HTTP in its own abstraction, which allows it
to be congruent with existing practices such as alerting on HTTP status.

## Why don't we model Messaging and Storage as RPC?
We believe that even if underlying drivers like Redis have RPC like
characteristics, this is not the best abstraction to optimize for when modeling
service abstraction communication.

In other words, a tool like Redis is more effectively understood as a storage
service, vs modeling it as an RPC framework, like Apache Thrift or Avro.

Conversely, we believe that RPC services, especially where users define the IDL
have business relevance. The message/method/function names are more likely to
reflect business processes. This relevance helps users navigate impact. If we
also put MySQL driver communication in the same abstraction, it would at best
create more data to filter out and at worst, drown out the ability for users to
find their business functions.

## `RpcRequest` overview

The `RpcRequest` type is a model adapter, used initially for sampling, but in
the future can be used to parse data into the span model, for example to
portably create a span name without special knowledge of the RPC library. This
is repeating the work we did in HTTP, which allows allows library agnostic
data and sampling policy.

## Why start with `method()` and `service()` properties?

In order to lower initial effort, we decided to scope the initial
implementation of RPC to just things needed by samplers, injection and
extraction. Among these were the desire to create a sampling rule to match
requests to an "auth service" or a "play" method.

### Examples please?
`rpc.method` - The unqualified, case-sensitive method name. Prefer the name
defined in IDL to any mapped Java method name.

Examples
* gRPC - full method "grpc.health.v1.Health/Check" returns "Check"
* Apache Dubbo - "demo.service.DemoService#sayHello()" command returns "demo.service.DemoService"
* Apache Thrift - full method "scribe.Log" returns "Log"

`rpc.service` - The fully-qualified, case-sensitive service path. Prefer the
name defined in IDL to any mapped Java package name.

Examples
* gRPC - full method "grpc.health.v1.Health/Check" returns "grpc.health.v1.Health"
* Apache Dubbo - "demo.service.DemoService#sayHello()" command returns "demo.service.DemoService"
* Apache Thrift - full method "scribe.Log" returns "scribe"

### Why not parameters?
It was easier to start with these because the `method` and `service` properties
of RPC are quite explored and also easy compared to other attributes such as
request parameters. For example, unlike HTTP, where parameters are strings, RPC
parameters can be encoded in binary types, such as protobuf. Not only does this
present abstraction concerns, such as where in the code these types are
unmarshalled, but also serialization issues, when we consider most policy will
be defined declaratively.

### Why use `rpc.method` and `rpc.service`?
To reduce friction, we wanted to use names already in practice. For example,
`method` and `service` are fairly common in IDL such as Apache Thrift and gRPC.
These names are also used in non-IDL based RPC, such as Apache Dubbo. Moreover,
there are established practice of `method` and `service` in tools such as
[Prometheus gRPC monitoring](https://github.com/grpc-ecosystem/go-grpc-prometheus#labels).

While there could be confusion around `rpc.method` vs Java method, that exists
anyway and is easy to explain: When IDL exists, prefer its service and method
name to any matched Java names.

`rpc.service` unfortunately conflicts with our term `Endpoint.serviceName`, but
only if you ignore the `Endpoint` prefix. Given this, we feel it fits in with
existing practice and doesn't add enough confusion to create new terms.

### Wait.. not everything has a `service` namespace?

Not all RPC implementation has a service/namespace. For example, Redis commands
have a flat namespace. In our model, not applicable properties are represented
as null. The important part is decoupling `service` from `method` allows one
property, `method`, to be extractable more frequently vs if we forced
concatenation through a `full method name` as exists in gRPC.

### But what if I want a `full method name` as exists in gRPC?

We can have the talk about what an idiomatic term could be for a fully
qualified method name. Only gRPC uses the term `full method name`, so it isn't
as clean to lift that term up, vs other names such as `service` that exist in
multiple tools such as Apache Thrift.

Meanwhile, in gRPC, the full method name can be composed using `Matchers.and()`
```java
rpcTracing = rpcTracingBuilder.serverSampler(RpcRuleSampler.newBuilder()
  .putRule(and(serviceEquals("users.UserService"), methodEquals("GetUserToken")), RateLimitingSampler.create(100))
  .build()).build();

grpcTracing = GrpcTracing.create(rpcTracing);
```

### But my span name is already what I want!
It is subtle, but important to note that `RpcRequest` is an input type used in
parameterized sampling. This happens before a span name is chosen. Moreover, a
future change will allow parsing into a span name from the same type. In other
words, `RpcRequest` is an intermediate model that must evaluate properties
before a span exists.

## Why "rpc.error_code"?

## Why not "rpc.error_message"?
A long form error message is unlikely to be portable due to lack of frameworks defining more than
even an error bit in their RPC message types. Moreover, long form messages usually materialize as
exception messages, which are handled directly by `MutableSpan.error()`.

For example, users today trace SOAP services which underneath have "faultcode" and "faultstring".
The "faultcode" becomes the "rpc.error_code", and the "faultstring" is very likely to be a raised
exception message. If a user desires to also tag that in a SOAP specific way, they can use an
`RpcResponseParser`.

### Why not re-use OpenTelemetry "status"
OpenTelemetry chose to re-use gRPC status codes as a generic "status" type.
https://github.com/open-telemetry/opentelemetry-java/blob/c24e4a963669cfd044d7478a801e8f170faba4fe/api/src/main/java/io/opentelemetry/trace/Status.java#L38

In practice this is not portable for a number of reasons.

While errors like as "method doesn't exist on the other side" are possible generically. There's no
current art to suggest errors can be identified portably across RPC frameworks. In fact, even
success is not identifiable portably, notably due to "one-way" RPC, used widely including in Apache
Thrift, Avro and Dubbo frameworks. Even errors are usually not defined more than boolean `true`.

One-way RPC is typically implemented similar to messaging. Whether the response was even accepted or
not is unknowable. Hence, you cannot set "OK" status and expect it to be correct, much less map to
response status which you'll never receive.
