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

        try (var fos = new FileOutputStream(zip.toFile());
             var zos = new ZipOutputStream(fos)) {
            Files.walk(src).filter(Files::isRegularFile).forEach(p -> {
                String entryName = "cropfarm-resourcepack/"
                        + src.relativize(p).toString().replace('\\', '/');
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
