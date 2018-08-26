package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.server.RouterFunction;

import static org.springframework.web.reactive.function.server.RequestPredicates.*;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

@SpringBootApplication
@EnableMongoAuditing
@EnableDiscoveryClient
@Slf4j
public class PostServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostServiceApplication.class, args);
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
                .pathMatchers(HttpMethod.GET, "/posts/**").permitAll()
                .pathMatchers(HttpMethod.DELETE, "/posts/**").hasRole("ADMIN")
                .anyExchange().authenticated()
                .and()
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> routes(
            PostRepository posts,
            PostHandler postController,
            CommentHandler commentHandler) {
        RouterFunction<ServerResponse> commentsRoutes = route(GET("/"), commentHandler::all)
                .andRoute(POST("/"), commentHandler::create)
                .andRoute(GET("/{commentid}"), commentHandler::get)
                .andRoute(PUT("/{commentid}"), commentHandler::update)
                .andRoute(DELETE("/{commentid}"), commentHandler::delete);

        RouterFunction<ServerResponse> postsRoutes =
                route(accept(MediaType.APPLICATION_JSON_UTF8).and(GET("/")), postController::all)
                .andRoute(accept(MediaType.APPLICATION_STREAM_JSON).and(GET("/")), postController::stream)
                .andRoute(POST("/"), postController::create)
                .andRoute(GET("/{slug}"), postController::get)
                .andRoute(PUT("/{slug}"), postController::update)
                .andRoute(DELETE("/{slug}"), postController::delete)
                .andNest(path("/{slug}/comments"), commentsRoutes);

        return nest(path("/posts"), postsRoutes);
    }

    @Bean
    public AuditorAware<Username> auditorAware() {
        return () -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> {

                            if (auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                                UserDetails userDetails = (UserDetails) auth.getPrincipal();

                                return new Username(userDetails.getUsername());
                            }

                            return null;
                        }
                )
                .blockOptional();
    }

    @Bean
    public AbstractMongoEventListener<Post> mongoEventListener() {
        return new AbstractMongoEventListener<Post>() {

            @Override
            public void onBeforeConvert(BeforeConvertEvent<Post> event) {
                super.onBeforeConvert(event);
                event.getSource().setSlug(Utils.slugify(event.getSource().getTitle()));

                log.debug("after set slug:: onBeforeConvert({}, {})", event.getSource(), event.getDocument());
            }

        };
    }
}
