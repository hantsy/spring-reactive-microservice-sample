package com.example.demo;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.server.RouterFunction;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

@SpringBootApplication
@EnableMongoAuditing
@Slf4j
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
        WebSessionIdResolver webSessionIdResolver() {
            HeaderWebSessionIdResolver  webSessionIdResolver =  new HeaderWebSessionIdResolver();
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

            RouterFunction<ServerResponse> postsRoutes = route(GET("/"), postController::all)
                    .andRoute(POST("/"), postController::create)
                    .andRoute(GET("/{slug}"), postController::get)
                    .andRoute(PUT("/{slug}"), postController::update)
                    .andRoute(DELETE("/{slug}"), postController::delete)
                    .andNest(path("/{slug}/comments"), commentsRoutes);

            return nest(path("/posts"), postsRoutes);
    }

    @Bean
    public AuditorAware<Username> auditorAware() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        log.debug("current authentication:" + authentication);

        if (authentication == null || !authentication.isAuthenticated()) {
            return () -> Optional.<Username>empty();
        }

        return () -> Optional.of(
                Username.builder()
                        .username(((UserDetails) authentication.getPrincipal()).getUsername())
                        .build()
        );
    }
}
