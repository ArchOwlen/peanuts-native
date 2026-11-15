package dev.peanuts.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads the shipped profile without starting the packer's native engine in the launcher process. */
final class RuntimeShaderMap {
    private RuntimeShaderMap() {}

    static boolean compilesAtRunTime(InputStream tools, String titleId) throws IOException {
        String name = "dm/games/" + titleId + "/shader_map.tsv";
        try (ZipInputStream zip = new ZipInputStream(tools)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!name.equals(e.getName())) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("#")) continue;
                    String[] cols = line.split("\t");
                    if (cols.length >= 5 && cols[4].startsWith("/glslc_capture/")) return true;
                }
                return false;
            }
        }
        throw new IOException("The app's shader profile is missing: " + titleId + ". Reinstall the app and tap Build again.");
    }
}
