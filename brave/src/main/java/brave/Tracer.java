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

import brave.handler.FinishedSpanHandler;
import brave.handler.MutableSpan;
import brave.handler.SpanListener;
import brave.internal.InternalPropagation;
import brave.internal.Nullable;
import brave.internal.Platform;
import brave.internal.recorder.PendingSpan;
import brave.internal.recorder.PendingSpans;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import brave.sampler.SamplerFunction;
import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static brave.internal.InternalPropagation.FLAG_LOCAL_ROOT;
import static brave.internal.InternalPropagation.FLAG_SAMPLED;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_LOCAL;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_SET;
import static brave.internal.InternalPropagation.FLAG_SHARED;
import static brave.internal.Lists.concatImmutableLists;
import static brave.propagation.SamplingFlags.EMPTY;
import static brave.propagation.SamplingFlags.NOT_SAMPLED;
import static brave.propagation.SamplingFlags.SAMPLED;

/**
 * Using a tracer, you can create a root span capturing the critical path of a request. Child spans
 * can be created to allocate latency relating to outgoing requests.
 *
 * When tracing single-threaded code, just run it inside a scoped span:
 * <pre>{@code
 * // Start a new trace or a span within an existing trace representing an operation
 * ScopedSpan span = tracer.startScopedSpan("encode");
 * try {
 *   // The span is in "scope" so that downstream code such as loggers can see trace IDs
 *   return encoder.encode();
 * } catch (RuntimeException | Error e) {
 *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
 *   throw e;
 * } finally {
 *   span.finish();
 * }
 * }</pre>
 *
 * <p>When you need more features, or finer control, use the {@linkplain Span} type:
 * <pre>{@code
 * // Start a new trace or a span within an existing trace representing an operation
 * Span span = tracer.nextSpan().name("encode").start();
 * // Put the span in "scope" so that downstream code such as loggers can see trace IDs
 * try (SpanInScope ws = tracer.withSpanInScope(span)) {
 *   return encoder.encode();
 * } catch (RuntimeException | Error e) {
 *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
 *   throw e;
 * } finally {
 *   span.finish(); // note the scope is independent of the span. Always finish a span.
 * }
 * }</pre>
 *
 * <p>Both of the above examples report the exact same span on finish!
 *
 * @see Span
 * @see ScopedSpan
 * @see Propagation
 */
public class Tracer {

  final Clock clock;
  final Propagation.Factory propagationFactory;
  final SpanListener spanListener;
  final FinishedSpanHandler finishedSpanHandler;
  final PendingSpans pendingSpans;
  final Sampler sampler;
  final CurrentTraceContext currentTraceContext;
  final boolean traceId128Bit, supportsJoin, alwaysSampleLocal;
  final AtomicBoolean noop;

  Tracer(
    Clock clock,
    Propagation.Factory propagationFactory,
    SpanListener spanListener,
    FinishedSpanHandler finishedSpanHandler,
    PendingSpans pendingSpans,
    Sampler sampler,
    CurrentTraceContext currentTraceContext,
    boolean traceId128Bit,
    boolean supportsJoin,
    boolean alwaysSampleLocal,
    AtomicBoolean noop
  ) {
    this.clock = clock;
    this.propagationFactory = propagationFactory;
    this.spanListener = spanListener;
    this.finishedSpanHandler = finishedSpanHandler;
    this.pendingSpans = pendingSpans;
    this.sampler = sampler;
    this.currentTraceContext = currentTraceContext;
    this.traceId128Bit = traceId128Bit;
    this.supportsJoin = supportsJoin;
    this.alwaysSampleLocal = alwaysSampleLocal;
    this.noop = noop;
  }

  /**
   * @since 4.19
   * @deprecated Since 5.8, use {@link #nextSpan(SamplerFunction, Object)}  or {@link
   * #startScopedSpan(String, SamplerFunction, Object)}
   */
  @Deprecated public Tracer withSampler(Sampler sampler) {
    if (sampler == null) throw new NullPointerException("sampler == null");
    return new Tracer(
      clock,
      propagationFactory,
      spanListener,
      finishedSpanHandler,
      pendingSpans,
      sampler,
      currentTraceContext,
      traceId128Bit,
      supportsJoin,
      alwaysSampleLocal,
      noop
    );
  }

