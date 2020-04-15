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
package brave.internal.handler;

import brave.handler.MutableSpan;
import brave.handler.SpanCollector;
import brave.internal.Platform;
import brave.propagation.TraceContext;
import java.util.Arrays;

import static brave.internal.Throwables.propagateIfFatal;

/** This logs exceptions instead of raising an error, as the supplied collector could have bugs. */
public final class SafeSpanCollector implements SpanCollector {
  // Array ensures no iterators are created at runtime
  public static SpanCollector create(SpanCollector[] handlers) {
    if (handlers.length == 0) return SpanCollector.NOOP;
    if (handlers.length == 1) return new SafeSpanCollector(handlers[0]);
    return new SafeSpanCollector(new CompositeSpanCollector(handlers));
  }

  final SpanCollector delegate;

  SafeSpanCollector(SpanCollector delegate) {
    this.delegate = delegate;
  }

  @Override public void begin(TraceContext context, MutableSpan span, TraceContext parent) {
    try {
      delegate.begin(context, span, parent);
    } catch (Throwable t) {
      propagateIfFatal(t);
      Platform.get().log("error handling onSampled {0}", context, t);
    }
  }

  @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    try {
      return delegate.end(context, span, cause);
    } catch (Throwable t) {
      propagateIfFatal(t);
      Platform.get().log("error handling onCollected {0}", context, t);
      return true; // user error in this collector shouldn't impact another
    }
  }

  static final class CompositeSpanCollector implements SpanCollector {
    final SpanCollector[] collectors;

    CompositeSpanCollector(SpanCollector[] collectors) {
      this.collectors = collectors;
    }

    @Override public void begin(TraceContext context, MutableSpan span, TraceContext parent) {
      for (int i = collectors.length; i-- > 0; ) {
        collectors[i].begin(context, span, parent);
      }
    }

    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      for (int i = collectors.length; i-- > 0; ) {
        if (!collectors[i].end(context, span, cause)) return false;
      }
      return true;
    }

    @Override public String toString() {
      return Arrays.toString(collectors);
    }
  }
}
