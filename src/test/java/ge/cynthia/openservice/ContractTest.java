package ge.cynthia.openservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = "--spring.main.banner-mode=off",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ContractTest extends ContractSupport {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
    }
}
