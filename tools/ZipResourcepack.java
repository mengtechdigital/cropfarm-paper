import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Zips build/cropfarm-resourcepack/ → build/cropfarm-resourcepack.zip.
 * Run after tools/GenerateTextures.java.
 */
public class ZipResourcepack {
    public static void main(String[] args) throws IOException {
        Path src = Paths.get("build/cropfarm-resourcepack");
        Path zip = Paths.get("build/cropfarm-resourcepack.zip");
        if (!Files.isDirectory(src)) {
            System.err.println("Missing " + src + " — run tools/GenerateTextures.java first.");
            System.exit(1);
        }
        Files.deleteIfExists(zip);

        // IMPORTANT: pack.mcmeta and assets/ must be at the ZIP ROOT — server
        // -pushed Minecraft resource packs reject zips that wrap everything
        // in a top-level folder (local manual installs are more forgiving).
        try (var fos = new FileOutputStream(zip.toFile());
             var zos = new ZipOutputStream(fos)) {
            Files.walk(src).filter(Files::isRegularFile).forEach(p -> {
                String entryName = src.relativize(p).toString().replace('\\', '/');
                try {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        System.out.println("Wrote " + zip + " (" + Files.size(zip) + " bytes)");
    }
}
