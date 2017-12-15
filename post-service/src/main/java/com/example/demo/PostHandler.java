/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import java.net.URI;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.ServerResponse.*;


/**
 * @author hantsy
 */
@Component
class PostHandler {

    private final PostRepository posts;

    public PostHandler(PostRepository posts) {
        this.posts = posts;
    }

    public Mono<ServerResponse> all(ServerRequest req) {
        return ok().body(this.posts.findAll(), Post.class);
    }

    public Mono<ServerResponse> stream(ServerRequest req) {
        return ok().body(Flux.interval(Duration.ofSeconds(30L)).flatMap(s -> this.posts.findAll()), Post.class);
    }

    public Mono<ServerResponse> create(ServerRequest req) {
        return req
                .bodyToMono(Post.class)
                .flatMap((post) -> this.posts.save(post))
                .flatMap((p) -> created(URI.create("/posts/" + p.getSlug())).build());
    }

    public Mono<ServerResponse> get(ServerRequest req) {
        return this.posts
                .findBySlug(req.pathVariable("slug"))
                .flatMap((post) -> ok().body(BodyInserters.fromObject(post)))
                .switchIfEmpty(notFound().build());
    }

    public Mono<ServerResponse> update(ServerRequest req) {
        return Mono
                .zip(
                        (data) -> {
                            Post p = (Post) data[0];
                            Post p2 = (Post) data[1];
                            p.setTitle(p2.getTitle());
                            p.setContent(p2.getContent());
                            return p;
                        },
                        this.posts.findById(req.pathVariable("slug")),
                        req.bodyToMono(Post.class)
                )
                .cast(Post.class)
                .flatMap((post) -> this.posts.save(post))
                .flatMap((post) -> noContent().build());
    }

    public Mono<ServerResponse> delete(ServerRequest req) {
        return this.posts.findBySlug(req.pathVariable("slug"))
                .flatMap((post) -> noContent().build())
                .switchIfEmpty(notFound().build());
    }

}