  /**
   * Explicitly creates a new trace. The result will be a root span (no parent span ID).
   *
   * <p>To implicitly create a new trace, or a span within an existing one, use {@link
   * #nextSpan()}.
   */
  public Span newTrace() {
    return _toSpan(null, newRootContext(0));
  }

  /**
   * Joining is re-using the same trace and span ids extracted from an incoming RPC request. This
   * should not be used for messaging operations, as {@link #nextSpan(TraceContextOrSamplingFlags)}
   * is a better choice.
   *
   * <p>When this incoming context is sampled, we assume this is a shared span, one where the
   * caller and the current tracer report to the same span IDs. If no sampling decision occurred
   * yet, we have exclusive access to this span ID.
   *
   * <p>Here's an example of conditionally joining a span, depending on if a trace context was
   * extracted from an incoming request.
   *
   * <pre>{@code
   * extracted = extractor.extract(request);
   * span = contextOrFlags.context() != null
   *          ? tracer.joinSpan(contextOrFlags.context())
   *          : tracer.nextSpan(extracted);
   * }</pre>
   *
   * <p><em>Note:</em> When {@link Propagation.Factory#supportsJoin()} is false, this will always
   * fork a new child via {@link #newChild(TraceContext)}.
   *
   * @see Propagation
   * @see Extractor#extract(Object)
   * @see TraceContextOrSamplingFlags#context()
   * @see #nextSpan(TraceContextOrSamplingFlags)
   */
  public final Span joinSpan(TraceContext context) {
    if (context == null) throw new NullPointerException("context == null");
    if (!supportsJoin) return newChild(context);

    // set shared flag if not already done
    int flags = InternalPropagation.instance.flags(context);
    if (!context.shared()) {
      flags |= FLAG_SHARED;
      return toSpan(context, InternalPropagation.instance.withFlags(context, flags));
    } else {
      flags &= ~FLAG_SHARED;
      return toSpan(InternalPropagation.instance.withFlags(context, flags), context);
    }
  }

  /** Returns an equivalent context if exists in the pending map */
  TraceContext swapForPendingContext(TraceContext context) {
    PendingSpan pendingSpan = pendingSpans.get(context);
    return pendingSpan != null ? pendingSpan.context() : null;
  }

  /**
   * Explicitly creates a child within an existing trace. The result will be have its parent ID set
   * to the input's span ID. If a sampling decision has not yet been made, one will happen here.
   *
   * <p>To implicitly create a new trace, or a span within an existing one, use {@link
   * #nextSpan()}.
   */
  public Span newChild(TraceContext parent) {
    if (parent == null) throw new NullPointerException("parent == null");
    return _toSpan(parent, decorateContext(parent, parent.spanId()));
  }

  TraceContext newRootContext(int flags) {
    flags &= ~FLAG_SHARED; // cannot be shared if we aren't reusing the span ID
    return decorateContext(flags, 0L, 0L, 0L, 0L, 0L, Collections.emptyList());
  }

  /**
   * Decorates a context after backfilling any missing data such as span IDs or sampling state.
   *
   * <p>Called by methods which can accept externally supplied parent trace contexts: Ex. {@link
   * #newChild(TraceContext)} and {@link #startScopedSpanWithParent(String, TraceContext)}. This
   * implies the {@link TraceContext#localRootId()} could be zero, if the context was manually
   * created.
   */
  TraceContext decorateContext(TraceContext parent, long parentId) {
    int flags = InternalPropagation.instance.flags(parent);
    flags &= ~FLAG_SHARED; // cannot be shared if we aren't reusing the span ID
    return decorateContext(
      flags,
      parent.traceIdHigh(),
      parent.traceId(),
      parent.localRootId(),
      parentId,
      0L,
      parent.extra()
    );
  }

