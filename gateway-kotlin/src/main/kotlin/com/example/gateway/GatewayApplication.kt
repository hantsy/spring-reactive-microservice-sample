package com.example.gateway

import org.reactivestreams.Publisher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerExchangeFilterFunction
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.cloud.netflix.hystrix.HystrixCommands
import org.springframework.context.support.beans
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.env.get
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import java.time.LocalDateTime

@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args) {
        beans {
            bean {
                DiscoveryClientRouteDefinitionLocator(ref())
            }
//            bean {
//                MapReactiveUserDetailsService(
//                        User.withDefaultPasswordEncoder()
//                                .username("user")
//                                .roles("USER")
//                                .password("pw")
//                                .build())
//            }
//            bean {
//                //@formatter:off
//                val http = ref<ServerHttpSecurity>()
//                http
//                        .csrf().disable()
//                        .httpBasic()
//                        .and()
//                        .authorizeExchange()
//                        .pathMatchers("/proxy").authenticated()
//                        .anyExchange().permitAll()
//                        .and()
//                        .build()
//                //@formatter:on
//            }
            bean {
                val builder = ref<RouteLocatorBuilder>()
                builder
                        .routes {
                            val authServiceUrl = env["services.auth-service.url"]
                            val postServiceUrl = env["services.post-service.url"]

                            route {
                                path("/user")
                                uri(authServiceUrl + "/user")
                            }

                            route {
                                val rl = ref<RequestRateLimiterGatewayFilterFactory>()
                                        .apply(RedisRateLimiter.args(2, 4))
                                path("/posts")
                                filters {
                                    filter(rl)
                                }
                                uri(postServiceUrl + "/posts")
                            }
                        }
            }
            bean {
                WebClient.builder().filter(ref<LoadBalancerExchangeFilterFunction>()).build()
            }
            bean {
                router {
                    val client = ref<WebClient>()

                    val favoriteServiceUrl = env["services.favorite-service.url"]
                    val postServiceUrl = env["services.post-service.url"]
                    GET("/posts/{slug}/favorites") {

                        val favorites: Publisher<String> = client
                                .get()
                                .uri(favoriteServiceUrl + "/posts/{slug}/favorites", it.pathVariable("slug"))
                                .retrieve()
                                .bodyToFlux(ParameterizedTypeReference.forType(String::class.java))


                        val cb = HystrixCommands
                                .from(favorites)
                                .commandName("posts-favorites")
                                .fallback(Flux.just("favorited users!!"))
                                .eager()
                                .build()

                        ServerResponse.ok().body(cb)
                    }


                    GET("/user/favorites") {

                        val favorites: Publisher<FavoritedPost> = client
                                .get()
                                .uri(favoriteServiceUrl +"/users/{username}/favorites", it.principal().block()?.name)
                                .retrieve()
                                .bodyToFlux(String::class.java)
                                .flatMap {
                                   slug -> client
                                            .get()
                                            .uri(postServiceUrl +"/posts/{slug}/", it)
                                            .retrieve()
                                            .bodyToFlux(Post::class.java)
                                            .map { (_, title, slug, _, createdDate) ->
                                                FavoritedPost(title, slug, createdDate)
                                            }
                                }


                        val cb = HystrixCommands
                                .from(favorites)
                                .commandName("posts-favorites")
                                .fallback(Flux.just(FavoritedPost("Loading error", "not_loaded", LocalDateTime.now())))
                                .eager()
                                .build()

                        ServerResponse.ok().body(cb)
                    }
                }
            }
        }
    }
}

data class Post(var id: Long, var title: String, var slug: String, var content: String, var createdData: LocalDateTime)
data class FavoritedPost(var title: String, var slug: String, var createdDate: LocalDateTime)