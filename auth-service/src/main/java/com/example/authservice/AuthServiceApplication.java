package com.example.authservice;

import lombok.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

import static java.util.stream.Collectors.toList;
import static org.springframework.data.cassandra.core.query.Criteria.where;
import static org.springframework.data.cassandra.core.query.Query.query;
import static org.springframework.security.core.userdetails.User.withUserDetails;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;


@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
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
                .authorizeExchange()
                .pathMatchers(HttpMethod.GET, "/users/exists").permitAll()
                .pathMatchers("/users/{user}/**").access(this::currentUserMatchesPath)
                .anyExchange().authenticated()
                .and()
                .build();
    }

    private Mono<AuthorizationDecision> currentUserMatchesPath(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
                .map((a) -> context.getVariables().get("user").equals(a.getName()))
                .map(AuthorizationDecision::new);
    }

    @Bean
    public ReactiveUserDetailsService userDetailsRepository(UserRepository users) {
        return (username) -> users
                .findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withDefaultPasswordEncoder()
                        .username(user.getUsername())
                        .password(user.getPassword())
                        .authorities(user.getRoles().stream().map(SimpleGrantedAuthority::new).collect(toList()))
                        .accountExpired(!user.isActive())
                        .credentialsExpired(!user.isActive())
                        .accountLocked(!user.isActive())
                        .disabled(!user.isActive())
                        .build()
                )
                .cast(UserDetails.class);
    }

    @Bean
    public RouterFunction<ServerResponse> routes(
            UserHandler userHandler) {
        return route(GET("/"), userHandler::current)
                .andRoute(GET("/exists"), userHandler::exists);
    }
}


@Component
class UserHandler {

    private final UserRepository users;

    public UserHandler(UserRepository users) {
        this.users = users;
    }

    public Mono<ServerResponse> current(ServerRequest req) {
        return req.principal()
                .cast(UserDetails.class)
                .map(
                        user -> {
                            Map<Object, Object> map = new HashMap<>();
                            map.put("username", user.getUsername());
                            map.put("roles", AuthorityUtils.authorityListToSet(user.getAuthorities()));
                            return map;
                        }
                )
                .flatMap((user) -> ok().body(BodyInserters.fromObject(user)));
    }

    public Mono<ServerResponse> exists(ServerRequest req) {
        return Mono.justOrEmpty(req.queryParam("useranme"))
                .flatMap(this.users::findByUsername)
                .flatMap(user -> ok().body(BodyInserters.fromObject(UsernameAvailability.builder().build())))
                .switchIfEmpty(ok().body(BodyInserters.fromObject(new UsernameAvailability(true))));
    }
}

@Component
class UserRepository {

    private final ReactiveCassandraTemplate template;

    public UserRepository(ReactiveCassandraTemplate template) {
        this.template = template;
    }

    public Mono<User> findByUsername(String username) {
        return this.template
                .selectOne(
                        query(where("username").is(username)),
                        User.class
                );
    }

    public Mono<User> save(User user) {
        return this.template.insert(user);
    }

    public Mono<Boolean> deleteAll() {
        return this.template.delete(Query.empty(), User.class);
    }

}

@Data
@AllArgsConstructor
@Builder
class UsernameAvailability {
    @Builder.Default
    private boolean available = false;
}

@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
class User {

    @PrimaryKey
    private String username;
    private String password;

    @Builder.Default
    private boolean active = true;
    @Builder.Default
    private List<String> roles = new ArrayList<>();


}

