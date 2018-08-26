package com.example.gateway;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.isomorphism.util.TokenBucket;
import org.isomorphism.util.TokenBuckets;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerExchangeFilterFunction;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.netflix.hystrix.HystrixCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.springframework.cloud.netflix.hystrix.HystrixCommands.from;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
@EnableDiscoveryClient
@EnableCircuitBreaker
@Slf4j
public class GatewayApplication {

    @Value("${services.auth-service.url}")
    private String authServiceUrl;

    @Value("${services.post-service.url}")
    private String postServiceUrl;

    @Value("${services.favorite-service.url}")
    private String favoriteServiceUrl;

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);

    }


    @Bean
    WebSessionIdResolver webSessionIdResolver() {
        HeaderWebSessionIdResolver webSessionIdResolver = new HeaderWebSessionIdResolver();
        webSessionIdResolver.setHeaderName("X-AUTH-TOKEN");
        return webSessionIdResolver;
    }

    @Bean
    SecurityWebFilterChain authorization(ServerHttpSecurity security) {
        return security
            .authorizeExchange().pathMatchers("/user/**").authenticated()
            .anyExchange().permitAll()
            .and()
            .httpBasic().disable()
            .csrf().disable()
            .build();
    }

    @Bean
    WebClient client(LoadBalancerExchangeFilterFunction lb, CopyRequestAuthTokenHeaderExchangeFilterFunction xtoken) {
        return WebClient.builder()
            .filter(lb)
            .filter(xtoken)
            .build();
    }

    @Bean
    RouterFunction<ServerResponse> routes(WebClient webClient) {
        log.debug("authServiceUrl:{}", this.authServiceUrl);
        log.debug("postServiceUrl:{}", this.postServiceUrl);
        log.debug("favoriteServiceUrl:{}", this.favoriteServiceUrl);

        return route(
            GET("/posts/{slug}/favorited"),
            (req) -> {
                Flux<Map> favorites = webClient
                    //.mutate().filter(new CopyRequestAuthTokenHeaderExchangeFilterFunction(req)).build()
                    .get()
                    .uri(favoriteServiceUrl + "/posts/{slug}/favorited", req.pathVariable("slug"))
                    .retrieve()
                    .bodyToFlux(Map.class);

                Publisher<Map> cb = from(favorites)
                    .commandName("posts-favorites")
                    .fallback(Flux.just(Collections.singletonMap("favorited", false)))
                    .eager()
                    .build();

                return ok().body(favorites, Map.class);
            }
        ).andRoute(
            GET("/posts/{slug}/favorites"),
            (req) -> {
                Flux<String> favorites = webClient
                    .get()
                    .uri(favoriteServiceUrl + "/posts/{slug}/favorites", req.pathVariable("slug"))
                    .retrieve()
                    .bodyToFlux(String.class);

                Publisher<String> cb = from(favorites)
                    .commandName("posts-favorites")
                    .fallback(Flux.just("loading favorited users failed!"))
                    .eager()
                    .build();

                return ok().body(cb, String.class);
            }
        ).andRoute(
            GET("/user/favorites"),
            (req) -> {
                Flux<FavoritedPost> favorites = req.principal()
                    .flatMapMany(
                        p -> webClient
                           //.mutate().filter(new CopyRequestAuthTokenHeaderExchangeFilterFunction(req)).build()
                            .get()
                            .uri(favoriteServiceUrl + "/users/{username}/favorites", p.getName())
                            .retrieve()
                            .bodyToFlux(String.class)
                            .flatMap(
                                slug -> webClient
                                    .get()
                                    .uri(postServiceUrl + "/posts/{slug}/", slug)
                                    .retrieve()
                                    .bodyToMono(Post.class)
                                    .map(post -> new FavoritedPost(post.getTitle(), slug, post.getCreatedDate()))
                            )
                    );


                Publisher<FavoritedPost> cb = HystrixCommands
                    .from(favorites)
                    .commandName("posts-favorites")
                    .fallback(Flux.just(new FavoritedPost("Loading favorited posts failed", "not_loaded", LocalDateTime.now())))
                    .eager()
                    .build();

                return ok().body(cb, FavoritedPost.class);
            }
        );
    }

    @Bean
    @Order(-1)
    RouteLocator gatewayRoutes(RequestRateLimiterGatewayFilterFactory rl,
                               ThrottleGatewayFilterFactory throttle,
                               RouteLocatorBuilder locator) {
        return locator.routes()
            .route("session", predicate -> predicate.path("/session")
                .uri(authServiceUrl)
            )

            .route("users", predicate -> predicate.path("/users/**")
                .uri(authServiceUrl)
            )

            .route("favorites", predicate -> predicate
                .method(HttpMethod.POST)
                .or()
                .method(HttpMethod.DELETE)
                .and()
                .path("/posts/*/favorites")
                //.or().method(HttpMethod.GET).and().path("/posts/*/favorites/**")
                .uri(favoriteServiceUrl)
            )

            .route("posts", predicate -> predicate.path("/posts/**")
                .filters(
                    g -> g
                        .filter(throttle.apply(ThrottleGatewayFilterFactory.Config.builder().capacity(1).refillPeriod(1).refillTokens(1).refillUnit(TimeUnit.MILLISECONDS).build()))
                        .filter(rl.apply(new RequestRateLimiterGatewayFilterFactory.Config().setRateLimiter(new RedisRateLimiter(2, 4))))

                )
                .uri(postServiceUrl)
            )
            .build();
    }

    // spring.cloud.gateway.discovery.locator.enabled=true
    @Bean
    DiscoveryClientRouteDefinitionLocator discoveryRoutes(DiscoveryClient dc, DiscoveryLocatorProperties props) {
        return new DiscoveryClientRouteDefinitionLocator(dc, props);
    }

}

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
class Post {
    private Long id;
    private String slug;
    private String title;
    private String content;
    private LocalDateTime createdDate;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
class FavoritedPost {
    private String slug;
    private String title;
    private LocalDateTime createdDate;
}

/**
 * https://github.com/spring-cloud/spring-cloud-gateway/blob/master/spring-cloud-gateway-sample/src/main/java/org/springframework/cloud/gateway/sample/ThrottleGatewayFilter.java
 * Sample throttling filter.
 * See https://github.com/bbeck/token-bucket
 */
@Slf4j
@Component
class ThrottleGatewayFilterFactory extends AbstractGatewayFilterFactory<ThrottleGatewayFilterFactory.Config> {
    int capacity = 1;
    int refillTokens = 1;
    int refillPeriod = 1;
    TimeUnit refillUnit = TimeUnit.MILLISECONDS;

