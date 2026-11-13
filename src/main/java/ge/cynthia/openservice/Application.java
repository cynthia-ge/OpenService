package ge.cynthia.openservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws java.io.IOException {
        if (args.length > 0 && args[0].equals("setup")) {
            if (args.length > 2) throw new IllegalArgumentException("Use setup [directory]");
            System.out.println(
                    Setup.create(java.nio.file.Path.of(args.length == 2 ? args[1] : ".")));
            return;
        }
        SpringApplication.run(Application.class, args);
    }
}
