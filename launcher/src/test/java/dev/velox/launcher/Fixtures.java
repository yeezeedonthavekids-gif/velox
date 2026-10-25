package dev.velox.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Fixtures {
    private Fixtures() {}

    /** A jar containing a fabric.mod.json with the given id (or none if id is null). */
    static byte[] modJar(String id, String filler) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            if (id != null) {
                z.putNextEntry(new ZipEntry("fabric.mod.json"));
                z.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\"}").getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("filler.txt"));
            z.write(filler.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    static String modrinthProject(String id, String slug, String title, String license, String... gameVersions) {
        StringBuilder gv = new StringBuilder();
        for (String v : gameVersions) gv.append(gv.length() == 0 ? "" : ",").append('"').append(v).append('"');
        return "{\"id\":\"" + id + "\",\"slug\":\"" + slug + "\",\"title\":\"" + title + "\",\"loaders\":[\"fabric\"],"
                + "\"game_versions\":[" + gv + "],\"license\":{\"id\":\"" + license + "\",\"name\":\"" + license + "\"}}";
    }

    static String modrinthVersion(String id, String projectId, String number, String type, String url, String filename,
                                  byte[] data, String deps) {
        return "{\"id\":\"" + id + "\",\"project_id\":\"" + projectId + "\",\"version_number\":\"" + number + "\","
                + "\"version_type\":\"" + type + "\",\"loaders\":[\"fabric\"],\"game_versions\":[\"26.3\"],"
                + "\"files\":[{\"url\":\"" + url + "\",\"filename\":\"" + filename + "\",\"primary\":true,\"size\":" + data.length + ","
                + "\"hashes\":{\"sha1\":\"" + MockServer.sha1(data) + "\",\"sha512\":\"" + MockServer.sha512(data) + "\"}}],"
                + "\"dependencies\":[" + (deps == null ? "" : deps) + "]}";
    }
}
