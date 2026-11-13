package ge.cynthia.openservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

public final class Setup {
    private Setup() {}

    public static String create(Path directory) throws java.io.IOException {
        Path destination = directory.resolve(".env");
        StringBuilder values = new StringBuilder();
        for (String name : List.of("DB_PASSWORD", "SECRET_KEY", "ADMIN_PASSWORD"))
            values.append(name)
                    .append('=')
                    .append(
                            HexFormat.of()
                                    .formatHex(Base64.getUrlDecoder().decode(Passwords.token())))
                    .append('\n');
        try (var channel =
                Files.newByteChannel(
                        destination,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")))) {
            var bytes = StandardCharsets.UTF_8.encode(values.toString());
            while (bytes.hasRemaining()) channel.write(bytes);
            return "Created .env with private local credentials. Username: admin. Read"
                    + " ADMIN_PASSWORD in .env.";
        } catch (FileAlreadyExistsException existing) {
            return ".env already exists; preserved it.";
        }
    }
}
