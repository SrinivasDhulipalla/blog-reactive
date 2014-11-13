package se.callista.springmvc.asynch.pattern.aggregator;

import com.ning.http.client.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import se.callista.springmvc.asynch.common.lambdasupport.AsyncHttpClientJava8;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.function.Function.identity;

@RestController
public class AggregatorNonBlockingJava8Controller {

	@Value("${sp.non_blocking.url}")
	private String SP_NON_BLOCKING_URL;

	@Value("${aggregator.timeoutMs}")
	private int TIMEOUT_MS;

	@Autowired
	@Qualifier("dbThreadPoolExecutor")
	private TaskExecutor dbThreadPoolExecutor;

	@Autowired
	private AsyncHttpClientJava8 asyncHttpClientJava8;

	/**
	 * Sample usage: curl "http://localhost:9080/aggregate-non-blocking-java8?minMs=1000&maxMs=2000"
	 *
	 * @param dbLookupMs
	 * @param dbHits
	 * @param minMs
	 * @param maxMs
	 * @return
	 * @throws java.io.IOException
	 */
	@RequestMapping("/aggregate-non-blocking-java8")
	public DeferredResult<String> nonBlockingAggregator(
			@RequestParam(value = "dbLookupMs", required = false, defaultValue = "0") int dbLookupMs,
			@RequestParam(value = "dbHits", required = false, defaultValue = "3") int dbHits,
			@RequestParam(value = "minMs", required = false, defaultValue = "0") int minMs,
			@RequestParam(value = "maxMs", required = false, defaultValue = "0") int maxMs) throws IOException {

		DeferredResult<String> deferredResult = new DeferredResult<>();

		dbLookup(dbLookupMs, dbHits, minMs, maxMs)
				.thenCompose(urls -> executeRemoteHttpRequests(urls))
				.thenApply(result -> deferredResult.setResult(result
						.stream()
							.map(extractResponseBody)
						.collect(Collectors.joining("\n"))));

		return deferredResult;
	}

	public CompletableFuture<List<Response>> executeRemoteHttpRequests(List<String> urls) {
		return sequence(urls
						.stream()
							.map(this::doAsyncCall)
						.collect(Collectors.toList())
				)
				.thenApply(responses -> responses
						.stream()
							.filter(Optional::isPresent)
							.map(Optional::get)
						.collect(Collectors.toList())
				);
	}

	private CompletableFuture<Optional<Response>> doAsyncCall(String url) {
		return asyncHttpClientJava8.execute(url)
				.thenApply(Optional::of)
				.applyToEither(TimeoutDefault.with(TIMEOUT_MS), identity())
				.exceptionally(t -> Optional.empty());
	}

	private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
		CompletableFuture<Void> allDoneFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
		return allDoneFuture.thenApply(v ->
						futures.stream().
								map(future -> future.join()).
								collect(Collectors.<T>toList())
		);
	}

	private CompletableFuture<List<String>> dbLookup(int dbLookupMs, int dbHits, int minMs, int maxMs) {
		final String url = SP_NON_BLOCKING_URL + "?minMs=" + minMs + "&maxMs=" + maxMs;
		final DbLookup dbLookup = new DbLookup(dbLookupMs, dbHits);

		return supplyAsync(() -> dbLookup.executeDbLookup(), dbThreadPoolExecutor)
				.thenApply(noOfCalls -> Collections.nCopies(noOfCalls, url));
	}


	private Function<Response, String> extractResponseBody =
			response -> {
				try {
					return response.getResponseBody();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};

}
