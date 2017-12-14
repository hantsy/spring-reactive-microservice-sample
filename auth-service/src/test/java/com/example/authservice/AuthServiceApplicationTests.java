package com.example.authservice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;

import java.util.Map;
import java.util.function.Consumer;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.Credentials.basicAuthenticationCredentials;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.basicAuthentication;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public class AuthServiceApplicationTests {

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
	public void getUserInfoWithoutAuthWillReturn401(){
		client
				.get()
				.uri("/user")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	public void getUserInfoWithInvalidAuthWillReturn401(){
		client
				.get()
				.uri("/user")
				.attributes(invalidCredentials())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
	}


	@Test
	@WithMockUser
	public void getUserInfoWithAuthWillBeOk(){
		client
				.get()
				.uri("/user")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK);
	}

	@Test
	public void getUserInfoWithAuthWillBeOk2(){
		client
				.mutateWith(mockUser())
				.get()
				.uri("/user")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK);
	}

	@Test
	public void getUserInfoWithAuthWillBeOk3(){
		client
				.get()
				.uri("/user")
				.attributes(userCredentials())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK);
	}

	private Consumer<Map<String, Object>> userCredentials() {
		return basicAuthenticationCredentials("user", "password");
	}

	private Consumer<Map<String, Object>> invalidCredentials() {
		return basicAuthenticationCredentials("user", "INVALID");
	}

}