  /**
   * Creates a trace context object holding the below fields. When fields such as span ID are
   * absent, they will be backfilled. Then, any missing state managed by the tracer are applied,
   * such as the "local root". Finally, decoration hooks apply to ensure any propagation state are
   * added to the "extra" section of the result. This supports functionality like baggage
   * propagation.
   *
   * <p>All parameters except span ID can be empty in the case of a new root span.
   *
   * @param flags any incoming flags from a parent context.
   * @param traceIdHigh See {@link TraceContext#traceIdHigh()}
   * @param traceId Zero is a new trace. Otherwise, {@link TraceContext#traceId()}.
   * @param localRootId Zero is a new local root. Otherwise, {@link TraceContext#localRootId()}.
   * @param parentId Same as {@link TraceContext#parentIdAsLong()}.
   * @param spanId When non-zero this is a shared span. See {@link TraceContext#spanId()}.
   * @param extra Any additional {@link TraceContext#extra() propagated state}.
   * @return a decorated, sampled context with local root information applied.
   */
  TraceContext decorateContext(
    int flags,
    long traceIdHigh,
    long traceId,
    long localRootId,
    long parentId,
    long spanId,
    List<Object> extra
  ) {
    if (alwaysSampleLocal && (flags & FLAG_SAMPLED_LOCAL) != FLAG_SAMPLED_LOCAL) {
      flags |= FLAG_SAMPLED_LOCAL;
    }

    if (spanId == 0L) spanId = nextId();

    if (traceId == 0L) { // make a new trace ID
      traceIdHigh = traceId128Bit ? Platform.get().nextTraceIdHigh() : 0L;
      traceId = spanId;
    }

    if ((flags & FLAG_SAMPLED_SET) != FLAG_SAMPLED_SET) { // cheap check for not yet sampled
      flags = InternalPropagation.sampled(sampler.isSampled(traceId), flags);
      flags &= ~FLAG_SHARED; // cannot be shared if not yet sampled
    }

    // Zero when root or an externally managed context was passed to newChild or scopedWithParent
    if (localRootId == 0L) {
      localRootId = spanId;
      flags |= FLAG_LOCAL_ROOT;
    } else {
      flags &= ~FLAG_LOCAL_ROOT;
    }
    return propagationFactory.decorate(InternalPropagation.instance.newTraceContext(
      flags,
      traceIdHigh,
      traceId,
      localRootId,
      parentId,
      spanId,
      extra
    ));
  }

  /**
   * This creates a new span based on parameters extracted from an incoming request. This will
   * always result in a new span. If no trace identifiers were extracted, a span will be created
   * based on the implicit context in the same manner as {@link #nextSpan()}. If a sampling decision
   * has not yet been made, one will happen here.
   *
   * <p>Ex.
   * <pre>{@code
   * extracted = extractor.extract(request);
   * span = tracer.nextSpan(extracted);
   * }</pre>
   *
   * <p><em>Note:</em> Unlike {@link #joinSpan(TraceContext)}, this does not attempt to re-use
   * extracted span IDs. This means the extracted context (if any) is the parent of the span
   * returned.
   *
   * <p><em>Note:</em> If a context could be extracted from the input, that trace is resumed, not
   * whatever the {@link #currentSpan()} was. Make sure you re-apply {@link #withSpanInScope(Span)}
   * so that data is written to the correct trace.
   *
   * @see Propagation
   * @see Extractor#extract(Object)
   * @see #nextSpan(SamplerFunction, Object)
   */
  // TODO: BRAVE6 a MutableTraceContext object is cleaner especially here, as we can represent a
  // partial result, such as trace id without span ID without declaring a special type. Also, the
  // the code is a bit easier to work with especially if we want to avoid excess allocations. Here,
  // we manually code some things to keep the cpu and allocations low, at the cost of readability.
  public Span nextSpan(TraceContextOrSamplingFlags extracted) {
    if (extracted == null) throw new NullPointerException("extracted == null");
    TraceContext context = extracted.context();
    if (context != null) return newChild(context);

    TraceIdContext traceIdContext = extracted.traceIdContext();
    if (traceIdContext != null) {
      return _toSpan(null, decorateContext(
        InternalPropagation.instance.flags(extracted.traceIdContext()),
        traceIdContext.traceIdHigh(),
        traceIdContext.traceId(),
        0L,
        0L,
        0L,
        extracted.extra()
      ));
    }

    SamplingFlags samplingFlags = extracted.samplingFlags();
    List<Object> extra = extracted.extra();

    TraceContext parent = currentTraceContext.get();
    int flags;
    long traceIdHigh = 0L, traceId = 0L, localRootId = 0L, spanId = 0L;
    if (parent != null) {
      // At this point, we didn't extract trace IDs, but do have a trace in progress. Since typical
      // trace sampling is up front, we retain the decision from the parent.
      flags = InternalPropagation.instance.flags(parent);
      traceIdHigh = parent.traceIdHigh();
      traceId = parent.traceId();
      localRootId = parent.localRootId();
      spanId = parent.spanId();
      extra = concatImmutableLists(extra, parent.extra());
    } else {
      flags = InternalPropagation.instance.flags(samplingFlags);
    }
    return _toSpan(parent,
      decorateContext(flags, traceIdHigh, traceId, localRootId, spanId, 0L, extra));
  }

