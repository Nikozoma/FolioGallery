# Folio Gallery

**Android gallery organizer that uses actual on-device directories.** Java + native Android APIs, no accounts, no cloud services, no tracking, no external libraries.

## What v0.1 does

- Display photos from Android MediaStore, with efficient lazy thumbnails and a full-screen viewer.
- Create **real directories** under `Internal storage/Pictures/Gallery Organizer/`, including nested directories.
- Rename folders and delete only folders confirmed completely empty.
- Browse folders and the photos physically in each directory.
- Select photos (tap **SELECT** or long-press a thumbnail), choose **MOVE**, choose any managed destination.
- Perform bulk physical moves by changing `MediaStore.MediaColumns.RELATIVE_PATH` (not copy/delete). Android asks for approval to modify the selected files. Maximum 100 files per batch in v0.1.
- Leave photos untouched if the user cancels. Report count of unsuccessful individual moves; unsuccessful files remain in their original location.

**Images only** in v0.1; videos, editing, slideshow, gestures/zoom, SD-card-specific destination handling, and undo are outside this first build.

## First run

1. Open Folio Gallery and grant full photo access. If you grant selected-photo access, you will see only the selected photos.
2. Open **MY FOLDERS → CHOOSE PICTURES**.
3. The Android system directory picker must be pointed at `Internal storage → Pictures`. Choose **Use this folder** and approve.
4. Folio creates `Pictures/Gallery Organizer`. This directory is real and visible in Samsung My Files and other file managers.
5. Tap **+ NEW FOLDER** (and enter a folder to create a nested child folder).
6. Go to **ALL PHOTOS**, select photos, tap **MOVE (N)**, choose a folder, and approve Android's write-access request.

The Pictures access grant is persistent. Root selection is limited to primary internal storage on purpose. Folder creation happens via Android's Storage Access Framework; moving an existing photo happens via MediaStore, so there are no broad "all files access" permissions.

## GitHub Actions build

The `.github/workflows/android-apk.yml` workflow builds on every push to `main` or `master` and can also be started manually from **Actions → Build Android APK → Run workflow**.

- Install JDK 17, Android SDK API 36, Gradle 8.13.
- Run `gradle :app:assembleDebug`.
- Under the successful workflow run's **Artifacts**, download `FolioGallery-debug-apk` (ZIP) and extract `app-debug.apk`.
- Copy the APK to an Android 11+ phone, and install it. This is a debug-signed APK for personal testing; no Play Store account is needed.

## Build locally

Requires Android SDK API 36, JDK 17, Gradle 8.13. Run:

```
gradle :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Data safety

- The application does not upload photos or access the internet (and does not ask for internet permission).
- Renaming or moving items changes their actual disk path; other apps syncing or referencing paths may be affected.
- Moving can fail for inaccessible items, unsupported providers, restricted locations, or cross-volume destinations; no copy/delete fallback runs.
- The app never deletes any photos. Deletion is restricted to **completely empty directories** and requires an explicit confirmation.
- **Test on a small set of disposable photos** before moving irreplaceable pictures. Builds have not been verified on a physical device.

## Android permissions

- `READ_EXTERNAL_STORAGE` on Android 11–12.
- `READ_MEDIA_IMAGES` on Android 13+.
- `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+ for partial gallery access.
- `ACTION_OPEN_DOCUMENT_TREE` with a persisted URI grant to `Pictures` for real folder creation/rename/empty deletion.
- `MediaStore.createWriteRequest()` for the specific photos selected for physical movement.

**No `MANAGE_EXTERNAL_STORAGE` required.**

## Engineering notes

The first release intentionally uses platform UI widgets and no dependency on Kotlin, Compose, external image libraries, or AndroidX. The app supports Android 11 and later (`minSdk 30`). The design is dark with mint accents, built to be simple to maintain and iterate.
