/*
 * Copyright 2017-present Open Networking Laboratory
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
package io.atomix.protocols.raft;

import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.cluster.RaftClusterEvent;
import io.atomix.protocols.raft.cluster.RaftMember;
import io.atomix.protocols.raft.cluster.impl.DefaultRaftMember;
import io.atomix.protocols.raft.event.EventType;
import io.atomix.protocols.raft.operation.OperationId;
import io.atomix.protocols.raft.operation.OperationType;
import io.atomix.protocols.raft.operation.RaftOperation;
import io.atomix.protocols.raft.operation.impl.DefaultOperationId;
import io.atomix.protocols.raft.protocol.TestRaftProtocolFactory;
import io.atomix.protocols.raft.proxy.RaftProxy;
import io.atomix.protocols.raft.service.AbstractRaftService;
import io.atomix.protocols.raft.service.Commit;
import io.atomix.protocols.raft.service.RaftServiceExecutor;
import io.atomix.protocols.raft.service.ServiceType;
import io.atomix.protocols.raft.session.RaftSession;
import io.atomix.protocols.raft.session.RaftSessionMetadata;
import io.atomix.protocols.raft.storage.RaftStorage;
import io.atomix.protocols.raft.storage.log.entry.CloseSessionEntry;
import io.atomix.protocols.raft.storage.log.entry.CommandEntry;
import io.atomix.protocols.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.protocols.raft.storage.log.entry.InitializeEntry;
import io.atomix.protocols.raft.storage.log.entry.KeepAliveEntry;
import io.atomix.protocols.raft.storage.log.entry.MetadataEntry;
import io.atomix.protocols.raft.storage.log.entry.OpenSessionEntry;
import io.atomix.protocols.raft.storage.log.entry.QueryEntry;
import io.atomix.protocols.raft.storage.snapshot.SnapshotReader;
import io.atomix.protocols.raft.storage.snapshot.SnapshotWriter;
import io.atomix.protocols.raft.storage.system.Configuration;
import io.atomix.serializer.Serializer;
import io.atomix.serializer.kryo.KryoNamespace;
import io.atomix.storage.StorageLevel;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Raft test.
 */
public class RaftTest extends ConcurrentTestCase {
  private static final Serializer storageSerializer = Serializer.using(KryoNamespace.newBuilder()
      .register(CloseSessionEntry.class)
      .register(CommandEntry.class)
      .register(ConfigurationEntry.class)
      .register(InitializeEntry.class)
      .register(KeepAliveEntry.class)
      .register(MetadataEntry.class)
      .register(OpenSessionEntry.class)
      .register(QueryEntry.class)
      .register(RaftOperation.class)
      .register(DefaultOperationId.class)
      .register(OperationType.class)
      .register(ReadConsistency.class)
      .register(ArrayList.class)
      .register(HashSet.class)
      .register(DefaultRaftMember.class)
      .register(MemberId.class)
      .register(RaftMember.Type.class)
      .register(Instant.class)
      .register(Configuration.class)
      .register(byte[].class)
      .register(long[].class)
      .build());

  private static final Serializer clientSerializer = Serializer.using(KryoNamespace.DEFAULT);

  protected volatile int nextId;
  protected volatile List<RaftMember> members;
  protected volatile List<RaftClient> clients = new ArrayList<>();
  protected volatile List<RaftServer> servers = new ArrayList<>();
  protected volatile TestRaftProtocolFactory protocolFactory;

  /**
   * Tests getting session metadata.
   */
  @Test
  public void testSessionMetadata() throws Throwable {
    createServers(3);
    RaftClient client = createClient();
    createSession(client).invoke(WRITE).join();
    createSession(client).invoke(WRITE).join();
    assertNotNull(client.metadata().getLeader());
    assertNotNull(client.metadata().getServers());
    Set<RaftSessionMetadata> typeSessions = client.metadata().getSessions("test").join();
    assertEquals(2, typeSessions.size());
    typeSessions = client.metadata().getSessions(ServiceType.from("test")).join();
    assertEquals(2, typeSessions.size());
    Set<RaftSessionMetadata> serviceSessions = client.metadata().getSessions("test", "test").join();
    assertEquals(2, serviceSessions.size());
    serviceSessions = client.metadata().getSessions(ServiceType.from("test"), "test").join();
    assertEquals(2, serviceSessions.size());
  }

