package com.trace.backend.integration.processor;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancel oversized responses before the HTTP client buffers them in memory. */
public final class BoundedResponse implements HttpResponse.BodySubscriber<byte[]> {
  private final HttpResponse.BodySubscriber<byte[]> body =
      HttpResponse.BodySubscribers.ofByteArray();
  private final int limit;
  private Flow.Subscription subscription;
  private long received;
  private boolean failed;

  public BoundedResponse(int limit) {
    this.limit = limit;
  }

  public CompletionStage<byte[]> getBody() {
    return body.getBody();
  }

  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    body.onSubscribe(subscription);
  }

  public void onNext(List<ByteBuffer> chunks) {
    if (failed) return;
    for (var chunk : chunks) received += chunk.remaining();
    if (received > limit) {
      failed = true;
      subscription.cancel();
      body.onError(new IOException("Processor response limit exceeded"));
    } else body.onNext(chunks);
  }

  public void onError(Throwable error) {
    if (!failed) body.onError(error);
  }

  public void onComplete() {
    if (!failed) body.onComplete();
  }
}