  /**
   * Converts the context to a Span object after decorating it for propagation.
   *
   * <p>This api is not advised for routine use. It is better to hold a reference to a span created
   * elsewhere vs rely on implicit lookups.
   */
  public Span toSpan(TraceContext context) {
    return toSpan(null, context);
  }

  Span toSpan(@Nullable TraceContext parent, TraceContext context) {
    // Re-use a pending context if present: This ensures reference consistency on Span.context()
    TraceContext pendingContext = swapForPendingContext(context);
    if (pendingContext != null) return _toSpan(parent, pendingContext);

    // There are a few known scenarios for the context to be absent from the pending map:
    // * Created by a separate tracer (localRootId set)
    // * Recreating the same trace context after it was garbage collected (localRootId set)
    // * Ad-hoc usage of TraceContext.Builder (localRootId not set, as only settable internally)
    //
    // The first two scenarios are currently indistinguishable from each other. If we had a way to
    // tell if the current tracer already decorated the context, we could avoid re-decorating it
    // in the case of recreation. This is an edge case anyway and decoration should be idempotent.
    // Hence, we decorate unconditionally here.
    TraceContext decorated = decorateContext(
      InternalPropagation.instance.flags(context),
      context.traceIdHigh(),
      context.traceId(),
      parent != null ? context.localRootId() : 0L, // we are now a local root
      context.parentIdAsLong(),
      context.spanId(),
      context.extra()
    );

    return _toSpan(parent, decorated);
  }

  Span _toSpan(@Nullable TraceContext parent, TraceContext context) {
    if (isNoop(context)) return new NoopSpan(context);

    // allocate a mutable span in case multiple threads call this method.. they'll use the same data
    PendingSpan pendingSpan = pendingSpans.getOrCreate(parent, context, false);
    TraceContext pendingContext = pendingSpan.context();
    // A lost race of Tracer.toSpan(context) is the only known situation where "context" won't be
    // the same as pendingSpan.context()
    if (pendingContext != null) context = pendingContext;
    return new RealSpan(context, pendingSpans, pendingSpan.state(), pendingSpan.clock(),
      finishedSpanHandler);
  }

  /**
   * Makes the given span the "current span" and returns an object that exits that scope on close.
   * Calls to {@link #currentSpan()} and {@link #currentSpanCustomizer()} will affect this span
   * until the return value is closed.
   *
   * <p>The most convenient way to use this method is via the try-with-resources idiom.
   *
   * Ex.
   * <pre>{@code
   * // Assume a framework interceptor uses this method to set the inbound span as current
   * try (SpanInScope ws = tracer.withSpanInScope(span)) {
   *   return inboundRequest.invoke();
   * // note: try-with-resources closes the scope *before* the catch block
   * } catch (RuntimeException | Error e) {
   *   span.error(e);
   *   throw e;
   * } finally {
   *   span.finish();
   * }
   *
   * // An unrelated framework interceptor can now lookup the correct parent for outbound requests
   * Span parent = tracer.currentSpan()
   * Span span = tracer.nextSpan().name("outbound").start(); // parent is implicitly looked up
   * try (SpanInScope ws = tracer.withSpanInScope(span)) {
   *   return outboundRequest.invoke();
   * // note: try-with-resources closes the scope *before* the catch block
   * } catch (RuntimeException | Error e) {
   *   span.error(e);
   *   throw e;
   * } finally {
   *   span.finish();
   * }
   * }</pre>
   *
   * <p>When tracing in-process commands, prefer {@link #startScopedSpan(String)} which scopes by
   * default.
   *
   * <p>Note: While downstream code might affect the span, calling this method, and calling close
   * on the result have no effect on the input. For example, calling close on the result does not
   * finish the span. Not only is it safe to call close, you must call close to end the scope, or
   * risk leaking resources associated with the scope.
   *
   * @param span span to place into scope or null to clear the scope
   */
  public SpanInScope withSpanInScope(@Nullable Span span) {
    return new SpanInScope(currentTraceContext.newScope(span != null ? span.context() : null));
  }

