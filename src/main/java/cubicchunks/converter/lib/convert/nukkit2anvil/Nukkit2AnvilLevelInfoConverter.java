package cubicchunks.converter.lib.convert.nukkit2anvil;

import cubicchunks.converter.lib.convert.LevelInfoConverter;
import cubicchunks.converter.lib.convert.data.AnvilChunkData;
import cubicchunks.converter.lib.convert.data.NukkitChunkData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author DaPorkchop_
 */
public class Nukkit2AnvilLevelInfoConverter implements LevelInfoConverter<NukkitChunkData, AnvilChunkData> {
    private final Path srcDir;
    private final Path dstDir;

    public Nukkit2AnvilLevelInfoConverter(Path srcDir, Path dstDir) {
        this.srcDir = srcDir;
        this.dstDir = dstDir;
    }

    @Override
    public void convert() throws IOException {
        Files.copy(this.srcDir.resolve("level.dat"), this.dstDir.resolve("level.dat"));
        if (Files.exists(this.srcDir.resolve("offset.txt")))    {
            Files.copy(this.srcDir.resolve("offset.txt"), this.dstDir.resolve("offset.txt"));
        }
    }
}
