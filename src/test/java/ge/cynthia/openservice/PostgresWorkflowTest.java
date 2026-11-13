package ge.cynthia.openservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class PostgresWorkflowTest extends WorkflowSupport {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
        registry.add("DATABASE_URL", DockerDatabase::url);
    }
}
