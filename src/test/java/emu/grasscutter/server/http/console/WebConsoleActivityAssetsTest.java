package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WebConsoleActivityAssetsTest {
    @Test void originalRelativePathsStayInTheirArchiveRoot() {
        var root = Path.of("local/test-assets").toAbsolutePath().normalize();
        assertEquals(root.resolve("client/a.blk"), WebConsoleActivityAssets.inside(root,"client/a.blk"));
        assertThrows(IllegalArgumentException.class, () -> WebConsoleActivityAssets.inside(root,"../outside.blk"));
        assertThrows(IllegalArgumentException.class, () -> WebConsoleActivityAssets.inside(root,root.resolve("outside.blk").toString()));
        assertThrows(IllegalArgumentException.class, () -> WebConsoleActivityAssets.inside(root,""));
    }
}
