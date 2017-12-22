package com.example.gateway;

import com.sun.org.apache.bcel.internal.generic.GETFIELD;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerExchangeFilterFunction;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.filter.factory.GatewayFilters;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicates;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.Routes;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.netflix.hystrix.HystrixCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.stream.IntStream;

import static org.springframework.cloud.gateway.filter.factory.GatewayFilters.addResponseHeader;
import static org.springframework.cloud.gateway.handler.predicate.RoutePredicates.host;
import static org.springframework.cloud.gateway.handler.predicate.RoutePredicates.path;
import static org.springframework.cloud.gateway.route.Routes.locator;
import static org.springframework.tuple.TupleBuilder.tuple;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
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
    SecurityWebFilterChain authorization(ServerHttpSecurity security) {
        return security
                .authorizeExchange().pathMatchers("/rl").authenticated()
                .anyExchange().permitAll()
                .and()
                .httpBasic()
                .and()
                .build();
    }

    @Bean
    WebClient client(LoadBalancerExchangeFilterFunction lb) {
        return WebClient.builder().filter(lb).build();
    }

    @Bean
    RouterFunction<ServerResponse> routes(WebClient webClient) {
        return route(
                GET("/posts/{slug}/favorites"),
                (req) -> {
                    Flux<String> favorites = webClient
                            .get()
                            .uri(favoriteServiceUrl + "/posts/{slug}/favorites", req.pathVariable("slug"))
                            .retrieve()
                            .bodyToFlux(ParameterizedTypeReference.forType(String.class));

                    Publisher<String> cb = HystrixCommands
                            .from(favorites)
                            .commandName("posts-favorites")
                            .fallback(Flux.just("favorited users!!"))
                            .eager()
                            .build();

                    return ok().body(cb, String.class);
                }
        ).andRoute(
                GET("/posts/{slug}/favorites"),
                (req) -> {
                    Flux<FavoritedPost> favorites = webClient
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
                            .fallback(Flux.just(new FavoritedPost("Loading error", "not_loaded", LocalDateTime.now())))
                            .eager()
                            .build();

                    return ok().body(cb, FavoritedPost.class);
                }
        );
    }

    @Bean
    RouteLocator gatewayRoutes(RequestRateLimiterGatewayFilterFactory rl, RouteLocatorBuilder locator) {
        return locator.routes()
                .route("user", predicate -> predicate.path("/user")
                        .uri(authServiceUrl + "/user")
                )

                .route("posts", predicate -> predicate.path("/posts")
                        .filter(rl.apply(RedisRateLimiter.args(2, 4)))
                        .uri(postServiceUrl + "/posts")
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