  /**
   * Tests starting several members individually.
   */
  @Test
  public void testSingleMemberStart() throws Throwable {
    RaftServer server = createServers(1).get(0);
    server.bootstrap().thenRun(this::resume);
    await(5000);
    RaftServer joiner1 = createServer(nextMember(RaftMember.Type.ACTIVE));
    joiner1.join(server.cluster().getMember().memberId()).thenRun(this::resume);
    await(5000);
    RaftServer joiner2 = createServer(nextMember(RaftMember.Type.ACTIVE));
    joiner2.join(server.cluster().getMember().memberId()).thenRun(this::resume);
    await(5000);
  }

  /**
   * Tests joining a server after many entries have been committed.
   */
  @Test
  public void testActiveJoinLate() throws Throwable {
    testServerJoinLate(RaftMember.Type.ACTIVE, RaftServer.Role.FOLLOWER);
  }

  /**
   * Tests joining a server after many entries have been committed.
   */
  @Test
  public void testPassiveJoinLate() throws Throwable {
    testServerJoinLate(RaftMember.Type.PASSIVE, RaftServer.Role.PASSIVE);
  }

  /**
   * Tests joining a server after many entries have been committed.
   */
  @Test
  public void testReserveJoinLate() throws Throwable {
    testServerJoinLate(RaftMember.Type.RESERVE, RaftServer.Role.RESERVE);
  }