  /**
   * Returns a customizer for current span in scope or noop if there isn't one.
   *
   * <p>Unlike {@link CurrentSpanCustomizer}, this represents a single span. Accordingly, this
   * reference should not be saved as a field. That said, it is more efficient to save this result
   * as a method-local variable vs repeated calls.
   */
  public SpanCustomizer currentSpanCustomizer() {
    // note: we don't need to decorate the context for propagation as it is only used for toString
    TraceContext context = currentTraceContext.get();
    if (context == null || isNoop(context)) return NoopSpanCustomizer.INSTANCE;
    return new SpanCustomizerShield(toSpan(context));
  }

  /**
   * Returns the current span in scope or null if there isn't one.
   *
   * <p>When entering user code, prefer {@link #currentSpanCustomizer()} as it is a stable type and
   * will never return null.
   */
  @Nullable public Span currentSpan() {
    TraceContext context = currentTraceContext.get();
    if (context == null) return null;
    // Returns a lazy span to reduce overhead when tracer.currentSpan() is invoked just to see if
    // one exists, or when the result is never used.
    return new LazySpan(this, context);
  }

  /**
   * Returns a new child span if there's a {@link #currentSpan()} or a new trace if there isn't.
   *
   * <p>Prefer {@link #startScopedSpan(String)} if you are tracing a synchronous function or code
   * block.
   */
  public Span nextSpan() {
    TraceContext parent = currentTraceContext.get();
    return parent != null ? newChild(parent) : newTrace();
  }

  /**
   * Returns a new child span if there's a {@link #currentSpan()} or a new trace if there isn't. The
   * result is the "current span" until {@link ScopedSpan#finish()} is called.
   *
   * Here's an example:
   * <pre>{@code
   * ScopedSpan span = tracer.startScopedSpan("encode");
   * try {
   *   // The span is in "scope" so that downstream code such as loggers can see trace IDs
   *   return encoder.encode();
   * } catch (RuntimeException | Error e) {
   *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
   *   throw e;
   * } finally {
   *   span.finish();
   * }
   * }</pre>
   */
  public ScopedSpan startScopedSpan(String name) {
    return startScopedSpanWithParent(name, currentTraceContext.get());
  }

  /**
   * Like {@link #startScopedSpan(String)} except when there is no trace in process, the sampler
   * {@link SamplerFunction#trySample(Object) triggers} against the supplied argument.
   *
   * @param name the {@link Span#name(String) span name}
   * @param samplerFunction invoked if there's no {@link CurrentTraceContext#get() current trace}
   * @param arg parameter to {@link SamplerFunction#trySample(Object)}
   * @see #nextSpan(SamplerFunction, Object)
   * @since 5.8
   */
  public <T> ScopedSpan startScopedSpan(String name, SamplerFunction<T> samplerFunction, T arg) {
    if (name == null) throw new NullPointerException("name == null");
    TraceContext parent = currentTraceContext.get();
    return newScopedSpan(parent, nextContext(samplerFunction, arg, parent), name);
  }

  /**
   * Like {@link #nextSpan()} except when there is no trace in process, the sampler {@link
   * SamplerFunction#trySample(Object) triggers} against the supplied argument.
   *
   * @param samplerFunction invoked if there's no {@link CurrentTraceContext#get() current trace}
   * @param arg parameter to {@link SamplerFunction#trySample(Object)}
   * @see #startScopedSpan(String, SamplerFunction, Object)
   * @see #nextSpan(TraceContextOrSamplingFlags)
   * @since 5.8
   */
  public <T> Span nextSpan(SamplerFunction<T> samplerFunction, T arg) {
    TraceContext parent = currentTraceContext.get();
    return _toSpan(parent, nextContext(samplerFunction, arg, parent));
  }

