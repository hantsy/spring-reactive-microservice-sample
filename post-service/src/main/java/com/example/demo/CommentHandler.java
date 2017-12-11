/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 *
 * @author hantsy
 */
@Component
class CommentHandler {

    private final CommentRepository comments;

    public CommentHandler(CommentRepository comments) {
        this.comments = comments;
    }

    public Mono<ServerResponse> all(ServerRequest req) {
        return ServerResponse.ok().body(this.comments.findAll(), Comment.class);
    }

    public Mono<ServerResponse> create(ServerRequest req) {
        return req
                .bodyToMono(Comment.class)
                .flatMap((comment) -> this.comments.save(comment))
                .flatMap((p) -> ServerResponse.created(URI.create("/posts/" + req.pathVariable("slug") + "/comments/" + p.getId())).build());
    }

    public Mono<ServerResponse> get(ServerRequest req) {
        return this.comments
                .findById(req.pathVariable("commentid"))
                .flatMap((comment) -> ServerResponse.ok().body(Mono.just(comment), Comment.class))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> update(ServerRequest req) {
        return Mono
                .zip(
                        (data) -> {
                            Comment p = (Comment) data[0];
                            Comment p2 = (Comment) data[1];

                            p.setContent(p2.getContent());
                            return p;
                        },
                        this.comments.findById(req.pathVariable("commentid")),
                        req.bodyToMono(Comment.class)
                )
                .cast(Comment.class)
                .flatMap((comment) -> this.comments.save(comment))
                .flatMap((comment) -> ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> delete(ServerRequest req) {
        return ServerResponse.noContent().build(this.comments.deleteById(req.pathVariable("commentid")));
    }

}
