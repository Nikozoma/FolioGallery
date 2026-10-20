package com.folio.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, offline, non-destructive MediaStore + Storage Access Framework organizer. */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 40;
    private static final int REQ_TREE = 41;
    private static final int REQ_WRITE = 42;
    private static final String ROOT_NAME = "Gallery Organizer";
    private static final String ROOT_PATH = "Pictures/" + ROOT_NAME + "/";
    private static final int BG = Color.rgb(11, 13, 20);
    private static final int SURFACE = Color.rgb(27, 31, 42);
    private static final int ACCENT = Color.rgb(128, 231, 218);
    private static final int MUTED = Color.rgb(165, 177, 191);
    private static final int WHITE = Color.rgb(244, 248, 251);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(3);
    private final ArrayList<MediaItem> photos = new ArrayList<>();
    private final ArrayList<Object> displayed = new ArrayList<>();
    private final LinkedHashMap<String, MediaItem> selected = new LinkedHashMap<>();
    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private LinearLayout page;
    private LinearLayout actionBar;
    private TextView heading, subheading, allTab, foldersTab, footer;
    private GridView grid;
    private BaseAdapter adapter;
    private boolean folderMode = false;
    private boolean selectMode = false;
    private boolean operationInFlight = false;
    private Uri treeUri, rootDocUri, currentDocUri;
    private String currentPath = ROOT_PATH;
    private final ArrayList<FolderItem> breadcrumb = new ArrayList<>();
    private String destinationPath;
    private ArrayList<MediaItem> pendingMoves;
    private int loadSerial = 0;

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String label, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private TextView button(String label, boolean highlighted, View.OnClickListener callback) {
        TextView t = text(label, 13, highlighted ? BG : WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(9), dp(13), dp(9));
        t.setBackground(shape(highlighted ? ACCENT : SURFACE, 12));
        t.setOnClickListener(callback);
        return t;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        // Android 15+ edge-to-edge: account for system bars on all supported versions.
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            if (page != null) page.setPadding(0, top, 0, bottom);
            return insets;
        });
        buildLayout();
        String saved = getPreferences(MODE_PRIVATE).getString("picturesTreeUri", null);
        if (saved != null) {
            try {
                treeUri = Uri.parse(saved);
                locateAppRoot(false);
            } catch (Exception ignored) { treeUri = null; rootDocUri = null; }
        }
        render();
        if (hasPhotoAccess()) loadPhotos(); else showPermissionPrompt();
    }

    private void buildLayout() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        setContentView(page);
        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(18), dp(14), dp(18), dp(10));
        top.setOrientation(LinearLayout.VERTICAL);
        heading = text("FOLIO  /  GALLERY", 24, WHITE, true);
        top.addView(heading);
        subheading = text("YOUR PHOTOS. YOUR FOLDERS.", 11, MUTED, true);
        top.addView(subheading);
        page.addView(top);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(16), dp(4), dp(16), dp(8));
        allTab = button("ALL PHOTOS", true, v -> switchTab(false));
        foldersTab = button("MY FOLDERS", false, v -> switchTab(true));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(45), 1);
        tabLp.setMargins(dp(2), 0, dp(4), 0);
        tabs.addView(allTab, tabLp);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, dp(45), 1);
        tabLp2.setMargins(dp(4), 0, dp(2), 0);
        tabs.addView(foldersTab, tabLp2);
        page.addView(tabs);
        actionBar = new LinearLayout(this);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setPadding(dp(16), dp(4), dp(16), dp(12));
        page.addView(actionBar);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(4));
        grid.setHorizontalSpacing(dp(4));
        grid.setPadding(dp(12), 0, dp(12), dp(10));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Object item = displayed.get(position);
            if (item instanceof FolderItem) {
                if (selectMode) { toast("Finish selecting photos before opening folders."); return; }
                openFolder((FolderItem) item);
            } else {
                MediaItem p = (MediaItem) item;
                if (selectMode) togglePhoto(p);
                else { Intent intent = new Intent(this, PhotoViewerActivity.class);
                    intent.putExtra("uri", p.uri.toString()); intent.putExtra("name", p.name);
                    startActivity(intent); }
            }
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            Object item = displayed.get(position);
            if (item instanceof FolderItem) { folderOptions((FolderItem) item); return true; }
            selectMode = true; togglePhoto((MediaItem) item); return true;
        });
        page.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        footer = text("", 12, MUTED, false);
        footer.setPadding(dp(18), dp(7), dp(18), dp(12));
        page.addView(footer);
    }

    private void switchTab(boolean toFolders) {
        folderMode = toFolders;
        selectMode = false; selected.clear();
        if (toFolders && rootDocUri == null && treeUri != null) locateAppRoot(false);
        render();
    }

    private void render() {
        allTab.setBackground(shape(!folderMode ? ACCENT : SURFACE, 12));
        allTab.setTextColor(!folderMode ? BG : WHITE);
        foldersTab.setBackground(shape(folderMode ? ACCENT : SURFACE, 12));
        foldersTab.setTextColor(folderMode ? BG : WHITE);
        actionBar.removeAllViews();
        if (operationInFlight) {
            actionBar.addView(text("Moving photos… please keep the app open", 13, ACCENT, true));
        } else if (selectMode) {
            actionBar.addView(button("CANCEL", false, v -> {
                selectMode = false; selected.clear(); render();
            }));
            actionBar.addView(gap(8));
            actionBar.addView(button("MOVE (" + selected.size() + ")", !selected.isEmpty(), v -> showMoveTargets()));
        } else {
            if (folderMode && currentDocUri != null && !currentDocUri.equals(rootDocUri)) {
                actionBar.addView(button("‹ BACK", false, v -> goUp()));
                actionBar.addView(gap(8));
            }
            if (folderMode) actionBar.addView(button(rootDocUri == null ? "CHOOSE PICTURES" : "+ NEW FOLDER", true, v -> {
                if (rootDocUri == null) choosePictures(); else askNewFolder();
            }));
            else actionBar.addView(button("SELECT", false, v -> { selectMode = true; render(); }));
            if (folderMode && rootDocUri != null) {
                actionBar.addView(gap(8));
                actionBar.addView(button("CHANGE ROOT", false, v -> choosePictures()));
            }
            if (!hasPhotoAccess()) {
                actionBar.addView(gap(8));
                actionBar.addView(button("PHOTO ACCESS", false, v -> requestPhotoAccess()));
            }
        }
        displayed.clear();
        if (folderMode) {
            String where = (currentDocUri != null && !currentDocUri.equals(rootDocUri))
                    ? currentPath.substring(ROOT_PATH.length()).replaceAll("/$", "") : "My folders";
            subheading.setText("PICTURES / " + ROOT_NAME.toUpperCase(Locale.ROOT) + (where.equals("My folders") ? "" : " / " + where.toUpperCase(Locale.ROOT)));
            if (rootDocUri != null && currentDocUri != null) {
                try { displayed.addAll(listFolders(currentDocUri)); }
                catch (Exception ex) { toast("Folder access expired. Choose Pictures again."); }
                for (MediaItem p : photos) if (p.relativePath.equalsIgnoreCase(currentPath)) displayed.add(p);
            }
        } else {
            subheading.setText("YOUR PHOTOS. YOUR FOLDERS.");
            displayed.addAll(photos);
        }
        heading.setText(selectMode ? selected.size() + " SELECTED" : "FOLIO  /  GALLERY");
        adapter.notifyDataSetChanged();
        int countPhotos = 0, countDirs = 0;
        for (Object x : displayed) { if (x instanceof FolderItem) countDirs++; else countPhotos++; }
        if (folderMode && rootDocUri == null) footer.setText("Choose the Pictures directory to create real folders.");
        else if (displayed.isEmpty()) footer.setText(folderMode ? "No items here. Create your first folder." : "No photos found. Check photo permissions.");
        else footer.setText(countPhotos + " PHOTOS    ·    " + countDirs + " FOLDERS" +
                (hasLimitedAccess() ? "    ·    LIMITED PHOTO ACCESS" : ""));
    }

    private View gap(int width) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return v; }

    private boolean hasLimitedAccess() {
        return Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasPhotoAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || hasLimitedAccess();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void showPermissionPrompt() {
        new AlertDialog.Builder(this).setTitle("Show your gallery")
                .setMessage("Allow photo access to browse and organize images already on your phone. For the complete gallery, choose Allow all photos.")
                .setPositiveButton("Continue", (d, w) -> requestPhotoAccess())
                .setNegativeButton("Later", null).show();
    }
    private void requestPhotoAccess() {
        if (Build.VERSION.SDK_INT >= 34) requestPermissions(new String[]{ Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }, REQ_PERMISSION);
        else if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{ Manifest.permission.READ_MEDIA_IMAGES }, REQ_PERMISSION);
        else requestPermissions(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_PERMISSION) {
            if (hasPhotoAccess()) loadPhotos(); else render();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (page != null && hasPhotoAccess() && !operationInFlight) loadPhotos();
    }
    private void loadPhotos() {
        final int serial = ++loadSerial;
        io.execute(() -> {
            ArrayList<MediaItem> found = new ArrayList<>();
            Uri source = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] columns = { MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED };
            try (Cursor c = getContentResolver().query(source, columns, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) while (c.moveToNext()) {
                    long id = c.getLong(0);
                    found.add(new MediaItem(ContentUris.withAppendedId(source, id),
                            c.getString(1), c.getString(2), c.getLong(3)));
                }
            } catch (SecurityException ignored) { /* Permission can change mid-query. */ }
            catch (Exception ex) { main.post(() -> toast("Could not load photos: " + ex.getMessage())); }
            main.post(() -> {
                if (serial != loadSerial || isFinishing() || isDestroyed()) return;
                photos.clear(); photos.addAll(found);
                // A media rescan can invalidate selections, so retain only still-visible records.
                LinkedHashMap<String, MediaItem> active = new LinkedHashMap<>();
                for (MediaItem p : found) if (selected.containsKey(p.uri.toString())) active.put(p.uri.toString(), p);
                selected.clear(); selected.putAll(active);
                render();
            });
        });
    }

    // SAF folder grant allows creating, renaming, and removing *empty* physical directories.
    private void choosePictures() {
        new AlertDialog.Builder(this).setTitle("Choose your Pictures folder")
                .setMessage("In the Android file picker, select Internal storage → Pictures, then tap Use this folder. Folio will create Pictures/" + ROOT_NAME + ".")
                .setPositiveButton("Open picker", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, REQ_TREE);
                }).setNegativeButton("Cancel", null).show();
    }
    private String docId(Uri uri) { return DocumentsContract.getDocumentId(uri); }
    private String relativePathOf(Uri document) {
        String id = docId(document);
        if (!id.startsWith("primary:Pictures/" + ROOT_NAME)) throw new IllegalArgumentException("Folder must be inside Pictures/" + ROOT_NAME);
        return id.substring("primary:".length()).replaceAll("/?$", "/");
    }
    private Uri resolveDirectory(Uri parent, String name, boolean create) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId(parent));
        try (Cursor c = getContentResolver().query(children, new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                if (name.equals(c.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
            }
        }
        return create ? DocumentsContract.createDocument(getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name) : null;
    }
    private void locateAppRoot(boolean create) {
        if (treeUri == null) return;
        Uri pictures = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        rootDocUri = resolveDirectory(pictures, ROOT_NAME, create);
        currentDocUri = rootDocUri;
        currentPath = ROOT_PATH;
        breadcrumb.clear();
    }
    private List<FolderItem> listFolders(Uri parent) {
        ArrayList<FolderItem> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId(parent));
        try (Cursor c = getContentResolver().query(children, new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) continue;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                String path = relativePathOf(uri);
                int count = 0;
                for (MediaItem p : photos) if (p.relativePath.startsWith(path)) count++;
                result.add(new FolderItem(uri, c.getString(1), path, count));
            }
        }
        Collections.sort(result, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }
    private void openFolder(FolderItem f) {
        breadcrumb.add(new FolderItem(currentDocUri, "..", currentPath, 0));
        currentDocUri = f.uri; currentPath = f.relativePath; render();
    }
    private void goUp() {
        if (breadcrumb.isEmpty()) { currentDocUri = rootDocUri; currentPath = ROOT_PATH; }
        else {
            FolderItem previous = breadcrumb.remove(breadcrumb.size() - 1);
            currentDocUri = previous.uri; currentPath = previous.relativePath;
        }
        render();
    }
    @Override public void onBackPressed() {
        if (operationInFlight) { toast("Please finish the current move."); return; }
        if (selectMode) { selectMode = false; selected.clear(); render(); }
        else if (folderMode && currentDocUri != null && !currentDocUri.equals(rootDocUri)) goUp();
        else super.onBackPressed();
    }
    private void askNewFolder() {
        if (currentDocUri == null) { choosePictures(); return; }
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setHint("e.g. Trips, Pets, Projects");
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(22), dp(4), dp(22), 0);
        container.addView(field);
        new AlertDialog.Builder(this).setTitle("Create real folder").setView(container)
                .setPositiveButton("Create", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
                        toast("Use a folder name without slashes."); return;
                    }
                    try {
                        if (resolveDirectory(currentDocUri, name, false) != null) { toast("That folder already exists."); return; }
                        Uri created = DocumentsContract.createDocument(getContentResolver(), currentDocUri,
                                DocumentsContract.Document.MIME_TYPE_DIR, name);
                        if (created == null) throw new IllegalStateException("Android did not create a folder.");
                        toast("Created " + name); render();
                    } catch (Exception ex) { toast("Could not create folder: " + ex.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }
    private void folderOptions(FolderItem folder) {
        new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{ "Rename folder", "Delete (empty folders only)" },
                (dialog, which) -> { if (which == 0) renameFolder(folder); else deleteEmptyFolder(folder); }).show();
    }
    private void renameFolder(FolderItem folder) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(folder.name);
        new AlertDialog.Builder(this).setTitle("Rename physical folder").setView(field)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
                        toast("Invalid folder name."); return;
                    }
                    try {
                        Uri renamed = DocumentsContract.renameDocument(getContentResolver(), folder.uri, name);
                        if (renamed == null) throw new IllegalStateException("Rename denied");
                        loadPhotos();
                    } catch (Exception ex) { toast("Could not rename: " + ex.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }
    private void deleteEmptyFolder(FolderItem f) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId(f.uri));
        try (Cursor c = getContentResolver().query(children,
                new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null)) {
            if (c == null || c.getCount() > 0) { toast("Only completely empty folders can be deleted."); return; }
        } catch (Exception ex) { toast("Could not check folder contents."); return; }
        new AlertDialog.Builder(this).setTitle("Delete empty folder?")
                .setMessage(f.name + " is empty. Remove this folder from device storage?")
                .setPositiveButton("Delete", (d, w) -> {
                    try { if (DocumentsContract.deleteDocument(getContentResolver(), f.uri)) render();
                        else toast("Android couldn't delete that folder."); }
                    catch (Exception ex) { toast("Delete failed: " + ex.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void togglePhoto(MediaItem p) {
        if (selected.containsKey(p.uri.toString())) selected.remove(p.uri.toString());
        else selected.put(p.uri.toString(), p);
        render();
    }
    private void collectDestinations(Uri directory, String prefix, List<FolderItem> targets, int depth) {
        if (depth > 12 || targets.size() >= 160) return;
        for (FolderItem folder : listFolders(directory)) {
            targets.add(new FolderItem(folder.uri, prefix + folder.name, folder.relativePath, folder.count));
            collectDestinations(folder.uri, prefix + "   ", targets, depth + 1);
        }
    }
    private void showMoveTargets() {
        if (selected.isEmpty()) { toast("Select some photos first."); return; }
        if (selected.size() > 100) { toast("Move up to 100 photos at a time in this version."); return; }
        if (rootDocUri == null) { selectMode = false; selected.clear(); folderMode = true; render(); choosePictures(); return; }
        ArrayList<FolderItem> targets = new ArrayList<>();
        targets.add(new FolderItem(rootDocUri, ROOT_NAME + " (root)", ROOT_PATH, 0));
        try { collectDestinations(rootDocUri, "", targets, 0); }
        catch (Exception ex) { toast("Cannot read folder list: " + ex.getMessage()); return; }
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = targets.get(i).name;
        new AlertDialog.Builder(this).setTitle("Move " + selected.size() + " photos into…")
                .setItems(labels, (d, chosen) -> confirmMove(targets.get(chosen)))
                .setNegativeButton("Cancel", null).show();
    }
    private void confirmMove(FolderItem target) {
        ArrayList<MediaItem> toMove = new ArrayList<>();
        for (MediaItem p : selected.values()) if (!p.relativePath.equalsIgnoreCase(target.relativePath)) toMove.add(p);
        if (toMove.isEmpty()) { toast("Those photos are already in that folder."); return; }
        new AlertDialog.Builder(this).setTitle("Physically move " + toMove.size() + " photos?")
                .setMessage("Original images will move to " + target.relativePath + " on your phone. Other gallery apps and file managers will see the new location. No duplicate copies will be made.")
                .setPositiveButton("Continue", (d, w) -> requestMoveAuthorization(toMove, target.relativePath))
                .setNegativeButton("Cancel", null).show();
    }
    private void requestMoveAuthorization(ArrayList<MediaItem> files, String target) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (MediaItem p : files) uris.add(p.uri);
            PendingIntent consent = MediaStore.createWriteRequest(getContentResolver(), uris);
            pendingMoves = files;
            destinationPath = target;
            startIntentSenderForResult(consent.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (Exception ex) { pendingMoves = null; destinationPath = null;
            toast("Android did not grant access: " + ex.getMessage()); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri picked = data.getData();
            try {
                if (!"primary:Pictures".equals(DocumentsContract.getTreeDocumentId(picked))) {
                    toast("Please select Internal storage → Pictures (not a subfolder)."); return;
                }
                getContentResolver().takePersistableUriPermission(picked,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                treeUri = picked;
                locateAppRoot(true);
                if (rootDocUri == null) throw new IllegalStateException("Could not create root folder");
                getPreferences(MODE_PRIVATE).edit().putString("picturesTreeUri", picked.toString()).apply();
                folderMode = true; selectMode = false; selected.clear();
                toast("Ready: " + ROOT_PATH);
                render();
            } catch (Exception ex) { treeUri = null; rootDocUri = null; currentDocUri = null;
                toast("Folder setup failed: " + ex.getMessage()); render(); }
            return;
        }
        if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK && pendingMoves != null && destinationPath != null) {
                performMoves(new ArrayList<>(pendingMoves), destinationPath);
            } else { toast("Move canceled. Your photos were not changed."); }
            pendingMoves = null; destinationPath = null;
        }
    }
    private void performMoves(ArrayList<MediaItem> batch, String dest) {
        operationInFlight = true; render();
        io.execute(() -> {
            int success = 0;
            ArrayList<String> failed = new ArrayList<>();
            for (MediaItem p : batch) {
                try {
                    ContentValues change = new ContentValues();
                    change.put(MediaStore.MediaColumns.RELATIVE_PATH, dest);
                    if (getContentResolver().update(p.uri, change, null, null) == 1) success++;
                    else failed.add(p.name);
                } catch (Exception ex) { failed.add(p.name); }
            }
            int moved = success;
            main.post(() -> {
                operationInFlight = false; selectMode = false; selected.clear();
                toast("Moved " + moved + " of " + batch.size() + " photos" +
                        (failed.isEmpty() ? "." : ". " + failed.size() + " stayed in their original folders."));
                loadPhotos();
            });
        });
    }

    private class GalleryAdapter extends BaseAdapter {
        @Override public int getCount() { return displayed.size(); }
        @Override public Object getItem(int position) { return displayed.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Object item = displayed.get(position);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int side = (screenWidth - dp(34)) / 3;
            FrameLayout frame = new FrameLayout(MainActivity.this);
            frame.setLayoutParams(new android.widget.AbsListView.LayoutParams(side, side));
            frame.setBackground(shape(SURFACE, 10));
            frame.setClipToOutline(true);
            if (item instanceof FolderItem) {
                FolderItem folder = (FolderItem) item;
                LinearLayout stack = new LinearLayout(MainActivity.this);
                stack.setOrientation(LinearLayout.VERTICAL); stack.setGravity(Gravity.CENTER);
                stack.setPadding(dp(5), dp(7), dp(5), dp(6));
                TextView glyph = text("▰", 39, ACCENT, true);
                glyph.setGravity(Gravity.CENTER);
                stack.addView(glyph);
                TextView label = text(folder.name, 12, WHITE, true);
                label.setMaxLines(2); label.setGravity(Gravity.CENTER);
                stack.addView(label);
                TextView meta = text(folder.count + " photos", 10, MUTED, false);
                meta.setGravity(Gravity.CENTER);
                stack.addView(meta);
                frame.addView(stack, new FrameLayout.LayoutParams(-1, -1));
            } else {
                MediaItem photo = (MediaItem) item;
                ImageView picture = new ImageView(MainActivity.this);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                picture.setBackgroundColor(SURFACE);
                picture.setTag(photo.uri.toString());
                frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
                String key = photo.uri.toString();
                Bitmap ready = bitmapCache.get(key);
                if (ready != null && !ready.isRecycled()) picture.setImageBitmap(ready);
                else thumbnails.execute(() -> {
                    try {
                        Bitmap loaded = getContentResolver().loadThumbnail(photo.uri, new Size(360, 360), null);
                        if (loaded != null) {
                            bitmapCache.put(key, loaded);
                            main.post(() -> {
                                if (key.equals(picture.getTag())) picture.setImageBitmap(loaded);
                            });
                        }
                    } catch (Exception ignored) { /* Provider access can be revoked mid-load. */ }
                });
                if (selected.containsKey(key)) {
                    View tint = new View(MainActivity.this);
                    tint.setBackgroundColor(0x7750C9B8);
                    frame.addView(tint, new FrameLayout.LayoutParams(-1, -1));
                    TextView tick = text("✓", 25, WHITE, true);
                    tick.setGravity(Gravity.CENTER);
                    tick.setBackground(shape(0xDD246A64, 25));
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END);
                    lp.setMargins(0, dp(7), dp(7), 0);
                    frame.addView(tick, lp);
                }
            }
            return frame;
        }
    }

    @Override protected void onDestroy() {
        io.shutdown(); thumbnails.shutdown();
        super.onDestroy();
    }
}
