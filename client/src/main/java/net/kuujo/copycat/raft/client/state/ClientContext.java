/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft.client.state;

import net.kuujo.copycat.io.serializer.Serializer;
import net.kuujo.copycat.io.transport.Client;
import net.kuujo.copycat.io.transport.Connection;
import net.kuujo.copycat.io.transport.Transport;
import net.kuujo.copycat.io.transport.TransportException;
import net.kuujo.copycat.raft.*;
import net.kuujo.copycat.raft.protocol.*;
import net.kuujo.copycat.util.Managed;
import net.kuujo.copycat.util.concurrent.Context;
import net.kuujo.copycat.util.concurrent.Futures;
import net.kuujo.copycat.util.concurrent.SingleThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raft client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ClientContext implements Managed<Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientContext.class);
  private static final long REQUEST_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
  protected final UUID id = UUID.randomUUID();
  private Members members;
  private final Transport transport;
  private final Client client;
  private Member remote;
  private Connection connection;
  private final Context context;
  private CompletableFuture<Void> registerFuture;
  private final AtomicBoolean keepAlive = new AtomicBoolean();
  private final Random random = new Random();
  private ScheduledFuture<?> keepAliveTimer;
  private ScheduledFuture<?> registerTimer;
  private long keepAliveInterval = 1000;
  private volatile boolean open;
  private CompletableFuture<Void> openFuture;
  protected volatile int leader;
  protected volatile long term;
  private final ClientSession session;

  public ClientContext(Members members, Transport transport, Serializer serializer) {
    if (members == null)
      throw new NullPointerException("members cannot be null");
    if (transport == null)
      throw new NullPointerException("transport cannot be null");
    if (serializer == null)
      throw new NullPointerException("serializer cannot be null");

    this.members = members;
    this.transport = transport;
    this.context = new SingleThreadContext("copycat-client-" + UUID.randomUUID().toString(), serializer.clone());
    this.client = transport.client(id);
    this.session = new ClientSession(context);
  }

  /**
   * Returns the client context.
   *
   * @return The client context.
   */
  public Context getContext() {
    return context;
  }

  /**
   * Returns the cluster leader.
   *
   * @return The cluster leader.
   */
  public Member getLeader() {
    return leader != 0 ? members.member(leader) : null;
  }

  /**
   * Sets the cluster leader.
   *
   * @param leader The cluster leader.
   * @return The Raft client.
   */
  ClientContext setLeader(int leader) {
    this.leader = leader;
    return this;
  }

  /**
   * Returns the cluster term.
   *
   * @return The cluster term.
   */
  public long getTerm() {
    return term;
  }

  /**
   * Sets the cluster term.
   *
   * @param term The cluster term.
   * @return The Raft client.
   */
  ClientContext setTerm(long term) {
    this.term = term;
    return this;
  }

  /**
   * Returns the client session.
   *
   * @return The client session.
   */
  public Session getSession() {
    return session;
  }

  /**
   * Sets the client members.
   *
   * @param members The client members.
   * @return The client context.
   */
  protected ClientContext setMembers(Members members) {
    this.members = members;
    return this;
  }

  /**
   * Returns the keep alive interval.
   *
   * @return The keep alive interval.
   */
  public long getKeepAliveInterval() {
    return keepAliveInterval;
  }

  /**
   * Sets the keep alive interval.
   *
   * @param keepAliveInterval The keep alive interval.
   * @return The Raft client.
   */
  public ClientContext setKeepAliveInterval(long keepAliveInterval) {
    if (keepAliveInterval <= 0)
      throw new IllegalArgumentException("keep alive interval must be positive");
    this.keepAliveInterval = keepAliveInterval;
    return this;
  }

  /**
   * Logs a request.
   */
  private <T extends Request<T>> T logRequest(T request, Member member) {
    LOGGER.debug("Sending {} to {}", request, member);
    return request;
  }

  /**
   * Logs a response.
   */
  private <T extends Response<T>> T logResponse(T response, Member member) {
    LOGGER.debug("Received {} from {}", response, member);
    return response;
  }

  /**
   * Gets a connection to a specific member.
   *
   * @param member The member for which to get the connection.
   * @return A completable future to be completed once the connection has been connected.
   */
  protected CompletableFuture<Connection> getConnection(Member member) {
    if (connection != null && member.equals(this.remote)) {
      return CompletableFuture.completedFuture(connection);
    }

    final InetSocketAddress address;
    try {
      address = new InetSocketAddress(InetAddress.getByName(member.host()), member.port());
    } catch (UnknownHostException e) {
      return Futures.exceptionalFuture(e);
    }

    if (connection != null) {
      return connection.close().thenCompose(v -> client.connect(address)).thenApply(connection -> {
        this.remote = member;
        return connect(connection);
      });
    }

    return client.connect(address).thenApply(connection -> {
      this.remote = member;
      return connect(connection);
    });
  }

  /**
   * Sets up a new connection.
   */
  protected Connection connect(Connection connection) {
    this.connection = connection;
    session.connect(connection);
    connection.closeListener(c -> this.connection = null);
    connection.exceptionListener(e -> this.connection = null);
    return connection;
  }

  /**
   * Submits a command.
   *
   * @param command The command to submit.
   * @param <R> The command result type.
   * @return A completable future to be completed with the command result.
   */
  @SuppressWarnings("unchecked")
  public <R> CompletableFuture<R> submit(Command<R> command) {
    if (!open)
      throw new IllegalStateException("protocol not open");

    CompletableFuture<R> future = new CompletableFuture<>();
    context.execute(() -> {
      if (session.isClosed()) {
        future.completeExceptionally(new IllegalStateException("session not open"));
        return;
      }

      CommandRequest request = CommandRequest.builder()
        .withSession(session.id())
        .withRequest(session.nextRequest())
        .withVersion(session.getVersion())
        .withCommand(command)
        .build();

      this.<R>submit(request).whenComplete((result, error) -> {
        if (error == null) {
          future.complete(result);
        } else {
          future.completeExceptionally(error);
        }
      });
    });
    return future;
  }

  /**
   * Recursively submits the command to the cluster.
   *
   * @param request The request to submit.
   * @return The completion future.
   */
  private <T> CompletableFuture<T> submit(CommandRequest request) {
    return submit(request, new CompletableFuture<>());
  }

  /**
   * Recursively submits the command to the cluster.
   *
   * @param request The request to submit.
   * @param future The future to complete once the command has succeeded.
   * @return The completion future.
   */
  private <T> CompletableFuture<T> submit(CommandRequest request, CompletableFuture<T> future) {
    Member member = selectMember();
    this.<T>submit(request, member).whenComplete((result, error) -> {
      if (error == null) {
        request.close();
        future.complete(result);
      } else if (error instanceof TimeoutException) {
        resetMember();
        submit(request, future);
      } else if (error instanceof NoLeaderException) {
        resetMember();
        submit(request, future);
      } else if (error instanceof TransportException) {
        LOGGER.warn("Failed to communicate with {}: {}", member, error);
        resetMember();
        submit(request, future);
      } else if (error instanceof UnknownSessionException) {
        LOGGER.warn("Lost session: {}", session.id());

        session.expire();

        register().thenRun(() -> {
          submit(CommandRequest.builder(request)
            .withSession(session.id())
            .build(), future);
        });
      } else {
        request.close();
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  /**
   * Attempts to submit the request to the given member.
   *
   * @param request The request to submit.
   * @param member The member to which to submit the request.
   * @return A completable future to be completed with the result.
   */
  @SuppressWarnings("unchecked")
  private <T> CompletableFuture<T> submit(CommandRequest request, Member member) {
    CompletableFuture<T> future = new CompletableFuture<>();
    ScheduledFuture<?> timeoutFuture = context.schedule(() -> future.completeExceptionally(new TimeoutException("request timed out")), REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);

    LOGGER.debug("Submitting {} to {}", request, member);
    getConnection(member).thenAccept(connection -> {
      connection.<CommandRequest, CommandResponse>send(request).whenComplete((response, error) -> {
        timeoutFuture.cancel(false);
        if (error == null) {
          if (response.status() == Response.Status.OK) {
            future.complete((T) response.result());
          } else {
            future.completeExceptionally(response.error().createException());
          }

          session.setVersion(request.request());
        } else {
          future.completeExceptionally(error);
        }
      });
    });

    return future;
  }

  /**
   * Submits a query.
   *
   * @param query The query to submit.
   * @param <R> The query result type.
   * @return A completable future to be completed with the query result.
   */
  @SuppressWarnings("unchecked")
  public <R> CompletableFuture<R> submit(Query<R> query) {
    if (!open)
      throw new IllegalStateException("protocol not open");

    CompletableFuture<R> future = new CompletableFuture<>();
    context.execute(() -> {
      if (!session.isOpen()) {
        future.completeExceptionally(new IllegalStateException("session not open"));
        return;
      }

      QueryRequest request = QueryRequest.builder()
        .withSession(session.id())
        .withVersion(session.getVersion())
        .withQuery(query)
        .build();

      this.<R>submit(request).whenComplete((result, error) -> {
        if (error == null) {
          future.complete(result);
        } else {
          future.completeExceptionally(error);
        }
      });
    });
    return future;
  }

  /**
   * Recursively submits the command to the cluster.
   *
   * @param request The request to submit.
   * @return The completion future.
   */
  private <T> CompletableFuture<T> submit(QueryRequest request) {
    return submit(request, new CompletableFuture<>());
  }

  /**
   * Recursively submits the command to the cluster.
   *
   * @param request The request to submit.
   * @param future The future to complete once the command has succeeded.
   * @return The completion future.
   */
  private <T> CompletableFuture<T> submit(QueryRequest request, CompletableFuture<T> future) {
    Member member = selectMember();
    this.<T>submit(request, member).whenComplete((result, error) -> {
      if (error == null) {
        request.close();
        future.complete(result);
      } else if (error instanceof TimeoutException) {
        resetMember();
        submit(request, future);
      } else if (error instanceof NoLeaderException) {
        resetMember();
        submit(request, future);
      } else if (error instanceof TransportException) {
        LOGGER.warn("Failed to communicate with {}: {}", member, error);
        resetMember();
        submit(request, future);
      } else if (error instanceof UnknownSessionException) {
        LOGGER.warn("Lost session: {}", session.id());

        session.expire();

        register().thenRun(() -> {
          submit(QueryRequest.builder(request)
            .withSession(session.id())
            .build(), future);
        });
      } else {
        request.close();
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  /**
   * Attempts to submit the request to the given member.
   *
   * @param request The request to submit.
   * @param member The member to which to submit the request.
   * @return A completable future to be completed with the result.
   */
  @SuppressWarnings("unchecked")
  private <T> CompletableFuture<T> submit(QueryRequest request, Member member) {
    CompletableFuture<T> future = new CompletableFuture<>();
    ScheduledFuture<?> timeoutFuture = context.schedule(() -> future.completeExceptionally(new TimeoutException("request timed out")), REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);

    LOGGER.debug("Submitting {} to {}", request, member);
    getConnection(member).thenAccept(connection -> {
      connection.<QueryRequest, QueryResponse>send(request).whenComplete((response, error) -> {
        timeoutFuture.cancel(false);
        if (error == null) {
          if (response.status() == Response.Status.OK) {
            future.complete((T) response.result());
          } else {
            future.completeExceptionally(response.error().createException());
          }
        } else {
          future.completeExceptionally(error);
        }
      });
    });

    return future;
  }

  /**
   * Registers the client.
   */
  private CompletableFuture<Void> register() {
    context.checkThread();
    if (registerFuture == null) {
      registerFuture = register(100, new CompletableFuture<>()).whenComplete((result, error) -> {
        registerFuture = null;
      });
    }
    return registerFuture;
  }

  /**
   * Registers the client.
   */
  private CompletableFuture<Void> register(long interval, CompletableFuture<Void> future) {
    register(new ArrayList<>(members.members())).whenCompleteAsync((result, error) -> {
      context.checkThread();
      if (error == null) {
        future.complete(null);
      } else {
        long nextInterval = Math.min(interval * 2, 5000);
        registerTimer = context.schedule(() -> register(nextInterval, future), nextInterval, TimeUnit.MILLISECONDS);
      }
    }, context);
    return future;
  }

  /**
   * Registers the client.
   */
  protected CompletableFuture<Void> register(List<Member> members) {
    return register(members, new CompletableFuture<>()).thenAccept(response -> {
      setTerm(response.term());
      setLeader(response.leader());
      setMembers(response.members());
      session.open(response.session(), client.id());

      synchronized (this) {
        if (openFuture != null) {
          CompletableFuture<Void> future = openFuture;
          context.execute(() -> {
            open = true;
            future.complete(null);
          });
          openFuture = null;
        }
      }
    });
  }

  /**
   * Registers the client by contacting a random member.
   */
  protected CompletableFuture<RegisterResponse> register(List<Member> members, CompletableFuture<RegisterResponse> future) {
    if (members.isEmpty()) {
      future.completeExceptionally(new NoLeaderException("no leader found"));
      return future;
    }

    Member member = selectMember(members);

    RegisterRequest request = RegisterRequest.builder()
      .withConnection(client.id())
      .build();

    getConnection(member).thenAccept(connection -> {
      context.checkThread();
      logRequest(request, member);

      connection.<RegisterRequest, RegisterResponse>send(request).whenComplete((response, error) -> {
        context.checkThread();
        if (error == null) {
          logResponse(response, member);
          if (response.status() == Response.Status.OK) {
            future.complete(response);
            LOGGER.debug("Registered new session: {}", response.session());
          } else {
            LOGGER.debug("Session registration failed, retrying");
            setLeader(0);
            register(members, future);
          }
        } else {
          LOGGER.debug("Session registration failed, retrying");
          setLeader(0);
          register(members, future);
        }
      });
    });
    return future;
  }

  /**
   * Starts the keep alive timer.
   */
  private void startKeepAliveTimer() {
    LOGGER.debug("Starting keep alive timer");
    keepAliveTimer = context.scheduleAtFixedRate(this::keepAlive, 1, keepAliveInterval, TimeUnit.MILLISECONDS);
  }

  /**
   * Sends a keep alive request to a random member.
   */
  private void keepAlive() {
    if (keepAlive.compareAndSet(false, true) && session.isOpen()) {
      keepAlive(new ArrayList<>(members.members())).whenComplete((result, error) -> keepAlive.set(false));
    }
  }

  /**
   * Sends a keep alive request.
   */
  protected CompletableFuture<Void> keepAlive(List<Member> members) {
    return keepAlive(members, new CompletableFuture<>()).thenAccept(response -> {
      setTerm(response.term());
      setLeader(response.leader());
      setMembers(response.members());
    });
  }

  /**
   * Registers the client by contacting a random member.
   */
  protected CompletableFuture<KeepAliveResponse> keepAlive(List<Member> members, CompletableFuture<KeepAliveResponse> future) {
    if (members.isEmpty()) {
      future.completeExceptionally(RaftError.Type.NO_LEADER_ERROR.createException());
      keepAlive.set(false);
      return future;
    }

    Member member = selectMember(members);

    KeepAliveRequest request = KeepAliveRequest.builder()
      .withSession(session.id())
      .build();

    getConnection(member).thenAccept(connection -> {
      context.checkThread();
      logRequest(request, member);

      if (isOpen()) {
        connection.<KeepAliveRequest, KeepAliveResponse>send(request).whenComplete((response, error) -> {
          context.checkThread();
          if (isOpen()) {
            if (error == null) {
              logResponse(response, member);
              if (response.status() == Response.Status.OK) {
                future.complete(response);
              } else {
                future.completeExceptionally(response.error().createException());
              }
            } else {
              future.completeExceptionally(error);
            }
          }
          request.close();
        });
      }
    });
    return future;
  }

  /**
   * Resets the selected member.
   */
  private void resetMember() {
    remote = null;
  }

  /**
   * Selects a random member from the members list.
   */
  protected Member selectMember() {
    return remote != null ? remote : members.members().get(random.nextInt(members.members().size()));
  }

  /**
   * Selects a random member from the given members list.
   */
  protected Member selectMember(List<Member> members) {
    return members.remove(random.nextInt(members.size()));
  }

  /**
   * Cancels the register timer.
   */
  private void cancelRegisterTimer() {
    if (registerTimer != null) {
      LOGGER.debug("Cancelling register timer");
      registerTimer.cancel(false);
    }
    registerFuture = null;
  }

  /**
   * Cancels the keep alive timer.
   */
  private void cancelKeepAliveTimer() {
    if (keepAliveTimer != null) {
      LOGGER.debug("Cancelling keep alive timer");
      keepAliveTimer.cancel(false);
    }
  }

  @Override
  public CompletableFuture<Void> open() {
    openFuture = new CompletableFuture<>();
    context.execute(() -> register().thenRun(this::startKeepAliveTimer));
    return openFuture;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public CompletableFuture<Void> close() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    context.execute(() -> {
      cancelRegisterTimer();
      cancelKeepAliveTimer();
      if (session.isOpen()) {
        session.close();
      }

      open = false;
      transport.close().whenCompleteAsync((result, error) -> {
        context.close();
        if (error == null) {
          future.complete(null);
        } else {
          future.completeExceptionally(error);
        }
      }, context);
    });
    return future;
  }

  @Override
  public boolean isClosed() {
    return !open;
  }

}
