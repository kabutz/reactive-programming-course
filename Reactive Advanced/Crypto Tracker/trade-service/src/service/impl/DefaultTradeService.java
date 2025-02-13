package service.impl;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.mongodb.MongoException;
import domain.Trade;
import domain.utils.DomainMapper;
import dto.MessageDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import repository.TradeRepository;
import service.CryptoService;
import service.TradeService;
import service.utils.MessageMapper;

import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

public class DefaultTradeService implements TradeService {

	private static final Logger logger = LoggerFactory.getLogger("trade-service");

	private final Flux<MessageDTO<MessageDTO.Trade>> sharedStream;

	public DefaultTradeService(CryptoService service,
			TradeRepository jdbcRepository,
			TradeRepository mongoRepository
	) {
		service.eventsStream()
		       .transform(this::filterAndMapTradingEvents)
		       .transform(this::mapToDomainTrade)
		       .as(f -> this.resilientlyStoreByBatchesToAllRepositories(f, jdbcRepository, mongoRepository))
		       .subscribe();
		sharedStream = service.eventsStream()
		                      .transform(this::filterAndMapTradingEvents);
	}

	@Override
	public Flux<MessageDTO<MessageDTO.Trade>> tradesStream() {
		return sharedStream;
	}

	Flux<MessageDTO<MessageDTO.Trade>> filterAndMapTradingEvents(Flux<Map<String, Object>> input) {
		// TODO: Add implementation to produce trading events
		return input.handle((m, s) -> {
			if (MessageMapper.isTradeMessageType(m)) {
				s.next(MessageMapper.mapToTradeMessage(m));
			}
		});
	}

	Flux<Trade> mapToDomainTrade(Flux<MessageDTO<MessageDTO.Trade>> input) {
		// TODO: Add implementation to mapping to com.example.part_10.domain.Trade
		return input.map(DomainMapper::mapToDomain);
	}

	Mono<Void> resilientlyStoreByBatchesToAllRepositories(
			Flux<Trade> input,
			TradeRepository tradeRepository1,
			TradeRepository tradeRepository2) {

		return input
			.windowTimeout(Integer.MAX_VALUE, Duration.ofSeconds(1), true)
			.log("window")
			.concatMap(tradesFlux -> tradesFlux.collectList().log("batch").flatMap(trades -> {
				if (trades.isEmpty()) {
					return Mono.empty();
				}

				return Mono
					.when(
							saveIntoMongoDatabase(tradeRepository1, trades),
							saveIntoRelationalDatabase(tradeRepository2, trades)
					);
			}), 0)
			.then();
	}

	Mono<Void> saveIntoMongoDatabase(TradeRepository tradeRepository1, List<Trade> trades) {
		return tradeRepository1
				.saveAll(trades)
				.timeout(Duration.ofSeconds(1))
				.retryWhen(Retry.backoff(100, Duration.ofMillis(100))
				                .maxBackoff(Duration.ofSeconds(5))
				                .filter(exception -> {
					                if (exception instanceof MongoException) {
						                return ((MongoException) exception).getCode() != 11000;
					                }

					                return true;
				                }))
				.onErrorResume(MongoException.class, t -> Mono.empty());
	}

	Mono<Void> saveIntoRelationalDatabase(TradeRepository tradeRepository2, List<Trade> trades) {
		return tradeRepository2
				.saveAll(trades)
				.timeout(Duration.ofSeconds(1))
				.retryWhen(Retry.backoff(100, Duration.ofMillis(500))
				                .maxBackoff(Duration.ofMillis(5000)));
	}

}
