package android.net;

import android.os.Parcel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

/** Host-test URI whose text can include deliberately malformed selections. */
public final class Task11bTestUri extends Uri {
    private final String raw;

    private Task11bTestUri(String raw) {
        this.raw = raw;
    }

    public static Task11bTestUri from(String raw) {
        return new Task11bTestUri(raw);
    }

    private URI parsedOrNull() {
        try {
            return new URI(raw);
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    @Override public Builder buildUpon() { return null; }
    @Override public String getAuthority() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getAuthority();
    }
    @Override public String getEncodedAuthority() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawAuthority();
    }
    @Override public String getEncodedFragment() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawFragment();
    }
    @Override public String getEncodedPath() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawPath();
    }
    @Override public String getEncodedQuery() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawQuery();
    }
    @Override public String getEncodedSchemeSpecificPart() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawSchemeSpecificPart();
    }
    @Override public String getEncodedUserInfo() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getRawUserInfo();
    }
    @Override public String getFragment() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getFragment();
    }
    @Override public String getHost() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getHost();
    }
    @Override public String getLastPathSegment() {
        List<String> segments = getPathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }
    @Override public String getPath() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getPath();
    }
    @Override public List<String> getPathSegments() {
        String path = getPath();
        if (path == null || path.isEmpty() || path.equals("/")) return Collections.emptyList();
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return List.of(trimmed.split("/"));
    }
    @Override public int getPort() {
        URI parsed = parsedOrNull();
        return parsed == null ? -1 : parsed.getPort();
    }
    @Override public String getQuery() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getQuery();
    }
    @Override public String getScheme() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getScheme();
    }
    @Override public String getSchemeSpecificPart() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getSchemeSpecificPart();
    }
    @Override public String getUserInfo() {
        URI parsed = parsedOrNull();
        return parsed == null ? null : parsed.getUserInfo();
    }
    @Override public boolean isHierarchical() {
        URI parsed = parsedOrNull();
        return parsed != null && !parsed.isOpaque();
    }
    @Override public boolean isRelative() {
        URI parsed = parsedOrNull();
        return parsed == null || !parsed.isAbsolute();
    }
    @Override public String toString() { return raw; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) { }
}
