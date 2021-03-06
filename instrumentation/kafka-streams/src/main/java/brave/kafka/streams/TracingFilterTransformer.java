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
package brave.kafka.streams;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;

class TracingFilterTransformer<K, V> extends TracingFilter<K, V, KeyValue<K, V>>
  implements Transformer<K, V, KeyValue<K, V>> {

  TracingFilterTransformer(KafkaStreamsTracing tracing, String spanName,
    Predicate<K, V> delegatePredicate, boolean filterNot) {
    super(tracing, spanName, delegatePredicate, filterNot);
  }

  @Override
  public void init(ProcessorContext context) {
    super.init(context);
  }

  @Override
  public KeyValue<K, V> transform(K key, V value) {
    return super.transform(key, value);
  }

  @Override public void close() {
  }

  @Override KeyValue<K, V> result(K key, V value) {
    return KeyValue.pair(key, value);
  }
}
