package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;

import java.net.URI;
import java.time.Duration;
import java.util.Random;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.basicAuthentication;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Slf4j
public class PostServiceApplicationTests {

    @Autowired
    RouterFunction<?> routerFunction;
    @Autowired
    WebFilterChainProxy springSecurityFilterChain;

    WebTestClient client;

    @Before
    public void setup() {
        this.client = WebTestClient
                .bindToRouterFunction(this.routerFunction)
                .webFilter(this.springSecurityFilterChain)
                .apply(springSecurity())
                .configureClient()
                .filter(basicAuthentication())
                .build();
    }

    @Test
    public void getAllPostsWithoutAuthWillBeOK() {
        client
                .get()
                .uri("/posts")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    public void getNonExistedPostsWithoutAuthShouldRetrun404() {
        client
                .get()
                .uri("/posts/xxx")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void addPostWithoutAuthWillReturn401() {
        client
                .post()
                .uri("/posts")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

//    @Test
//    public void testPostStream() {
//        client.mutate().responseTimeout(Duration.ofSeconds(60L)).build()
//                .get()
//                .uri("/posts")
//                .accept(MediaType.APPLICATION_STREAM_JSON)
//                .exchange()
//                .expectStatus().isOk();
//    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void postCrudOperations() {
        int randomInt = new Random().nextInt(Integer.MAX_VALUE);
        String title = "Post test " + randomInt;
        FluxExchangeResult<Void> postResult = client
                .post()
                .uri("/posts")
                .body(BodyInserters.fromObject(Post.builder().title(title).content("content of " + title).build()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .returnResult(Void.class);

        URI location = postResult.getResponseHeaders().getLocation();
        log.debug("post header location:" + location);
        assertNotNull(location);

        EntityExchangeResult<byte[]> getResult = client
                .get()
                .uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.title").isEqualTo(title)
                .returnResult();

        String getPost = new String(getResult.getResponseBody());
        assertTrue(getPost.contains(title));

        client
                .delete()
                .uri(location)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
    }


}
