/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import java.nio.ByteBuffer;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import static org.springframework.web.reactive.function.server.ServerResponse.noContent;
import reactor.core.publisher.Mono;

/**
 *
 * @author hantsy
 */
@Component
public class FavoriteHandler {

    private ReactiveRedisConnection conn;

    public FavoriteHandler(ReactiveRedisConnectionFactory factory) {
        this.conn = factory.getReactiveConnection();
    }

    // TODO not implemented yet.
    public Mono<ServerResponse> isFavorited(ServerRequest req) {

        String slug = req.pathVariable("slug");
        return req.principal()
            .map(p -> p.getName())
            .flatMap(
                name -> this.conn.zSetCommands()
                    .zAdd(ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(name.getBytes()))
                    .and(this.conn.zSetCommands().zAdd(ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(slug.getBytes())))
            )
            .flatMap(f -> noContent().build());

    }

    public Mono<ServerResponse> favorite(ServerRequest req) {

        String slug = req.pathVariable("slug");
        return req.principal()
            .map(p -> p.getName())
            .flatMap(
                name -> this.conn.zSetCommands()
                    .zAdd(ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(name.getBytes()))
                    .and(this.conn.zSetCommands().zAdd(ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()), 1.0D, ByteBuffer.wrap(slug.getBytes())))
            )
            .flatMap(f -> noContent().build());

    }

    public Mono<ServerResponse> unfavorite(ServerRequest req) {
        String slug = req.pathVariable("slug");
        return req.principal()
            .map(p -> p.getName())
            .flatMap(
                name -> this.conn.zSetCommands()
                    .zRem(ByteBuffer.wrap(("posts:" + slug + ":favorites").getBytes()), ByteBuffer.wrap(name.getBytes()))
                    .and(this.conn.zSetCommands().zRem(ByteBuffer.wrap(("users:" + name + ":favorites").getBytes()), ByteBuffer.wrap(slug.getBytes())))
            )
            .flatMap(f -> noContent().build());

    }

    private static String toString(ByteBuffer byteBuffer) {
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return new String(bytes);
    }
}
