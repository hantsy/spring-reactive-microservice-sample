package com.example.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.isomorphism.util.TokenBucket;
import org.isomorphism.util.TokenBuckets;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.netflix.hystrix.HystrixCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;

import org.springframework.web.reactive.function.client.*;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
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
//
//    @Bean
//    WebClient client(LoadBalancerExchangeFilterFunction lb) {
//        return WebClient.builder()
//            .filter(lb)
//            .build();
//    }

    @Bean
    WebClient client() {
        return WebClient.builder()
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
                    .mutate().filter(new CopyRequestAuthTokenHeaderExchangeFilterFunction(req)).build()
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
                            .mutate().filter(new CopyRequestAuthTokenHeaderExchangeFilterFunction(req)).build()
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
                               ThrottleGatewayFilter throttle,
                               RouteLocatorBuilder locator) {
        return locator.routes()
            .route("session", predicate -> predicate.path("/session")
                .uri(authServiceUrl)
            )

            .route("users", predicate -> predicate.path("/users/**")
                .uri(authServiceUrl)
            )

            .route("favorites", predicate -> predicate
                .method(HttpMethod.POST).or().method(HttpMethod.DELETE).and().path("/posts/*/favorites")
                //.or().method(HttpMethod.GET).and().path("/posts/*/favorites/**")
                .uri(favoriteServiceUrl)
            )

            .route("posts", predicate -> predicate.path("/posts/**")
//                .filter(throttle.apply(1,
//                    1,
//                    10,
//                    TimeUnit.SECONDS))
//                .filter(rl.apply(RedisRateLimiter.args(2, 4)))
                    .uri(postServiceUrl)
            )
            .build();
    }

    // spring.cloud.gateway.discovery.locator.enabled=true
//    @Bean
//    DiscoveryClientRouteDefinitionLocator discoveryRoutes(DiscoveryClient dc) {
//        return new DiscoveryClientRouteDefinitionLocator(dc);
//    }
//
//    @Bean
//    RouteLocator gatewayRoutes(RequestRateLimiterGatewayFilterFactory rl, RouteLocatorBuilder locator) {
//        return locator.routes()
//                .route("test", predicate -> predicate.host("**.abc.org")
//                        .and()
//                        .path("/image/png")
//                        .addResponseHeader("X-TestHeader", "foobar")
//                        .uri("http://httpbin.org:80")
//                )
//
//                .route("test2", predicate -> predicate.path("/image/webp")
//                        .add(addResponseHeader("X-AnotherHeader", "baz"))
//                        .uri("http://httpbin.org:80")
//                )
//                .route("test3", predicate -> predicate.host("**.throttle.org")
//                        .and()
//                        .path("/get")
//                        .add(throttle.apply(tuple().of("capacity", 1,
//                                "refillTokens", 1,
//                                "refillPeriod", 10,
//                                "refillUnit", "SECONDS")))
//                        .uri("http://httpbin.org:80")
//                )
//                .order(-1)
//                .build();
//    }

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
class ThrottleGatewayFilter implements GatewayFilter {

    int capacity;
    int refillTokens;
    int refillPeriod;
    TimeUnit refillUnit;

    public int getCapacity() {
        return capacity;
    }

    public ThrottleGatewayFilter setCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public int getRefillTokens() {
        return refillTokens;
    }

    public ThrottleGatewayFilter setRefillTokens(int refillTokens) {
        this.refillTokens = refillTokens;
        return this;
    }

    public int getRefillPeriod() {
        return refillPeriod;
    }

    public ThrottleGatewayFilter setRefillPeriod(int refillPeriod) {
        this.refillPeriod = refillPeriod;
        return this;
    }

    public TimeUnit getRefillUnit() {
        return refillUnit;
    }

    public ThrottleGatewayFilter setRefillUnit(TimeUnit refillUnit) {
        this.refillUnit = refillUnit;
        return this;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

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
    }
}

@Slf4j
class CopyRequestAuthTokenHeaderExchangeFilterFunction implements ExchangeFilterFunction {

    private ServerRequest request;

    public CopyRequestAuthTokenHeaderExchangeFilterFunction(ServerRequest request) {
        this.request = request;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest clientRequest, ExchangeFunction next) {

        ClientRequest newRequest = ClientRequest.from(clientRequest).build();
        if (this.request.headers().asHttpHeaders().containsKey("X-AUTH-TOKEN")) {
            newRequest.headers().add(
                "X-AUTH-TOKEN",
                this.request.headers().asHttpHeaders().getFirst("X-AUTH-TOKEN")
            );
        }
        log.debug("client request header X-AUTH-TOKEN: {}", newRequest.headers().get("X-AUTH-TOKEN"));

        return next.exchange(newRequest);
    }

}
