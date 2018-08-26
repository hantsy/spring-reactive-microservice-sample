package com.example.favoriteservice;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.Collections;

import static org.springframework.web.reactive.function.server.RequestPredicates.*;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.noContent;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
@EnableDiscoveryClient
public class FavoriteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FavoriteServiceApplication.class, args);
    }

    @Bean
    WebSessionIdResolver webSessionIdResolver() {
        HeaderWebSessionIdResolver webSessionIdResolver = new HeaderWebSessionIdResolver();
        webSessionIdResolver.setHeaderName("X-AUTH-TOKEN");
        return webSessionIdResolver;
    }

    @Bean
    SecurityWebFilterChain springWebFilterChain(ServerHttpSecurity http) throws Exception {
        return http
                .csrf().disable()
                .authorizeExchange()
                .anyExchange().authenticated()
                .and()
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> routes(FavoriteHandler favoriteHandler) {
        RouterFunction<ServerResponse> usersRoutes = route(GET("/{username}/favorites"), favoriteHandler::favoritedPosts);
        RouterFunction<ServerResponse> postsRoutes = route(GET("/{slug}/favorited"), favoriteHandler::favorited)
                .andRoute(GET("/{slug}/favorites"), favoriteHandler::all)
                .andRoute(POST("/{slug}/favorites"), favoriteHandler::favorite)
                .andRoute(DELETE("/{slug}/favorites"), favoriteHandler::unfavorite);

        return nest(path("/posts"), postsRoutes)
                .andNest(path("/users"), usersRoutes);
    }
}

@Component
class FavoriteHandler {

    private ReactiveRedisConnection conn;

    public FavoriteHandler(ReactiveRedisConnectionFactory factory) {
        this.conn = factory.getReactiveConnection();
    }

    public Mono<ServerResponse> favorited(ServerRequest req) {

        String slug = req.pathVariable("slug");
        return req.principal()
                .map(p -> p.getName())
                .flatMap(
                        name -> this.conn.zSetCommands()
                                .zRange(
                                        ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()),
                                        Range.of(Range.Bound.inclusive(0L), Range.Bound.inclusive(-1L))
                                )
                                .map(this::toString)
                                .collectList()
                                .map(f -> Collections.singletonMap("favorited", f.contains(name)))
                )
                .flatMap(f -> ok().body(BodyInserters.fromObject(f)));

    }

    public Mono<ServerResponse> all(ServerRequest req) {

        String slug = req.pathVariable("slug");
        return this.conn.zSetCommands()
                .zRange(
                        ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()),
                        Range.of(Range.Bound.inclusive(0L), Range.Bound.inclusive(-1L))
                )
                .map(this::toString)
                .collectList()
                .flatMap(f -> ok().body(BodyInserters.fromObject(f)));
    }

    public Mono<ServerResponse> favoritedPosts(ServerRequest req) {

        return req.principal()
                .map(p -> p.getName())
                .flatMap(
                        name -> this.conn.zSetCommands()
                                .zRange(
                                        ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()),
                                        Range.of(Range.Bound.inclusive(0L), Range.Bound.inclusive(-1L))
                                )
                                .map(this::toString)
                                .collectList()
                )
                .flatMap(f -> ok().body(BodyInserters.fromObject(f)));
    }

    public Mono<ServerResponse> favorite(ServerRequest req) {

        String slug = req.pathVariable("slug");
        return req.principal()
                .map(p -> p.getName())
                .flatMap(
                        name -> this.conn.zSetCommands()
                                .zAdd(ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(name.getBytes()))
                                .then(this.conn.zSetCommands().zAdd(ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(slug.getBytes())))
                )
                .flatMap(f -> ok().build());
    }


    public Mono<ServerResponse> unfavorite(ServerRequest req) {
        String slug = req.pathVariable("slug");
        return req.principal()
                .map(p -> p.getName())
                .flatMap(
                        name -> this.conn.zSetCommands()
                                .zRem(ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()), ByteBuffer.wrap(name.getBytes()))
                                .then(this.conn.zSetCommands().zRem(ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()), ByteBuffer.wrap(slug.getBytes())))
                )
                .flatMap(f -> noContent().build());

    }

    private String toString(ByteBuffer byteBuffer) {
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return new String(bytes);
    }
}

