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
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerExchangeFilterFunction;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.netflix.hystrix.HystrixCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.tuple.Tuple;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.RequestContext;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
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
            .httpBasic()
            .and()
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
                    .mutate().filter(new CopyRequestExchangeFilterFunction(req)).build()
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
                Flux<FavoritedPost> favorites = webClient
                    .mutate().filter(new CopyRequestExchangeFilterFunction(req)).build()
                    .get()
                    .uri(favoriteServiceUrl + "/users/{username}/favorites", req.principal().block().getName())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .flatMap(
                        slug -> webClient
                            .get()
                            .uri(postServiceUrl + "/posts/{slug}/", slug)
                            .retrieve()
                            .bodyToMono(Post.class)
                            .map(p -> new FavoritedPost(p.getTitle(), slug, p.getCreatedDate()))
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
            .route("user", predicate -> predicate.path("/user")
                .uri(authServiceUrl)
            )

            .route("users", predicate -> predicate.path("/users/**")
                .uri(authServiceUrl)
            )

            .route("favorites", predicate -> predicate.path("/posts/*/favorites")
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
 * Sample throttling filter.
 * See https://github.com/bbeck/token-bucket
 */
@Slf4j
@Component
class ThrottleGatewayFilterFactory implements GatewayFilterFactory {

    @Override
    public GatewayFilter apply(Tuple args) {
        int capacity = args.getInt("capacity");
        int refillTokens = args.getInt("refillTokens");
        int refillPeriod = args.getInt("refillPeriod");
        TimeUnit refillUnit = TimeUnit.valueOf(args.getString("refillUnit"));
        return apply(capacity, refillTokens, refillPeriod, refillUnit);
    }

    public GatewayFilter apply(int capacity, int refillTokens, int refillPeriod, TimeUnit refillUnit) {

        final TokenBucket tokenBucket = TokenBuckets.builder()
            .withCapacity(capacity)
            .withFixedIntervalRefillStrategy(refillTokens, refillPeriod, refillUnit)
            .build();

        return (exchange, chain) -> {
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
}

@Slf4j
class CopyRequestExchangeFilterFunction implements ExchangeFilterFunction {

    private ServerRequest request;

    public CopyRequestExchangeFilterFunction(ServerRequest request) {
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
