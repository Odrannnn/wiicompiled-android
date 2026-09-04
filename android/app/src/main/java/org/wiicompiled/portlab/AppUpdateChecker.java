package org.wiicompiled.portlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-demand, bounded update check against this app's official GitHub releases. */
final class AppUpdateChecker {
    private static final String API =
        "https://api.github.com/repos/Odrannnn/wiicompiled-android/releases?per_page=20";
    private static final String RELEASE_PREFIX =
        "https://github.com/Odrannnn/wiicompiled-android/releases/";
    private static final int MAX_RESPONSE = 256 * 1024;
    private static final Pattern VERSION = Pattern.compile(
        "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-alpha\\.(\\d+))?$", Pattern.CASE_INSENSITIVE);

    record Result(String currentVersion, String latestVersion, String releaseUrl, boolean updateAvailable) { }

    static Result check() throws IOException {
        HttpURLConnection connection = (HttpURLConnection)URI.create(API).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "WiiCompiled-Android/" + BuildConfig.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IOException("GitHub returned HTTP " + status);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_RESPONSE) throw new IOException("GitHub response is too large");
            String json;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (output.size() + read > MAX_RESPONSE)
                        throw new IOException("GitHub response is too large");
                    output.write(buffer, 0, read);
                }
                json = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            try { return parse(json); }
            catch (org.json.JSONException error) {
                throw new IOException("GitHub returned malformed release data", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Result parse(String json) throws IOException, org.json.JSONException {
        Version current = Version.parse(BuildConfig.VERSION_NAME);
        JSONArray releases = new JSONArray(json);
        Version best = current;
        String bestName = BuildConfig.VERSION_NAME;
        String bestUrl = RELEASE_PREFIX;
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.optJSONObject(index);
            if (release == null || release.optBoolean("draft", false)) continue;
            String tag = release.optString("tag_name", "");
            String url = release.optString("html_url", "");
            Version candidate = Version.tryParse(tag);
            if (candidate == null || !isOfficialReleaseUrl(url)) continue;
            if (candidate.compareTo(best) > 0) {
                best = candidate;
                bestName = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                bestUrl = url;
            }
        }
        return new Result(BuildConfig.VERSION_NAME, bestName, bestUrl, best.compareTo(current) > 0);
    }

    private static boolean isOfficialReleaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost())
                && value.startsWith(RELEASE_PREFIX);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record Version(int major, int minor, int patch, int channel, int alpha)
            implements Comparable<Version> {
        static Version parse(String value) throws IOException {
            Version parsed = tryParse(value);
            if (parsed == null) throw new IOException("Unsupported app version: " + value);
            return parsed;
        }
        static Version tryParse(String value) {
            Matcher match = VERSION.matcher(value == null ? "" : value.trim());
            if (!match.matches()) return null;
            try {
                boolean stable = match.group(4) == null;
                return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), stable ? 1 : 0,
                    stable ? 0 : Integer.parseInt(match.group(4)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        @Override public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            if (result == 0) result = Integer.compare(patch, other.patch);
            if (result == 0) result = Integer.compare(channel, other.channel);
            if (result == 0) result = Integer.compare(alpha, other.alpha);
            return result;
        }
    }
}
