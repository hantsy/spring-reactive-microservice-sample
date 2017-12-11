/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

/**
 *
 * @author hantsy
 */
interface PostRepository extends ReactiveMongoRepository<Post, String> {
    Mono<Post> findBySlug(String slug);
}
