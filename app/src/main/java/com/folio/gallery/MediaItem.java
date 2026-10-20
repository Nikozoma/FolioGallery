package com.folio.gallery;

import android.net.Uri;

final class MediaItem {
    final Uri uri;
    final String name;
    final String relativePath;
    final long added;

    MediaItem(Uri uri, String name, String relativePath, long added) {
        this.uri = uri;
        this.name = name;
        this.relativePath = relativePath == null ? "" : relativePath;
        this.added = added;
    }
}
