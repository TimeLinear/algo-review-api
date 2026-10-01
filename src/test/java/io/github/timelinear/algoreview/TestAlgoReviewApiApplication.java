package io.github.timelinear.algoreview;

import org.springframework.boot.SpringApplication;

public class TestAlgoReviewApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(AlgoReviewApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