  /**
   * Like {@link #nextSpan(SamplerFunction, Object)} except this controls the parent context
   * explicitly. This is useful when an invocation context is propagated manually, commonly the case
   * with asynchronous client frameworks.
   *
   * @param samplerFunction invoked if there's no {@link CurrentTraceContext#get() current trace}
   * @param arg parameter to {@link SamplerFunction#trySample(Object)}
   * @param parent of the new span, or {@code null} if it should have no parent
   * @see #nextSpan(SamplerFunction, Object)
   * @since 5.10
   */
  public <T> Span nextSpanWithParent(SamplerFunction<T> samplerFunction, T arg,
    @Nullable TraceContext parent) {
    return _toSpan(parent, nextContext(samplerFunction, arg, parent));
  }

  <T> TraceContext nextContext(SamplerFunction<T> samplerFunction, T arg, TraceContext parent) {
    if (samplerFunction == null) throw new NullPointerException("samplerFunction == null");
    if (arg == null) throw new NullPointerException("arg == null");
    if (parent != null) return decorateContext(parent, parent.spanId());

    Boolean sampled = samplerFunction.trySample(arg);
    SamplingFlags flags = sampled != null ? (sampled ? SAMPLED : NOT_SAMPLED) : EMPTY;
    return newRootContext(InternalPropagation.instance.flags(flags));
  }

  /**
   * Same as {@link #startScopedSpan(String)}, except ignores the current trace context.
   *
   * <p>Use this when you are creating a scoped span in a method block where the parent was
   * created. You can also use this to force a new trace by passing null parent.
   */
  // this api is needed to make tools such as executors which need to carry the invocation context
  public ScopedSpan startScopedSpanWithParent(String name, @Nullable TraceContext parent) {
    if (name == null) throw new NullPointerException("name == null");
    TraceContext context =
      parent != null ? decorateContext(parent, parent.spanId()) : newRootContext(0);
    return newScopedSpan(parent, context, name);
  }

  ScopedSpan newScopedSpan(@Nullable TraceContext parent, TraceContext context, String name) {
    Scope scope = currentTraceContext.newScope(context);
    if (isNoop(context)) return new NoopScopedSpan(context, scope);

    PendingSpan pendingSpan = pendingSpans.getOrCreate(parent, context, true);
    Clock clock = pendingSpan.clock();
    MutableSpan state = pendingSpan.state();
    state.name(name);
    return new RealScopedSpan(context, scope, state, clock, pendingSpans, finishedSpanHandler);
  }

  /** A span remains in the scope it was bound to until close is called. */
  public static final class SpanInScope implements Closeable {
    final Scope scope;

    // This type hides the SPI type and allows us to double-check the SPI didn't return null.
    SpanInScope(Scope scope) {
      if (scope == null) throw new NullPointerException("scope == null");
      this.scope = scope;
    }

    /** No exceptions are thrown when unbinding a span scope. */
    @Override public void close() {
      scope.close();
    }

    @Override public String toString() {
      return scope.toString();
    }
  }

  @Override public String toString() {
    TraceContext currentSpan = currentTraceContext.get();
    return "Tracer{"
      + (currentSpan != null ? ("currentSpan=" + currentSpan + ", ") : "")
      + (noop.get() ? "noop=true, " : "")
      + "finishedSpanHandler=" + finishedSpanHandler
      + "}";
  }

  boolean isNoop(TraceContext context) {
    if (finishedSpanHandler == FinishedSpanHandler.NOOP || noop.get()) return true;
    int flags = InternalPropagation.instance.flags(context);
    if ((flags & FLAG_SAMPLED_LOCAL) == FLAG_SAMPLED_LOCAL) return false;
    return (flags & FLAG_SAMPLED) != FLAG_SAMPLED;
  }

  /** Generates a new 64-bit ID, taking care to dodge zero which can be confused with absent */
  long nextId() {
    long nextId = Platform.get().randomLong();
    while (nextId == 0L) {
      nextId = Platform.get().randomLong();
    }
    return nextId;
  }
}
