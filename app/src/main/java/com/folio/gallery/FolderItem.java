package com.folio.gallery;

import android.net.Uri;

final class FolderItem {
    final Uri uri;
    final String name;
    final String relativePath;
    final int count;

    FolderItem(Uri uri, String name, String relativePath, int count) {
        this.uri = uri;
        this.name = name;
        this.relativePath = relativePath;
        this.count = count;
    }
}
