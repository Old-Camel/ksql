/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.ksql.schema.ksql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import io.confluent.ksql.schema.ksql.types.SqlArray;
import io.confluent.ksql.schema.ksql.types.SqlLambda;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import org.junit.Test;

public class SqlArgumentTest {

  @SuppressWarnings("UnstableApiUsage")
  @Test
  public void shouldImplementHashCodeAndEqualsProperly() {
    new EqualsTester()
        .addEqualityGroup(SqlArgument.of(SqlArray.of(SqlTypes.STRING)), SqlArgument.of(SqlArray.of(SqlTypes.STRING)))
        .addEqualityGroup(
            SqlArgument.of(SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER)),
            SqlArgument.of(SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER)))
        .addEqualityGroup(SqlArgument.of(null, null), SqlArgument.of(null, null))
        .testEquals();
  }

  @Test
  public void shouldReturnNullTypeIfBothLambdaAndTypeNotPresent() {
    final SqlArgument argument = SqlArgument.of(null, null);
    assertThat("null type", argument.getSqlTypeOrThrow() == null);
  }

  @Test
  public void shouldReturnTypeIfPresent() {
    final SqlArgument argument = SqlArgument.of(SqlTypes.STRING);
    assertThat("string type", argument.getSqlTypeOrThrow() == SqlTypes.STRING);
  }

  @Test
  public void shouldReturnLambdaIfPresent() {
    final SqlArgument argument = SqlArgument.of(SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER));
    assertThat("lambda", argument.getSqlLambdaOrThrow()
        .equals(SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER)));
  }

  @Test
  public void shouldThrowIfAssigningTypeAndLambdaToSqlArgument() {
    final Exception e = assertThrows(
        RuntimeException.class,
        () -> SqlArgument.of(SqlTypes.STRING, (SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER)))
    );
    assertThat(e.getMessage(), containsString(
        "A function argument was assigned to be both a type and a lambda"));
  }

  @Test
  public void shouldThrowWhenLambdaPresentWhenGettingType() {
    final SqlArgument argument = SqlArgument.of(null, (SqlLambda.of(ImmutableList.of(SqlTypes.STRING), SqlTypes.INTEGER)));

    final Exception e = assertThrows(
        RuntimeException.class,
        argument::getSqlTypeOrThrow
    );
    assertThat(e.getMessage(), containsString("Was expecting type as a function argument"));
  }

  @Test
  public void shouldThrowWhenTypePresentWhenGettingLambda() {
    final SqlArgument argument = SqlArgument.of(SqlTypes.STRING, null);
    final Exception e = assertThrows(
        RuntimeException.class,
        argument::getSqlLambdaOrThrow
    );

    assertThat(e.getMessage(), containsString("Was expecting lambda as a function argument"));
  }

  @Test
  public void shouldThrowWhenLambdaNotPresentGettingLambda() {
    final SqlArgument argument = SqlArgument.of(null, null);
    final Exception e = assertThrows(
        RuntimeException.class,
        argument::getSqlLambdaOrThrow
    );

    assertThat(e.getMessage(), containsString("Was expecting lambda as a function argument"));
  }
}