    @Override
    public GatewayFilter apply(Config config) {
        int capacity = config.getCapacity() <= 0 ? this.capacity : config.getCapacity();
        int refillTokens = config.getRefillTokens() <= 0 ? this.refillTokens : config.getRefillTokens();
        int refillPeriod = config.getRefillPeriod() <= 0 ? this.refillPeriod : config.getRefillPeriod();
        TimeUnit refillUnit = config.getRefillUnit() == null ? this.refillUnit : config.getRefillUnit();

        return (exchange, chain) -> {
            TokenBucket tokenBucket = TokenBuckets.builder()
                .withCapacity(capacity)
                .withFixedIntervalRefillStrategy(refillTokens, refillPeriod, refillUnit)
                .build();

            //TODO: get a token bucket for a key
            log.debug("TokenBucket capacity: " + tokenBucket.getCapacity());
            boolean consumed = tokenBucket.tryConsume();
            if (consumed) {
                return chain.filter(exchange);
            }
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        };
    }

    @Setter
    @Getter
    @Builder
    public static class Config {
        int capacity;
        int refillTokens;
        int refillPeriod;
        TimeUnit refillUnit;
    }
}

@Slf4j
@Component
class CopyRequestAuthTokenHeaderExchangeFilterFunction implements ExchangeFilterFunction {


    @Override
    public Mono<ClientResponse> filter(ClientRequest clientRequest, ExchangeFunction next) {

        ClientRequest newRequest = ClientRequest.from(clientRequest).build();
        if (clientRequest.headers().containsKey("X-AUTH-TOKEN")) {
            newRequest.headers().add(
                "X-AUTH-TOKEN",
                clientRequest.headers().getFirst("X-AUTH-TOKEN")
            );
        }
        log.debug("client request header X-AUTH-TOKEN: {}", newRequest.headers().get("X-AUTH-TOKEN"));

        return next.exchange(newRequest);
    }

}
