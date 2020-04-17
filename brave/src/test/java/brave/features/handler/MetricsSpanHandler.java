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
package brave.features.handler;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.util.StringUtils;

/** Example  span handler which emits metrics for each span with a given name */
public class MetricsSpanHandler extends SpanHandler {
  static final Tag EXCEPTION_NONE = Tag.of("exception", "None");

  final MeterRegistry registry;
  final String metricName;
  final Map<String, Tag> nameToTag;

  MetricsSpanHandler(MeterRegistry registry, String metricName, String... names) {
    Map<String, Tag> nameToTag = new LinkedHashMap<>();
    for (String name : names) {
      nameToTag.put(name, Tag.of("name", name));
    }
    this.registry = registry;
    this.metricName = metricName;
    this.nameToTag = nameToTag;
  }

  /**
   * We need to read the span name to determine if it will be recorded as a metric or not. This
   * isn't known for sure until the end of the span.
   */
  @Override public boolean alwaysSampleLocal() {
    return true;
  }

  @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    if (cause != Cause.FINISH) return true;

    Tag nameTag = nameToTag.get(span.name());
    if (nameTag == null) return true; // no tag

    // Example of adding a correlated tag. Note that in spans, we don't add a negative one (None)
    Tag errorTag = exception(span.error());
    if (errorTag != EXCEPTION_NONE) {
      span.tag("exception", errorTag.getValue());
    }

    // Look or create up a timer that records duration against
    registry.timer(metricName, Arrays.asList(nameTag, errorTag))
      // Timestamps are derived from a function of clock time and nanos offset
      .record(span.finishTimestamp() - span.startTimestamp(), TimeUnit.MICROSECONDS);
    return true;
  }

  static Tag exception(Throwable exception) {
    if (exception == null) return EXCEPTION_NONE;
    String simpleName = exception.getClass().getSimpleName();
    return Tag.of("exception",
      // check hasText as the class could be anonymous
      StringUtils.hasText(simpleName) ? simpleName : exception.getClass().getName());
  }
}
