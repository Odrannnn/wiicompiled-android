package org.wiicompiled.portlab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded Android client for WheelWizard's GameBanana catalogue contract. */
final class GameBananaClient {
    private static final String API = "https://gamebanana.com/apiv12";
    private static final int MAX_JSON = 4 * 1024 * 1024, MAX_REDIRECTS = 5;
    private static final long MAX_DOWNLOAD = 1024L * 1024 * 1024;

    record CatalogMod(int id, String name, String version, String author, String profileUrl,
                      boolean usesPatches) { }
    record ModFile(String name, long size, String url) { }
    record Details(int id, String name, String version, String author, String description,
                   long downloads, List<ModFile> files) { }
    interface Progress { void update(long received, long total); }

    static List<CatalogMod> search(String term, int page) throws IOException {
        String query = term == null || term.isBlank() ? "Mod" : term.trim();
        if (query.length() < 2) query = "Mod";
        String url = API + "/Util/Search/Results?_sSearchString="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&_idGameRow=5896&_sModelName=Mod&_nPage="
            + Math.max(page, 1);
        JSONObject root = parseJson(readJson(url)); JSONArray records = root.optJSONArray("_aRecords");
        if (records == null) return Collections.emptyList();
        List<CatalogMod> result = new ArrayList<>();
        for (int index = 0; index < records.length() && result.size() < 50; index++) {
            JSONObject item = records.optJSONObject(index); if (item == null) continue;
            if (!"Mod".equalsIgnoreCase(item.optString("_sModelName", "Mod"))
                || item.optBoolean("_bHasContentRatings", false)) continue;
            JSONObject submitter = item.optJSONObject("_aSubmitter");
            result.add(new CatalogMod(item.optInt("_idRow", -1), clean(item.optString("_sName")),
                clean(item.optString("_sVersion")), clean(submitter == null ? "" : submitter.optString("_sName")),
                item.optString("_sProfileUrl"), hasPatchTag(item.optJSONArray("_aTags"))));
        }
        return Collections.unmodifiableList(result);
    }

    static Details details(int id) throws IOException {
        if (id <= 0) throw new IOException("Invalid catalogue mod ID");
        JSONObject item = parseJson(readJson(API + "/Mod/" + id + "/ProfilePage"));
        JSONObject submitter = item.optJSONObject("_aSubmitter");
        List<ModFile> files = new ArrayList<>();
        JSONArray listed = item.optJSONArray("_aFiles");
        if (listed == null || listed.length() == 0) listed = item.optJSONArray("_aArchivedFiles");
        if (listed != null) for (int index = 0; index < listed.length(); index++) {
            JSONObject file = listed.optJSONObject(index); if (file == null) continue;
            String name = clean(file.optString("_sFile")), url = file.optString("_sDownloadUrl");
            if (!name.toLowerCase(Locale.US).endsWith(".zip") || !isHttps(url)) continue;
            files.add(new ModFile(name, Math.max(file.optLong("_nFilesize", -1), -1), url));
        }
        return new Details(item.optInt("_idRow", id), clean(item.optString("_sName")),
            clean(item.optString("_sVersion")), clean(submitter == null ? "" : submitter.optString("_sName")),
            cleanDescription(item.optString("_sText")), item.optLong("_nDownloadCount", 0),
            Collections.unmodifiableList(files));
    }

    static void download(ModFile remote, File destination, Progress progress) throws IOException {
        downloadUrl(remote.url(), destination, MAX_DOWNLOAD, progress);
    }

    static void downloadUrl(String url, File destination, long maximum, Progress progress) throws IOException {
        HttpURLConnection connection = open(url);
        long declared = connection.getContentLengthLong();
        if (declared > maximum) { connection.disconnect(); throw new IOException("Download exceeds its safety limit"); }
        File parent = destination.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create download cache");
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024]; long received = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                received += read; if (received > maximum) throw new IOException("Download exceeds its safety limit");
                output.write(buffer, 0, read); if (progress != null) progress.update(received, declared);
            }
        } catch (IOException error) { destination.delete(); throw error; }
        finally { connection.disconnect(); }
    }

    static long contentLength(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try { return connection.getContentLengthLong(); }
        finally { connection.disconnect(); }
    }

    private static String readJson(String url) throws IOException {
        return fetchText(url, MAX_JSON);
    }

    static String fetchText(String url, int maximum) throws IOException {
        HttpURLConnection connection = open(url);
        if (connection.getContentLengthLong() > maximum) { connection.disconnect(); throw new IOException("Server response is too large"); }
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                if (output.size() + read > maximum) throw new IOException("Server response is too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    private static JSONObject parseJson(String text) throws IOException {
        try { return new JSONObject(text); }
        catch (org.json.JSONException error) { throw new IOException("Catalogue returned malformed JSON", error); }
    }

    private static HttpURLConnection open(String address) throws IOException {
        String current = address;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!isHttps(current)) throw new IOException("Only HTTPS catalogue URLs are allowed");
            HttpURLConnection connection = (HttpURLConnection)URI.create(current).toURL().openConnection();
            connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept", "application/json, application/zip");
            connection.setRequestProperty("User-Agent", "WiiCompiled-Android-Port/0.1");
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return connection;
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("Download redirect has no location");
                current = URI.create(current).resolve(location).toString(); continue;
            }
            connection.disconnect(); throw new IOException("Catalogue server returned HTTP " + status);
        }
        throw new IOException("Too many catalogue redirects");
    }

    private static boolean isHttps(String value) {
        try { return "https".equalsIgnoreCase(URI.create(value).getScheme()); }
        catch (RuntimeException ignored) { return false; }
    }
    private static boolean hasPatchTag(JSONArray tags) {
        if (tags == null) return false;
        for (int index = 0; index < tags.length(); index++) {
            Object value = tags.opt(index); String title = value instanceof JSONObject
                ? ((JSONObject)value).optString("_sTitle") : String.valueOf(value);
            String normalized = title.split(":", 2)[0].trim();
            if (normalized.equalsIgnoreCase("patch") || normalized.equalsIgnoreCase("patches")) return true;
        }
        return false;
    }
    private static String clean(String value) {
        if (value == null) return ""; StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length() && result.length() < 240; index++)
            if (!Character.isISOControl(value.charAt(index))) result.append(value.charAt(index));
        return result.toString().trim();
    }
    private static String cleanDescription(String html) {
        return clean(html == null ? "" : html.replaceAll("<[^>]+>", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")).replaceAll("\\s+", " ");
    }
}
