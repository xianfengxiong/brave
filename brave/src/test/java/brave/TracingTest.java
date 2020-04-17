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
package brave;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.propagation.StrictCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.Test;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

import static org.assertj.core.api.Assertions.assertThat;

public class TracingTest {
  List<zipkin2.Span> spans = new ArrayList<>();
  List<MutableSpan> mutableSpans = new ArrayList<>();
  SpanHandler spanHandler = new SpanHandler() {
    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      mutableSpans.add(span);
      return true;
    }
  };

  /**
   * This behavior could be problematic as downstream services may report spans based on propagated
   * sampled status, and be missing a parent when their parent tracer is in noop.
   */
  @Test public void setNoop_dropsDataButDoesntAffectSampling() {
    try (Tracing tracing = Tracing.newBuilder()
      .currentTraceContext(StrictCurrentTraceContext.create())
      .spanReporter(spans::add).build()) {
      ScopedSpan parent = tracing.tracer().startScopedSpan("parent");

      tracing.setNoop(true);

      // a new child retains sampled from parent even in noop
      brave.Span child = tracing.tracer().newChild(parent.context());
      assertThat(child.context().sampled()).isTrue();
      assertThat(child.isNoop()).isTrue();
      child.finish();

      parent.finish();

      // a new trace is sampled from even when noop
      brave.Span root = tracing.tracer().newTrace();
      assertThat(root.context().sampled()).isTrue();
      assertThat(root.isNoop()).isTrue();
      root.finish();
    }

    assertThat(spans).isEmpty();
  }

  @Test public void localServiceNamePreservesCase() {
    String expectedLocalServiceName = "FavStar";
    Tracing.Builder builder = Tracing.newBuilder().localServiceName(expectedLocalServiceName);
    assertThat(builder).extracting("defaultSpan.localServiceName")
      .isEqualTo(expectedLocalServiceName);
  }

  @Test public void spanReporter_getsLocalEndpointInfo() {
    String expectedLocalServiceName = "favistar", expectedLocalIp = "1.2.3.4";
    int expectedLocalPort = 80;

    List<Span> zipkinSpans = new ArrayList<>();
    Reporter<Span> spanReporter = span -> {
      assertThat(span.localServiceName()).isEqualTo(expectedLocalServiceName);
      assertThat(span.localEndpoint().ipv4()).isEqualTo(expectedLocalIp);
      assertThat(span.localEndpoint().portAsInt()).isEqualTo(expectedLocalPort);
      zipkinSpans.add(span);
    };

    try (Tracing tracing = Tracing.newBuilder()
      .localServiceName(expectedLocalServiceName)
      .localIp(expectedLocalIp)
      .localPort(expectedLocalPort)
      .spanReporter(spanReporter)
      .build()) {
      tracing.tracer().newTrace().start().finish();
    }

    assertThat(zipkinSpans).isNotEmpty(); // ensures the assertions passed.
  }

  @Test public void spanHandler_loggingByDefault() {
    try (Tracing tracing = Tracing.newBuilder().build()) {
      assertThat(tracing.tracer().pendingSpans).extracting("spanHandler.delegate")
        .isInstanceOf(Tracing.LogSpanHandler.class);
    }
  }

  @Test public void spanHandler_ignoresNoop() {
    try (Tracing tracing = Tracing.newBuilder()
      .addSpanHandler(SpanHandler.NOOP)
      .build()) {
      assertThat(tracing.tracer().pendingSpans).extracting("spanHandler.delegate")
        .isInstanceOf(Tracing.LogSpanHandler.class);
    }
  }

  @Test public void spanHandler_multiple() {
    SpanHandler one = new SpanHandler() {
      @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        return true;
      }
    };
    SpanHandler two = new SpanHandler() {
      @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        return true;
      }
    };
    try (Tracing tracing = Tracing.newBuilder()
      .addSpanHandler(one)
      .addSpanHandler(two)
      .build()) {
      assertThat(tracing.tracer().pendingSpans).extracting("spanHandler.delegate.handlers")
        .asInstanceOf(InstanceOfAssertFactories.array(SpanHandler[].class))
        .containsExactly(one, two);
    }
  }

  /** This test shows that duplicates are not allowed. This prevents needless overhead. */
  @Test public void spanHandler_dupesIgnored() {
    SpanHandler spanHandler = new SpanHandler() {
      @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        return true;
      }
    };

    try (Tracing tracing = Tracing.newBuilder()
      .addSpanHandler(spanHandler)
      .addSpanHandler(spanHandler) // dupe
      .build()) {
      assertThat(tracing.tracer().pendingSpans).extracting("spanHandler.delegate")
        .isEqualTo(spanHandler);
    }
  }

  @Test public void alwaysReportSpans_reportsEvenWhenUnsampled() {
    TraceContext sampledLocal =
      TraceContext.newBuilder().traceId(1).spanId(1).sampledLocal(true).build();

    List<Span> spans = new ArrayList<>();
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .sampler(Sampler.NEVER_SAMPLE)
      .alwaysReportSpans()
      .build()) {
      tracing.tracer().toSpan(sampledLocal).start().finish();
    }

    assertThat(spans).isNotEmpty();
  }

  @Test public void spanHandler_dataChangesVisibleToZipkin() {
    String serviceNameOverride = "favistar";

    SpanHandler spanHandler = new SpanHandler() {
      @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        span.localServiceName(serviceNameOverride);
        return true;
      }
    };

    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .addSpanHandler(spanHandler)
      .build()) {
      tracing.tracer().newTrace().start().finish();
    }

    assertThat(spans.get(0).localServiceName()).isEqualTo(serviceNameOverride);
  }

  @Test public void spanHandler_recordsWhenSampled() {
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .addSpanHandler(spanHandler)
      .build()) {
      tracing.tracer().newTrace().start().name("aloha").finish();
    }

    assertThat(spans).hasSameSizeAs(mutableSpans).hasSize(1);
    assertThat(spans.get(0).name()).isEqualTo(mutableSpans.get(0).name());
    assertThat(spans.get(0).timestampAsLong()).isEqualTo(mutableSpans.get(0).startTimestamp());
    long mutableSpanDuration =
      Math.max(1, mutableSpans.get(0).finishTimestamp() - mutableSpans.get(0).startTimestamp());
    assertThat(spans.get(0).durationAsLong()).isEqualTo(mutableSpanDuration);
  }

  @Test public void spanHandler_doesntRecordWhenUnsampled() {
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .addSpanHandler(spanHandler)
      .sampler(Sampler.NEVER_SAMPLE)
      .build()) {
      tracing.tracer().newTrace().start().name("aloha").finish();
    }

    assertThat(spans).isEmpty();
    assertThat(mutableSpans).isEmpty();
  }

  @Test public void spanHandler_recordsWhenReporterIsNoopIfAlwaysSampleLocal() {
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(Reporter.NOOP)
      .addSpanHandler(spanHandler)
      .build()) {
      tracing.tracer().newTrace().start().name("aloha").finish();
    }

    assertThat(spans).isEmpty();
    assertThat(mutableSpans).hasSize(1);
  }

  @Test public void spanHandler_recordsWhenUnsampledIfAlwaysSampleLocal() {
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .addSpanHandler(new SpanHandler() {
        @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
          mutableSpans.add(span);
          return true;
        }

        @Override public boolean alwaysSampleLocal() {
          return true;
        }
      })
      .sampler(Sampler.NEVER_SAMPLE)
      .build()) {
      tracing.tracer().newTrace().start().name("aloha").finish();
    }

    assertThat(spans).isEmpty();
    assertThat(mutableSpans).hasSize(1);
  }

  @Test public void spanHandler_recordsWhenUnsampledIfContextSamplesLocal() {
    AtomicBoolean sampledLocal = new AtomicBoolean();
    try (Tracing tracing = Tracing.newBuilder()
      .spanReporter(spans::add)
      .propagationFactory(new Propagation.Factory() {
        @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
          return B3SinglePropagation.FACTORY.create(keyFactory);
        }

        @Override public TraceContext decorate(TraceContext context) {
          if (sampledLocal.getAndSet(true)) return context;
          return context.toBuilder().sampledLocal(true).build();
        }
      })
      .addSpanHandler(spanHandler)
      .sampler(Sampler.NEVER_SAMPLE)
      .build()) {
      tracing.tracer().newTrace().start().name("one").finish();
      tracing.tracer().newTrace().start().name("two").finish();
    }

    assertThat(spans).isEmpty();
    assertThat(mutableSpans).hasSize(1);
    assertThat(mutableSpans.get(0).name()).isEqualTo("one");
  }
}