  /**
   * Tests joining a server after many entries have been committed.
   */
  private void testServerJoinLate(RaftMember.Type type, RaftServer.Role role) throws Throwable {
    createServers(3);
    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    submit(session, 0, 100);
    await(10000);
    RaftServer joiner = createServer(nextMember(type));
    joiner.addRoleChangeListener(s -> {
      if (s == role)
        resume();
    });
    joiner.join(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(10000, 2);
    submit(session, 0, 10);
    await(10000);
    Thread.sleep(5000);
  }

  /**
   * Submits a bunch of commands recursively.
   */
  private void submit(RaftProxy session, int count, int total) {
    if (count < total) {
      session.invoke(WRITE).whenComplete((result, error) -> {
        threadAssertNull(error);
        submit(session, count + 1, total);
      });
    } else {
      resume();
    }
  }

  /**
   * Tests transferring leadership.
   */
  @Test
  public void testTransferLeadership() throws Throwable {
    List<RaftServer> servers = createServers(3);
    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    submit(session, 0, 1000);
    RaftServer follower = servers.stream()
        .filter(RaftServer::isFollower)
        .findFirst()
        .get();
    follower.promote().thenRun(this::resume);
    await(10000, 2);
    assertTrue(follower.isLeader());
  }

  /**
   * Tests joining a server to an existing cluster.
   */
  @Test
  public void testCrashRecover() throws Throwable {
    List<RaftServer> servers = createServers(3);
    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    submit(session, 0, 100);
    await(30000);
    Thread.sleep(15000);
    servers.get(0).shutdown().get(10, TimeUnit.SECONDS);
    RaftServer server = createServer(members.get(0));
    server.join(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(30000);
    submit(session, 0, 100);
    await(30000);
  }

  /**
   * Tests leaving a sever from a cluster.
   */
  @Test
  public void testServerLeave() throws Throwable {
    List<RaftServer> servers = createServers(3);
    RaftServer server = servers.get(0);
    server.leave().thenRun(this::resume);
    await(30000);
  }

  /**
   * Tests leaving the leader from a cluster.
   */
  @Test
  public void testLeaderLeave() throws Throwable {
    List<RaftServer> servers = createServers(3);
    RaftServer server = servers.stream().filter(s -> s.getRole() == RaftServer.Role.LEADER).findFirst().get();
    server.leave().thenRun(this::resume);
    await(30000);
  }

  /**
   * Tests keeping a client session alive.
   */
  @Test
  public void testClientKeepAlive() throws Throwable {
    createServers(3);
    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    Thread.sleep(Duration.ofSeconds(10).toMillis());
    threadAssertTrue(session.getState() == RaftProxy.State.CONNECTED);
  }

  /**
   * Tests an active member joining the cluster.
   */
  @Test
  public void testActiveJoin() throws Throwable {
    testServerJoin(RaftMember.Type.ACTIVE);
  }

  /**
   * Tests a passive member joining the cluster.
   */
  @Test
  public void testPassiveJoin() throws Throwable {
    testServerJoin(RaftMember.Type.PASSIVE);
  }

  /**
   * Tests a reserve member joining the cluster.
   */
  @Test
  public void testReserveJoin() throws Throwable {
    testServerJoin(RaftMember.Type.RESERVE);
  }

  /**
   * Tests a server joining the cluster.
   */
  private void testServerJoin(RaftMember.Type type) throws Throwable {
    createServers(3);
    RaftServer server = createServer(nextMember(type));
    server.join(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(10000);
  }

  /**
   * Tests joining and leaving the cluster, resizing the quorum.
   */
  @Test
  public void testResize() throws Throwable {
    RaftServer server = createServers(1).get(0);
    RaftServer joiner = createServer(nextMember(RaftMember.Type.ACTIVE));
    joiner.join(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(10000);
    server.leave().thenRun(this::resume);
    await(10000);
    joiner.leave().thenRun(this::resume);
  }

  /**
   * Tests an active member join event.
   */
  @Test
  public void testActiveJoinEvent() throws Throwable {
    testJoinEvent(RaftMember.Type.ACTIVE);
  }

  /**
   * Tests a passive member join event.
   */
  @Test
  public void testPassiveJoinEvent() throws Throwable {
    testJoinEvent(RaftMember.Type.PASSIVE);
  }

  /**
   * Tests a reserve member join event.
   */
  @Test
  public void testReserveJoinEvent() throws Throwable {
    testJoinEvent(RaftMember.Type.RESERVE);
  }

  /**
   * Tests a member join event.
   */
  private void testJoinEvent(RaftMember.Type type) throws Throwable {
    List<RaftServer> servers = createServers(3);

    RaftMember member = nextMember(type);

    RaftServer server = servers.get(0);
    server.cluster().addListener(event -> {
      if (event.type() == RaftClusterEvent.Type.JOIN) {
        threadAssertEquals(event.subject().memberId(), member.memberId());
        threadAssertEquals(event.subject().getType(), type);
        resume();
      }
    });

    RaftServer joiner = createServer(member);
    joiner.join(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(10000, 2);
  }

  /**
   * Tests demoting the leader.
   */
  @Test
  public void testDemoteLeader() throws Throwable {
    List<RaftServer> servers = createServers(3);

    RaftServer leader = servers.stream()
        .filter(s -> s.cluster().getMember().equals(s.cluster().getLeader()))
        .findFirst()
        .get();

    RaftServer follower = servers.stream()
        .filter(s -> !s.cluster().getMember().equals(s.cluster().getLeader()))
        .findFirst()
        .get();

    follower.cluster().getMember(leader.cluster().getMember().memberId()).addTypeChangeListener(t -> {
      threadAssertEquals(t, RaftMember.Type.PASSIVE);
      resume();
    });
    leader.cluster().getMember().demote(RaftMember.Type.PASSIVE).thenRun(this::resume);
    await(10000, 2);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testOneNodeSubmitCommand() throws Throwable {
    testSubmitCommand(1);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testTwoNodeSubmitCommand() throws Throwable {
    testSubmitCommand(2);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testThreeNodeSubmitCommand() throws Throwable {
    testSubmitCommand(3);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testFourNodeSubmitCommand() throws Throwable {
    testSubmitCommand(4);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testFiveNodeSubmitCommand() throws Throwable {
    testSubmitCommand(5);
  }

  /**
   * Tests submitting a command with a configured consistency level.
   */
  private void testSubmitCommand(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.invoke(WRITE).thenRun(this::resume);

    await(30000);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testTwoOfThreeNodeSubmitCommand() throws Throwable {
    testSubmitCommand(2, 3);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testThreeOfFourNodeSubmitCommand() throws Throwable {
    testSubmitCommand(3, 4);
  }

  /**
   * Tests submitting a command.
   */
  @Test
  public void testThreeOfFiveNodeSubmitCommand() throws Throwable {
    testSubmitCommand(3, 5);
  }

  /**
   * Tests submitting a command to a partial cluster.
   */
  private void testSubmitCommand(int live, int total) throws Throwable {
    createServers(live, total);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.invoke(WRITE).thenRun(this::resume);

    await(30000);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testOneNodeSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(1, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testOneNodeSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(1, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testOneNodeSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(1, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testTwoNodeSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(2, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testTwoNodeSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(2, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testTwoNodeSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(2, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testThreeNodeSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(3, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testThreeNodeSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(3, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testThreeNodeSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(3, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFourNodeSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(4, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFourNodeSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(4, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFourNodeSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(4, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFiveNodeSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(5, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFiveNodeSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(5, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests submitting a query.
   */
  @Test
  public void testFiveNodeSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(5, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests submitting a query with a configured consistency level.
   */
  private void testSubmitQuery(int nodes, ReadConsistency consistency) throws Throwable {
    createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client, consistency);
    session.invoke(READ).thenRun(this::resume);

    await(30000);
  }

  /**
   * Tests submitting a sequential event.
   */
  @Test
  public void testOneNodeSequentialEvent() throws Throwable {
    testSequentialEvent(1);
  }

  /**
   * Tests submitting a sequential event.
   */
  @Test
  public void testTwoNodeSequentialEvent() throws Throwable {
    testSequentialEvent(2);
  }

  /**
   * Tests submitting a sequential event.
   */
  @Test
  public void testThreeNodeSequentialEvent() throws Throwable {
    testSequentialEvent(3);
  }

  /**
   * Tests submitting a sequential event.
   */
  @Test
  public void testFourNodeSequentialEvent() throws Throwable {
    testSequentialEvent(4);
  }

  /**
   * Tests submitting a sequential event.
   */
  @Test
  public void testFiveNodeSequentialEvent() throws Throwable {
    testSequentialEvent(5);
  }

  /**
   * Tests submitting a sequential event.
   */
  private void testSequentialEvent(int nodes) throws Throwable {
    createServers(nodes);

    AtomicLong count = new AtomicLong();
    AtomicLong index = new AtomicLong();

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.<Long>addEventListener(CHANGE_EVENT, clientSerializer::decode, event -> {
      threadAssertEquals(count.incrementAndGet(), 2L);
      threadAssertEquals(index.get(), event);
      resume();
    });

    session.<Boolean, Long>invoke(EVENT, clientSerializer::encode, true, clientSerializer::decode)
        .thenAccept(result -> {
          threadAssertNotNull(result);
          threadAssertEquals(count.incrementAndGet(), 1L);
          index.set(result);
          resume();
        });

    await(30000, 2);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  public void testOneNodeEvents() throws Throwable {
    testEvents(1);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  public void testTwoNodeEvents() throws Throwable {
    testEvents(2);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  public void testThreeNodeEvents() throws Throwable {
    testEvents(3);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  public void testFourNodeEvents() throws Throwable {
    testEvents(4);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  public void testFiveNodeEvents() throws Throwable {
    testEvents(5);
  }

  /**
   * Tests submitting sequential events to all sessions.
   */
  private void testEvents(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });
    createSession(createClient()).addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });
    createSession(createClient()).addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    session.invoke(EVENT, clientSerializer::encode, false).thenRun(this::resume);

    await(30000, 4);
  }

  /**
   * Tests that operations are properly sequenced on the client.
   */
  @Test
  public void testSequenceLinearizableOperations() throws Throwable {
    testSequenceOperations(5, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Tests that operations are properly sequenced on the client.
   */
  @Test
  public void testSequenceBoundedLinearizableOperations() throws Throwable {
    testSequenceOperations(5, ReadConsistency.LINEARIZABLE_LEASE);
  }

  /**
   * Tests that operations are properly sequenced on the client.
   */
  @Test
  public void testSequenceSequentialOperations() throws Throwable {
    testSequenceOperations(5, ReadConsistency.SEQUENTIAL);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testSequenceOperations(int nodes, ReadConsistency consistency) throws Throwable {
    createServers(nodes);

    AtomicInteger counter = new AtomicInteger();
    AtomicLong index = new AtomicLong();

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.<Long>addEventListener(CHANGE_EVENT, clientSerializer::decode, event -> {
      threadAssertEquals(counter.incrementAndGet(), 3);
      threadAssertTrue(event >= index.get());
      index.set(event);
      resume();
    });

    session.<Long>invoke(WRITE, clientSerializer::decode).thenAccept(result -> {
      threadAssertNotNull(result);
      threadAssertEquals(counter.incrementAndGet(), 1);
      threadAssertTrue(index.compareAndSet(0, result));
      resume();
    });

    session.<Boolean, Long>invoke(EVENT, clientSerializer::encode, true, clientSerializer::decode).thenAccept(result -> {
      threadAssertNotNull(result);
      threadAssertEquals(counter.incrementAndGet(), 2);
      threadAssertTrue(result > index.get());
      index.set(result);
      resume();
    });

    session.<Long>invoke(READ, clientSerializer::decode).thenAccept(result -> {
      threadAssertNotNull(result);
      threadAssertEquals(counter.incrementAndGet(), 4);
      long i = index.get();
      threadAssertTrue(result >= i);
      resume();
    });

    await(30000, 4);
  }

  /**
   * Tests blocking within an event thread.
   */
  @Test
  public void testBlockOnEvent() throws Throwable {
    createServers(3);

    AtomicLong index = new AtomicLong();

    RaftClient client = createClient();
    RaftProxy session = createSession(client);

    session.<Long>addEventListener(CHANGE_EVENT, clientSerializer::decode, event -> {
      threadAssertEquals(index.get(), event);
      try {
        threadAssertTrue(index.get() <= session.<Long>invoke(READ, clientSerializer::decode)
            .get(5, TimeUnit.SECONDS));
      } catch (InterruptedException | TimeoutException | ExecutionException e) {
        threadFail(e);
      }
      resume();
    });

    session.<Boolean, Long>invoke(EVENT, clientSerializer::encode, true, clientSerializer::decode)
        .thenAccept(result -> {
          threadAssertNotNull(result);
          index.compareAndSet(0, result);
          resume();
        });

    await(10000, 2);
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testFiveNodeManyEvents() throws Throwable {
    testManyEvents(5);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testManyEvents(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(message -> {
      threadAssertNotNull(message);
      resume();
    });

    for (int i = 0 ; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testThreeNodesManyEventsAfterLeaderShutdown() throws Throwable {
    testManyEventsAfterLeaderShutdown(3);
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testFiveNodesManyEventsAfterLeaderShutdown() throws Throwable {
    testManyEventsAfterLeaderShutdown(5);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testManyEventsAfterLeaderShutdown(int nodes) throws Throwable {
    List<RaftServer> servers = createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    for (int i = 0; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }

    RaftServer leader = servers.stream().filter(s -> s.getRole() == RaftServer.Role.LEADER).findFirst().get();
    leader.shutdown().get(10, TimeUnit.SECONDS);

    for (int i = 0; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  @Ignore // Ignored due to lack of timeouts/retries in test protocol
  public void testThreeNodesEventsAfterFollowerKill() throws Throwable {
    testEventsAfterFollowerKill(3);
  }

  /**
   * Tests submitting sequential events.
   */
  @Test
  @Ignore // Ignored due to lack of timeouts/retries in test protocol
  public void testFiveNodesEventsAfterFollowerKill() throws Throwable {
    testEventsAfterFollowerKill(5);
  }

  /**
   * Tests submitting a sequential event that publishes to all sessions.
   */
  private void testEventsAfterFollowerKill(int nodes) throws Throwable {
    List<RaftServer> servers = createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    for (int i = 0 ; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }

    session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

    RaftServer follower = servers.stream().filter(s -> s.getRole() == RaftServer.Role.FOLLOWER).findFirst().get();
    follower.shutdown().get(10, TimeUnit.SECONDS);

    await(30000, 2);

    for (int i = 0 ; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }
  }

  /**
   * Tests submitting events.
   */
  @Test
  @Ignore // Ignored due to lack of timeouts/retries in test protocol
  public void testFiveNodesEventsAfterLeaderKill() throws Throwable {
    testEventsAfterLeaderKill(5);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testEventsAfterLeaderKill(int nodes) throws Throwable {
    List<RaftServer> servers = createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    for (int i = 0 ; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }

    session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

    RaftServer leader = servers.stream().filter(s -> s.getRole() == RaftServer.Role.LEADER).findFirst().get();
    leader.shutdown().get(10, TimeUnit.SECONDS);

    await(30000);

    for (int i = 0 ; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, true).thenRun(this::resume);

      await(30000, 2);
    }
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testFiveNodeManySessionsManyEvents() throws Throwable {
    testManySessionsManyEvents(5);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testManySessionsManyEvents(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client = createClient();
    RaftProxy session = createSession(client);
    session.addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    createSession(createClient()).addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    createSession(createClient()).addEventListener(event -> {
      threadAssertNotNull(event);
      resume();
    });

    for (int i = 0; i < 10; i++) {
      session.invoke(EVENT, clientSerializer::encode, false).thenRun(this::resume);

      await(10000, 4);
    }
  }

  /**
   * Tests session expiring events.
   */
  @Test
  public void testOneNodeExpireEvent() throws Throwable {
    testSessionExpire(1);
  }

  /**
   * Tests session expiring events.
   */
  @Test
  public void testThreeNodeExpireEvent() throws Throwable {
    testSessionExpire(3);
  }

  /**
   * Tests session expiring events.
   */
  @Test
  public void testFiveNodeExpireEvent() throws Throwable {
    testSessionExpire(5);
  }

  /**
   * Tests a session expiring.
   */
  private void testSessionExpire(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client1 = createClient();
    RaftProxy session1 = createSession(client1);
    RaftClient client2 = createClient();
    createSession(client2);
    session1.addEventListener(EXPIRE_EVENT, this::resume);
    session1.invoke(EXPIRE).thenRun(this::resume);
    client2.close().thenRun(this::resume);
    await(Duration.ofSeconds(10).toMillis(), 3);
  }

  /**
   * Tests session close events.
   */
  @Test
  public void testOneNodeCloseEvent() throws Throwable {
    testSessionClose(1);
  }

  /**
   * Tests session close events.
   */
  @Test
  public void testThreeNodeCloseEvent() throws Throwable {
    testSessionClose(3);
  }

  /**
   * Tests session close events.
   */
  @Test
  public void testFiveNodeCloseEvent() throws Throwable {
    testSessionClose(5);
  }

  /**
   * Tests a session closing.
   */
  private void testSessionClose(int nodes) throws Throwable {
    createServers(nodes);

    RaftClient client1 = createClient();
    RaftProxy session1 = createSession(client1);
    RaftClient client2 = createClient();
    session1.invoke(CLOSE).thenRun(this::resume);
    await(Duration.ofSeconds(10).toMillis(), 1);
    session1.addEventListener(CLOSE_EVENT, this::resume);
    createSession(client2).close().thenRun(this::resume);
    await(Duration.ofSeconds(10).toMillis(), 2);
  }

  /**
   * Returns the next unique member identifier.
   *
   * @return The next unique member identifier.
   */
  private MemberId nextMemberId() {
    return MemberId.from(String.valueOf(++nextId));
  }

  /**
   * Returns the next server address.
   *
   * @param type The startup member type.
   * @return The next server address.
   */
  private RaftMember nextMember(RaftMember.Type type) {
    return new TestMember(nextMemberId(), type);
  }

  /**
   * Creates a set of Raft servers.
   */
  private List<RaftServer> createServers(int nodes) throws Throwable {
    List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < nodes; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    for (int i = 0; i < nodes; i++) {
      RaftServer server = createServer(members.get(i));
      server.bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
      servers.add(server);
    }

    await(30000 * nodes, nodes);

    return servers;
  }

  /**
   * Creates a set of Raft servers.
   */
  private List<RaftServer> createServers(int live, int total) throws Throwable {
    List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < total; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    for (int i = 0; i < live; i++) {
      RaftServer server = createServer(members.get(i));
      server.bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
      servers.add(server);
    }

    await(30000 * live, live);

    return servers;
  }

  /**
   * Creates a Raft server.
   */
  private RaftServer createServer(RaftMember member) {
    RaftServer.Builder builder = RaftServer.newBuilder(member.memberId())
        .withType(member.getType())
        .withProtocol(protocolFactory.newServerProtocol(member.memberId()))
        .withStorage(RaftStorage.newBuilder()
            .withStorageLevel(StorageLevel.DISK)
            .withDirectory(new File(String.format("target/test-logs/%s", member.memberId())))
            .withSerializer(storageSerializer)
            .withMaxSegmentSize(1024 * 10)
            .withMaxEntriesPerSegment(10)
            .build())
        .addService("test", TestStateMachine::new);

    RaftServer server = builder.build();
    servers.add(server);
    return server;
  }

  /**
   * Creates a Raft client.
   */
  private RaftClient createClient() throws Throwable {
    MemberId memberId = nextMemberId();
    RaftClient client = RaftClient.newBuilder()
        .withMemberId(memberId)
        .withProtocol(protocolFactory.newClientProtocol(memberId))
        .build();
    client.connect(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(this::resume);
    await(30000);
    clients.add(client);
    return client;
  }

  /**
   * Creates a test session.
   */
  private RaftProxy createSession(RaftClient client)throws Exception  {
    return createSession(client, ReadConsistency.LINEARIZABLE);
  }

  /**
   * Creates a test session.
   */
  private RaftProxy createSession(RaftClient client, ReadConsistency consistency) throws Exception {
    return client.newProxyBuilder()
        .withName("test")
        .withServiceType("test")
        .withReadConsistency(consistency)
        .build()
        .open()
        .get(5, TimeUnit.SECONDS);
  }

  @Before
  @After
  public void clearTests() throws Exception {
    clients.forEach(c -> {
      try {
        c.close().get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
      }
    });

    servers.forEach(s -> {
      try {
        if (s.isRunning()) {
          s.shutdown().get(10, TimeUnit.SECONDS);
        }
      } catch (Exception e) {
      }
    });

    Path directory = Paths.get("target/test-logs/");
    if (Files.exists(directory)) {
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }

    members = new ArrayList<>();
    nextId = 0;
    clients = new ArrayList<>();
    servers = new ArrayList<>();
    protocolFactory = new TestRaftProtocolFactory();
  }

  private static final OperationId WRITE = OperationId.command("write");
  private static final OperationId EVENT = OperationId.command("event");
  private static final OperationId EXPIRE = OperationId.command("expire");
  private static final OperationId CLOSE = OperationId.command("close");

  private static final OperationId READ = OperationId.query("read");

  private static final EventType CHANGE_EVENT = EventType.from("change");
  private static final EventType EXPIRE_EVENT = EventType.from("expire");
  private static final EventType CLOSE_EVENT = EventType.from("close");

  /**
   * Test state machine.
   */
  public static class TestStateMachine extends AbstractRaftService {
    private Commit<Void> expire;
    private Commit<Void> close;

    @Override
    protected void configure(RaftServiceExecutor executor) {
      executor.register(WRITE, this::write, clientSerializer::encode);
      executor.register(READ, this::read, clientSerializer::encode);
      executor.register(EVENT, clientSerializer::decode, this::event, clientSerializer::encode);
      executor.register(CLOSE, this::close);
      executor.register(EXPIRE, this::expire);
    }

    @Override
    public void onExpire(RaftSession session) {
      if (expire != null) {
        expire.session().publish(EXPIRE_EVENT);
      }
    }

    @Override
    public void onClose(RaftSession session) {
      if (close != null && !session.equals(close.session())) {
        close.session().publish(CLOSE_EVENT);
      }
    }

    @Override
    public void snapshot(SnapshotWriter writer) {
      writer.writeLong(10);
    }

    @Override
    public void install(SnapshotReader reader) {
      assertEquals(10, reader.readLong());
    }

    protected long write(Commit<Void> commit) {
      return commit.index();
    }

    protected long read(Commit<Void> commit) {
      return commit.index();
    }

    protected long event(Commit<Boolean> commit) {
      if (commit.value()) {
        commit.session().publish(CHANGE_EVENT, clientSerializer::encode, commit.index());
      } else {
        for (RaftSession session : getSessions()) {
          session.publish(CHANGE_EVENT, clientSerializer::encode, commit.index());
        }
      }
      return commit.index();
    }

    public void close(Commit<Void> commit) {
      this.close = commit;
    }

    public void expire(Commit<Void> commit) {
      this.expire = commit;
    }
  }

  /**
   * Test member.
   */
  public static class TestMember implements RaftMember {
    private final MemberId memberId;
    private final Type type;

    TestMember(MemberId memberId, Type type) {
      this.memberId = memberId;
      this.type = type;
    }

    @Override
    public MemberId memberId() {
      return memberId;
    }

    @Override
    public Type getType() {
      return type;
    }

    @Override
    public int hash() {
      return 0;
    }

    @Override
    public void addTypeChangeListener(Consumer<Type> listener) {

    }

    @Override
    public void removeTypeChangeListener(Consumer<Type> listener) {

    }

    @Override
    public Instant getLastUpdated() {
      return null;
    }

    @Override
    public CompletableFuture<Void> promote() {
      return null;
    }

    @Override
    public CompletableFuture<Void> promote(Type type) {
      return null;
    }

    @Override
    public CompletableFuture<Void> demote() {
      return null;
    }

    @Override
    public CompletableFuture<Void> demote(Type type) {
      return null;
    }

    @Override
    public CompletableFuture<Void> remove() {
      return null;
    }
  }

}
