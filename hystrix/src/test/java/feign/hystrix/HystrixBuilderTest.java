/*
 * Copyright 2012-2023 The Feign Authors
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
package feign.hystrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import feign.FeignException;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Target;
import feign.Target.HardCodedTarget;
import feign.gson.GsonDecoder;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.observers.TestSubscriber;

public class HystrixBuilderTest {
  public final MockWebServer server = new MockWebServer();

  @Test
  void defaultMethodReturningHystrixCommand() {
    server.enqueue(new MockResponse().setBody("\"foo\""));

    final TestInterface api = target();

    final HystrixCommand<String> command = api.defaultMethodReturningCommand();

    assertThat(command).isNotNull();
    assertThat(command.execute()).isEqualTo("foo");
  }

  @Test
  void hystrixCommand() {
    server.enqueue(new MockResponse().setBody("\"foo\""));

    final TestInterface api = target();

    final HystrixCommand<String> command = api.command();

    assertThat(command).isNotNull();
    assertThat(command.execute()).isEqualTo("foo");
  }

  @Test
  void hystrixCommandFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final HystrixCommand<String> command = api.command();

    assertThat(command).isNotNull();
    assertThat(command.execute()).isEqualTo("fallback");
  }

  @Test
  void hystrixCommandInt() {
    server.enqueue(new MockResponse().setBody("1"));

    final TestInterface api = target();

    final HystrixCommand<Integer> command = api.intCommand();

    assertThat(command).isNotNull();
    assertThat(command.execute()).isEqualTo(Integer.valueOf(1));
  }

  @Test
  void hystrixCommandIntFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final HystrixCommand<Integer> command = api.intCommand();

    assertThat(command).isNotNull();
    assertThat(command.execute()).isEqualTo(Integer.valueOf(0));
  }

  @Test
  void hystrixCommandList() {
    server.enqueue(new MockResponse().setBody("[\"foo\",\"bar\"]"));

    final TestInterface api = target();

    final HystrixCommand<List<String>> command = api.listCommand();

    assertThat(command).isNotNull();
    assertThat(command.execute()).containsExactly("foo", "bar");
  }

  @Test
  void hystrixCommandListFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final HystrixCommand<List<String>> command = api.listCommand();

    assertThat(command).isNotNull();
    assertThat(command.execute()).containsExactly("fallback");
  }

  // When dealing with fallbacks, it is less tedious to keep interfaces small.
  interface GitHub {
    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    List<String> contributors(@Param("owner") String owner, @Param("repo") String repo);
  }

  interface GitHubHystrix {
    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    HystrixCommand<List<String>> contributorsHystrixCommand(@Param("owner") String owner,
                                                            @Param("repo") String repo);
  }

  @Test
  void fallbacksApplyOnError() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final GitHub fallback = (owner, repo) -> {
      if (owner.equals("Netflix") && repo.equals("feign")) {
        return Arrays.asList("stuarthendren"); // inspired this approach!
      } else {
        return Collections.emptyList();
      }
    };

    final GitHub api = target(GitHub.class, "http://localhost:" + server.getPort(), fallback);

    final List<String> result = api.contributors("Netflix", "feign");

    assertThat(result).containsExactly("stuarthendren");
  }

  @Test
  void errorInFallbackHasExpectedBehavior() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final GitHub fallback = (owner, repo) -> {
      throw new RuntimeException("oops");
    };

    final GitHub api = target(GitHub.class, "http://localhost:" + server.getPort(), fallback);

    HystrixRuntimeException exception =
        assertThrows(HystrixRuntimeException.class, () -> api.contributors("Netflix", "feign"));
    assertThat(exception).hasCauseInstanceOf(FeignException.class)
        .hasMessage("GitHub#contributors(String,String) failed and fallback failed.");
  }

  protected <E> E target(Class<E> api, String url) {
    return HystrixFeign.builder()
        .target(api, url);
  }

  protected <E> E target(Target<E> api) {
    return HystrixFeign.builder()
        .target(api);
  }

  protected <E> E target(Class<E> api, String url, E fallback) {
    return HystrixFeign.builder()
        .target(api, url, fallback);
  }

  @Test
  void hystrixRuntimeExceptionPropagatesOnException() {

    server.enqueue(new MockResponse().setResponseCode(500));

    final GitHub api = target(GitHub.class, "http://localhost:" + server.getPort());

    HystrixRuntimeException exception =
        assertThrows(HystrixRuntimeException.class, () -> api.contributors("Netflix", "feign"));
    assertThat(exception).hasCauseInstanceOf(FeignException.class)
        .hasMessage("GitHub#contributors(String,String) failed and no fallback available.");
  }

  @Test
  void rxObservable() {
    server.enqueue(new MockResponse().setBody("\"foo\""));

    final TestInterface api = target();

    final Observable<String> observable = api.observable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo("foo");
  }

  @Test
  void rxObservableFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Observable<String> observable = api.observable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo("fallback");
  }

  @Test
  void rxObservableInt() {
    server.enqueue(new MockResponse().setBody("1"));

    final TestInterface api = target();

    final Observable<Integer> observable = api.intObservable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<Integer> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo(Integer.valueOf(1));
  }

  @Test
  void rxObservableIntFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Observable<Integer> observable = api.intObservable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<Integer> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo(Integer.valueOf(0));
  }

  @Test
  void rxObservableList() {
    server.enqueue(new MockResponse().setBody("[\"foo\",\"bar\"]"));

    final TestInterface api = target();

    final Observable<List<String>> observable = api.listObservable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<List<String>> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).containsExactly("foo", "bar");
  }

  @Test
  void rxObservableListFall() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Observable<List<String>> observable = api.listObservable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<List<String>> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).containsExactly("fallback");
  }

  @Test
  void rxObservableListFall_noFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = targetWithoutFallback();

    final Observable<List<String>> observable = api.listObservable();

    assertThat(observable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<List<String>> testSubscriber = new TestSubscriber<>();
    observable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();

    assertThat(testSubscriber.getOnNextEvents()).isEmpty();
    assertThat(testSubscriber.getOnErrorEvents().get(0))
        .isInstanceOf(HystrixRuntimeException.class)
        .hasMessage("TestInterface#listObservable() failed and no fallback available.");
  }

  @Test
  void rxSingle() {
    server.enqueue(new MockResponse().setBody("\"foo\""));

    final TestInterface api = target();

    final Single<String> single = api.single();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo("foo");
  }

  @Test
  void rxSingleFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Single<String> single = api.single();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo("fallback");
  }

  @Test
  void rxSingleInt() {
    server.enqueue(new MockResponse().setBody("1"));

    final TestInterface api = target();

    final Single<Integer> single = api.intSingle();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<Integer> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo(Integer.valueOf(1));
  }

  @Test
  void rxSingleIntFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Single<Integer> single = api.intSingle();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<Integer> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).isEqualTo(Integer.valueOf(0));
  }

  @Test
  void rxSingleList() {
    server.enqueue(new MockResponse().setBody("[\"foo\",\"bar\"]"));

    final TestInterface api = target();

    final Single<List<String>> single = api.listSingle();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<List<String>> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).containsExactly("foo", "bar");
  }

  @Test
  void rxSingleListFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Single<List<String>> single = api.listSingle();

    assertThat(single).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<List<String>> testSubscriber = new TestSubscriber<>();
    single.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();
    assertThat(testSubscriber.getOnNextEvents().get(0)).containsExactly("fallback");
  }

  @Test
  void completableFutureEmptyBody()
      throws InterruptedException, ExecutionException, TimeoutException {
    server.enqueue(new MockResponse());

    final TestInterface api = target();

    final CompletableFuture<String> completable = api.completableFuture();

    assertThat(completable).isNotNull();

    completable.get(5, TimeUnit.SECONDS);
  }

  @Test
  void completableFutureWithBody()
      throws InterruptedException, ExecutionException, TimeoutException {
    server.enqueue(new MockResponse().setBody("foo"));

    final TestInterface api = target();

    final CompletableFuture<String> completable = api.completableFuture();

    assertThat(completable).isNotNull();

    assertThat(completable.get(5, TimeUnit.SECONDS)).isEqualTo("foo");
  }

  @Test
  void completableFutureFailWithoutFallback() throws TimeoutException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target(TestInterface.class, "http://localhost:" + server.getPort());

    final CompletableFuture<String> completable = api.completableFuture();

    assertThat(completable).isNotNull();

    try {
      completable.get(5, TimeUnit.SECONDS);
    } catch (final ExecutionException e) {
      assertThat(e).hasCauseInstanceOf(HystrixRuntimeException.class);
    }
  }

  @Test
  void completableFutureFallback()
      throws InterruptedException, ExecutionException, TimeoutException {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final CompletableFuture<String> completable = api.completableFuture();

    assertThat(completable).isNotNull();

    assertThat(completable.get(5, TimeUnit.SECONDS)).isEqualTo("fallback");
  }

  @Test
  void rxCompletableEmptyBody() {
    server.enqueue(new MockResponse());

    final TestInterface api = target();

    final Completable completable = api.completable();

    assertThat(completable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    completable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();

    testSubscriber.assertCompleted();
    testSubscriber.assertNoErrors();
  }

  @Test
  void rxCompletableWithBody() {
    server.enqueue(new MockResponse().setBody("foo"));

    final TestInterface api = target();

    final Completable completable = api.completable();

    assertThat(completable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    completable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();

    testSubscriber.assertCompleted();
    testSubscriber.assertNoErrors();
  }

  @Test
  void rxCompletableFailWithoutFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target(TestInterface.class, "http://localhost:" + server.getPort());

    final Completable completable = api.completable();

    assertThat(completable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    completable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();

    testSubscriber.assertError(HystrixRuntimeException.class);
  }

  @Test
  void rxCompletableFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final Completable completable = api.completable();

    assertThat(completable).isNotNull();
    assertThat(server.getRequestCount()).isEqualTo(0);

    final TestSubscriber<String> testSubscriber = new TestSubscriber<>();
    completable.subscribe(testSubscriber);
    testSubscriber.awaitTerminalEvent();

    testSubscriber.assertCompleted();
  }

  @Test
  void plainString() {
    server.enqueue(new MockResponse().setBody("\"foo\""));

    final TestInterface api = target();

    final String string = api.get();

    assertThat(string).isEqualTo("foo");
  }

  @Test
  void plainStringFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final String string = api.get();

    assertThat(string).isEqualTo("fallback");
  }

  @Test
  void plainList() {
    server.enqueue(new MockResponse().setBody("[\"foo\",\"bar\"]"));

    final TestInterface api = target();

    final List<String> list = api.getList();

    assertThat(list).isNotNull().containsExactly("foo", "bar");
  }

  @Test
  void plainListFallback() {
    server.enqueue(new MockResponse().setResponseCode(500));

    final TestInterface api = target();

    final List<String> list = api.getList();

    assertThat(list).isNotNull().containsExactly("fallback");
  }

  @Test
  void equalsHashCodeAndToStringWork() {
    final Target<TestInterface> t1 =
        new HardCodedTarget<>(TestInterface.class, "http://localhost:8080");
    final Target<TestInterface> t2 =
        new HardCodedTarget<>(TestInterface.class, "http://localhost:8888");
    final Target<OtherTestInterface> t3 =
        new HardCodedTarget<>(OtherTestInterface.class, "http://localhost:8080");
    final TestInterface i1 = target(t1);
    final TestInterface i2 = target(t1);
    final TestInterface i3 = target(t2);
    final OtherTestInterface i4 = target(t3);

    assertThat(i1)
        .isEqualTo(i2)
        .isNotEqualTo(i3)
        .isNotEqualTo(i4);

    assertThat(i1.hashCode())
        .isEqualTo(i2.hashCode())
        .isNotEqualTo(i3.hashCode())
        .isNotEqualTo(i4.hashCode());

    assertThat(i1.toString())
        .isEqualTo(i2.toString())
        .isNotEqualTo(i3.toString())
        .isNotEqualTo(i4.toString());

    assertThat(t1)
        .isNotEqualTo(i1);

    assertThat(t1.hashCode())
        .isEqualTo(i1.hashCode());

    assertThat(t1.toString())
        .isEqualTo(i1.toString());
  }

  protected TestInterface target() {
    return HystrixFeign.builder()
        .decoder(new GsonDecoder())
        .target(TestInterface.class, "http://localhost:" + server.getPort(),
            new FallbackTestInterface());
  }

  protected TestInterface targetWithoutFallback() {
    return HystrixFeign.builder()
        .decoder(new GsonDecoder())
        .target(TestInterface.class, "http://localhost:" + server.getPort());
  }

  interface OtherTestInterface {

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    HystrixCommand<List<String>> listCommand();
  }

  interface TestInterface {

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    HystrixCommand<List<String>> listCommand();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    HystrixCommand<String> command();

    default HystrixCommand<String> defaultMethodReturningCommand() {
      return command();
    }

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    HystrixCommand<Integer> intCommand();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Observable<List<String>> listObservable();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Observable<String> observable();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Single<Integer> intSingle();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Single<List<String>> listSingle();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Single<String> single();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    Observable<Integer> intObservable();


    @RequestLine("GET /")
    @Headers("Accept: application/json")
    String get();

    @RequestLine("GET /")
    @Headers("Accept: application/json")
    List<String> getList();

    @RequestLine("GET /")
    Completable completable();

    @RequestLine("GET /")
    CompletableFuture<String> completableFuture();
  }

  class FallbackTestInterface implements TestInterface {
    @Override
    public HystrixCommand<String> command() {
      return new HystrixCommand<>(HystrixCommandGroupKey.Factory.asKey("Test")) {
        @Override
        protected String run() throws Exception {
          return "fallback";
        }
      };
    }

    @Override
    public HystrixCommand<List<String>> listCommand() {
      return new HystrixCommand<>(HystrixCommandGroupKey.Factory.asKey("Test")) {
        @Override
        protected List<String> run() throws Exception {
          final List<String> fallbackResult = new ArrayList<>();
          fallbackResult.add("fallback");
          return fallbackResult;
        }
      };
    }

    @Override
    public HystrixCommand<Integer> intCommand() {
      return new HystrixCommand<>(HystrixCommandGroupKey.Factory.asKey("Test")) {
        @Override
        protected Integer run() throws Exception {
          return 0;
        }
      };
    }

    @Override
    public Observable<List<String>> listObservable() {
      final List<String> fallbackResult = new ArrayList<>();
      fallbackResult.add("fallback");
      return Observable.just(fallbackResult);
    }

    @Override
    public Observable<String> observable() {
      return Observable.just("fallback");
    }

    @Override
    public Single<Integer> intSingle() {
      return Single.just(0);
    }

    @Override
    public Single<List<String>> listSingle() {
      final List<String> fallbackResult = new ArrayList<>();
      fallbackResult.add("fallback");
      return Single.just(fallbackResult);
    }

    @Override
    public Single<String> single() {
      return Single.just("fallback");
    }

    @Override
    public Observable<Integer> intObservable() {
      return Observable.just(0);
    }

    @Override
    public String get() {
      return "fallback";
    }

    @Override
    public List<String> getList() {
      final List<String> fallbackResult = new ArrayList<>();
      fallbackResult.add("fallback");
      return fallbackResult;
    }

    @Override
    public Completable completable() {
      return Completable.complete();
    }

    @Override
    public CompletableFuture<String> completableFuture() {
      return CompletableFuture.completedFuture("fallback");
    }
  }

  @AfterEach
  void afterEachTest() throws IOException {
    server.close();
  }
}
