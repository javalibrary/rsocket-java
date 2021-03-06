/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.rsocket.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.rsocket.DuplexConnection;
import io.rsocket.Frame;
import io.rsocket.frame.FrameHeaderFlyweight;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.Fuseable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.FutureMono;
import reactor.util.concurrent.Queues;

import java.util.Objects;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.rsocket.frame.FrameHeaderFlyweight.FRAME_LENGTH_SIZE;

/**
 * An implementation of {@link DuplexConnection} that connects via a Websocket.
 *
 * <p>rsocket-java strongly assumes that each Frame is encoded with the length. This is not true for
 * message oriented transports so this must be specifically dropped from Frames sent and stitched
 * back on for frames received.
 */
public final class WebsocketDuplexConnection implements DuplexConnection {

  private final Connection connection;
  private final Disposable channelClosed;

  /**
   * Creates a new instance
   *
   * @param connection the {@link Connection} to for managing the server
   */
  public WebsocketDuplexConnection(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection must not be null");
    this.channelClosed =
        FutureMono.from(connection.channel().closeFuture())
            .doFinally(
                s -> {
                  if (!isDisposed()) {
                    dispose();
                  }
                })
            .subscribe();
  }

  @Override
  public void dispose() {
    connection.dispose();
  }

  @Override
  public boolean isDisposed() {
    return connection.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    return connection
        .onDispose()
        .doFinally(
            s -> {
              if (!channelClosed.isDisposed()) {
                channelClosed.dispose();
              }
            });
  }

  @Override
  public Flux<Frame> receive() {
    return connection
        .inbound()
        .receive()
        .map(
            buf -> {
              CompositeByteBuf composite = connection.channel().alloc().compositeBuffer();
              ByteBuf length = wrappedBuffer(new byte[FRAME_LENGTH_SIZE]);
              FrameHeaderFlyweight.encodeLength(length, 0, buf.readableBytes());
              composite.addComponents(true, length, buf.retain());
              return Frame.from(composite);
            });
  }

  @Override
  public Mono<Void> send(Publisher<Frame> frames) {
    return Flux.from(frames)
        .transform(
            frameFlux -> {
              if (frameFlux instanceof Fuseable.QueueSubscription) {
                Fuseable.QueueSubscription<Frame> queueSubscription =
                    (Fuseable.QueueSubscription<Frame>) frameFlux;
                queueSubscription.requestFusion(Fuseable.ASYNC);
                return new SendPublisher<>(
                    queueSubscription,
                    frameFlux,
                    connection.channel(),
                    this::toBinaryWebSocketFrame,
                    binaryWebSocketFrame -> binaryWebSocketFrame.content().readableBytes());
              } else {
                return new SendPublisher<>(
                    Queues.<Frame>small().get(),
                    frameFlux,
                    connection.channel(),
                    this::toBinaryWebSocketFrame,
                    binaryWebSocketFrame -> binaryWebSocketFrame.content().readableBytes());
              }
            })
        .then();
  }

  private BinaryWebSocketFrame toBinaryWebSocketFrame(Frame frame) {
    return new BinaryWebSocketFrame(frame.content().skipBytes(FRAME_LENGTH_SIZE).retain());
  }
}
