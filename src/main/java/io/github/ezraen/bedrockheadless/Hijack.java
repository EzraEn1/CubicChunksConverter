package io.github.ezraen.bedrockheadless;

import cubicchunks.converter.lib.*;
import cubicchunks.converter.lib.Registry;
import cubicchunks.converter.lib.convert.ChunkDataConverter;
import cubicchunks.converter.lib.convert.ChunkDataReader;
import cubicchunks.converter.lib.convert.ChunkDataWriter;
import cubicchunks.converter.lib.convert.LevelInfoConverter;
import cubicchunks.converter.lib.convert.WorldConverter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import com.flowpowered.nbt.*;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Jar needs 2 arguments: <path to source world> <output path>");
        }
        String src = args[0];
        String out = args[1];
        File Height = new File(src + "//zero_offset.txt");
        BufferedReader br = new BufferedReader(new FileReader(Height));
        //int alt = false;
        int alt = Integer.parseInt(br.readLine())/256;
        br.close();

        Path tempDir = Paths.get(src).getParent().resolve("temp");
        File temp = tempDir.toFile();
        if (temp.mkdir()) {
            System.out.println("Creating /temp folder for 1st and 2nd conversion.");
        }


        WorldConverter<?, ?> converter = new WorldConverter<Object, Object>(
                (LevelInfoConverter<Object, Object>)Registry.getLevelConverter("CubicChunks", "Anvil (layered)").apply(
                        Paths.get(src),
                        tempDir
                ),
                (ChunkDataReader<Object>)Registry.getReader("CubicChunks").apply(Paths.get(src)),
                (ChunkDataConverter<Object, Object>)Registry.getConverter("CubicChunks", "Anvil (layered)").get(),
                (ChunkDataWriter<Object>)Registry.getWriter("Anvil (layered)").apply(tempDir));
        converter.convert(new IProgressListener() {
            public void update(Void aVoid) {
            }

            public ErrorHandleResult error(Throwable throwable) {
                return null;
            }
        });
        System.out.println("Conversion 1 ended");

        String src2 = tempDir + "/layer [" + 256 * alt + ", " + (256 * alt + 256) + "]";
        WorldConverter<?, ?> converter2 = new WorldConverter<>(
                (LevelInfoConverter<Object, Object>)Registry.getLevelConverter("Anvil", "Nukkit").apply(
                        Paths.get(src2),
                        Paths.get(out)
                ),
                (ChunkDataReader<Object>)Registry.getReader("Anvil").apply(Paths.get(src2)),
                (ChunkDataConverter<Object, Object>)Registry.getConverter("Anvil", "Nukkit").get(),
                (ChunkDataWriter<Object>)Registry.getWriter("Nukkit").apply(Paths.get(out)));
        converter2.convert(new IProgressListener() {
            public void update(Void aVoid) {
            }

            public ErrorHandleResult error(Throwable throwable) {
                return null;
            }
        });
        System.out.println("Conversion 2 ended");
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        System.out.println("Done");
    }
}
