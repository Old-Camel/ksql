/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.internal.QueryStateListener;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.query.KafkaStreamsBuilder;
import io.confluent.ksql.query.QueryError;
import io.confluent.ksql.query.QueryError.Type;
import io.confluent.ksql.query.QueryErrorClassifier;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.SystemColumns;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.util.KsqlConstants.KsqlQueryType;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KafkaStreams.State;
import org.apache.kafka.streams.Topology;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class QueryMetadataTest {

  private static long RETRY_BACKOFF_INITIAL_MS = 1;
  private static long RETRY_BACKOFF_MAX_MS = 10;
  private static final String QUERY_APPLICATION_ID = "Query1";
  private static final QueryId QUERY_ID = new QueryId("queryId");
  private static final LogicalSchema SOME_SCHEMA = LogicalSchema.builder()
      .keyColumn(SystemColumns.ROWKEY_NAME, SqlTypes.STRING)
      .valueColumn(ColumnName.of("f0"), SqlTypes.STRING)
      .build();

  private static final Set<SourceName> SOME_SOURCES = ImmutableSet.of(SourceName.of("s1"), SourceName.of("s2"));
  private static final Long closeTimeout = KsqlConfig.KSQL_SHUTDOWN_TIMEOUT_MS_DEFAULT;

  @Mock
  private KafkaStreamsBuilder kafkaStreamsBuilder;
  @Mock
  private Topology topoplogy;
  @Mock
  private KafkaStreams kafkaStreams;
  @Mock
  private QueryStateListener listener;
  @Mock
  private Consumer<QueryMetadata> closeCallback;
  @Mock
  private QueryErrorClassifier classifier;
  @Mock
  private QueryStateListener queryStateListener;
  @Mock
  private Ticker ticker;

  private QueryMetadata query;

  @Before
  public void setup() {
    when(kafkaStreamsBuilder.build(topoplogy, Collections.emptyMap())).thenReturn(kafkaStreams);
    when(classifier.classify(any())).thenReturn(Type.UNKNOWN);

    query = new QueryMetadata(
        "foo",
        SOME_SCHEMA,
        SOME_SOURCES,
        "bar",
        QUERY_APPLICATION_ID,
        topoplogy,
        kafkaStreamsBuilder,
        Collections.emptyMap(),
        Collections.emptyMap(),
        closeCallback,
        closeTimeout,
        QUERY_ID,
        classifier,
        10,
        0L,
        0L
    ){
    };
    query.initialize();
  }

  @Test
  public void shouldSetInitialStateWhenListenerAdd() {
    // Given:
    when(kafkaStreams.state()).thenReturn(State.CREATED);

    // When:
    query.setQueryStateListener(listener);

    // Then:
    verify(listener).onChange(State.CREATED, State.CREATED);
  }

  @Test
  public void shouldGetUptimeFromStateListener() {
    // Given:
    when(kafkaStreams.state()).thenReturn(State.RUNNING);
    when(listener.uptime()).thenReturn(5L);

    // When:
    query.setQueryStateListener(listener);

    // Then:
    assertThat(query.uptime(), is(5L));
  }

  @Test
  public void shouldReturnZeroUptimeIfNoStateListenerSet() {
    // When/Then:
    assertThat(query.uptime(), is(0L));
  }

  @Test
  public void shouldConnectAnyListenerToStreamAppOnStart() {
    // Given:
    query.setQueryStateListener(listener);

    // When:
    query.start();

    // Then:
    verify(kafkaStreams).setStateListener(listener);
  }

  @Test
  public void shouldCloseAnyListenerOnClose() {
    // Given:
    query.setQueryStateListener(listener);

    // When:
    query.close();

    // Then:
    verify(listener).close();
  }

  @Test
  public void shouldReturnStreamState() {
    // Given:
    when(kafkaStreams.state()).thenReturn(State.PENDING_SHUTDOWN);

    // When:
    final String state = query.getState().toString();

    // Then:
    assertThat(state, is("PENDING_SHUTDOWN"));
  }

  @Test
  public void shouldCloseKStreamsAppOnCloseThenCloseCallback() {
    // When:
    query.close();

    // Then:
    final InOrder inOrder = inOrder(kafkaStreams, closeCallback);
    inOrder.verify(kafkaStreams).close(Duration.ofMillis(closeTimeout));
    inOrder.verify(closeCallback).accept(query);
  }

  @Test
  public void shouldNotCallCloseCallbackOnStop() {
    // When:
    query.stop();

    // Then:
    verifyNoMoreInteractions(closeCallback);
  }

  @Test
  public void shouldCallKafkaStreamsCloseOnStop() {
    // When:
    query.stop();

    // Then:
    verify(kafkaStreams).close(Duration.ofMillis(closeTimeout));
  }

  @Test
  public void shouldCleanUpKStreamsAppAfterCloseOnClose() {
    // When:
    query.close();

    // Then:
    final InOrder inOrder = inOrder(kafkaStreams);
    inOrder.verify(kafkaStreams).close(Duration.ofMillis(closeTimeout));
    inOrder.verify(kafkaStreams).cleanUp();
  }

  @Test
  public void shouldNotCleanUpKStreamsAppOnStop() {
    // When:
    query.stop();

    // Then:
    verify(kafkaStreams, never()).cleanUp();
  }

  @Test
  public void shouldReturnSources() {
    assertThat(query.getSourceNames(), is(SOME_SOURCES));
  }

  @Test
  public void shouldReturnSchema() {
    assertThat(query.getLogicalSchema(), is(SOME_SCHEMA));
  }

  @Test
  public void shouldNotifyQueryStateListenerOnError() {
    // Given:
    query.setQueryStateListener(queryStateListener);
    when(classifier.classify(any())).thenReturn(Type.USER);

    // When:
    query.uncaughtHandler(new RuntimeException("oops"));

    // Then:
    verify(queryStateListener).onError(argThat(q -> q.getType().equals(Type.USER)));
  }

  @Test
  public void shouldNotifyQueryStateListenerOnErrorEvenIfClassifierFails() {
    // Given:
    query.setQueryStateListener(queryStateListener);
    final RuntimeException thrown = new RuntimeException("bar");
    when(classifier.classify(any())).thenThrow(thrown);

    // When:
    query.uncaughtHandler(new RuntimeException("foo"));


    // Then:
    verify(queryStateListener).onError(argThat(q -> q.getType().equals(Type.UNKNOWN)));
  }

  @Test
  public void shouldReturnPersistentQueryTypeByDefault() {
    assertThat(query.getQueryType(), is(KsqlQueryType.PERSISTENT));
  }

  @Test
  public void shouldRetryEventStartWithInitialValues() {
    // Given:
    final long now = 20;
    when(ticker.read()).thenReturn(now);

    // When:
    final QueryMetadata.RetryEvent retryEvent = new QueryMetadata.RetryEvent(
            QUERY_ID,
            RETRY_BACKOFF_INITIAL_MS,
            RETRY_BACKOFF_MAX_MS,
            ticker
    );

    // Then:
    assertThat(retryEvent.getNumRetries(), is(0));
    assertThat(retryEvent.nextRestartTimeMs(), is(now + RETRY_BACKOFF_INITIAL_MS));
  }

  @Test
  public void shouldRetryEventRestartAndIncrementBackoffTime() {
    // Given:
    final long now = 20;
    when(ticker.read()).thenReturn(now);

    // When:
    final QueryMetadata.RetryEvent retryEvent = new QueryMetadata.RetryEvent(
            QUERY_ID,
            RETRY_BACKOFF_INITIAL_MS,
            RETRY_BACKOFF_MAX_MS,
            ticker
    );

    retryEvent.backOff();

    // Then:
    assertThat(retryEvent.getNumRetries(), is(1));
    assertThat(retryEvent.nextRestartTimeMs(), is(now + RETRY_BACKOFF_INITIAL_MS * 2));
  }

  @Test
  public void shouldRetryEventRestartAndNotExceedBackoffMaxTime() {
    // Given:
    final long now = 20;
    when(ticker.read()).thenReturn(now);

    // When:
    final QueryMetadata.RetryEvent retryEvent = new QueryMetadata.RetryEvent(
            QUERY_ID,
            RETRY_BACKOFF_INITIAL_MS,
            RETRY_BACKOFF_MAX_MS,
            ticker
    );
    retryEvent.backOff();
    retryEvent.backOff();

    // Then:
    assertThat(retryEvent.getNumRetries(), is(2));
    assertThat(retryEvent.nextRestartTimeMs(), lessThan(now + RETRY_BACKOFF_MAX_MS));
  }

  @Test
  public void shouldEvictBasedOnTime() {
    // Given:
    final QueryMetadata.TimeBoundedQueue queue = new QueryMetadata.TimeBoundedQueue(Duration.ZERO, 1);
    queue.add(new QueryError(System.currentTimeMillis(), "test", Type.SYSTEM));

    //Then:
    assertThat(queue.toImmutableList().size(), is(0));
  }

}
