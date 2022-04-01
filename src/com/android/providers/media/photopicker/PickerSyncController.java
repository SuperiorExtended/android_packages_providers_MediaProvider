/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.photopicker;

import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.content.ContentResolver.EXTRA_HONORED_ARGS;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;

import android.annotation.IntDef;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {
    private static final String TAG = "PickerSyncController";
    private static final String PREFS_KEY_CLOUD_PROVIDER_AUTHORITY = "cloud_provider_authority";
    private static final String PREFS_KEY_CLOUD_PROVIDER_PKGNAME = "cloud_provider_pkg_name";
    private static final String PREFS_KEY_CLOUD_PROVIDER_UID = "cloud_provider_uid";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    private static final String PICKER_USER_PREFS_FILE_NAME = "picker_user_prefs";
    public static final String PICKER_SYNC_PREFS_FILE_NAME = "picker_sync_prefs";
    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";

    private static final String DEFAULT_CLOUD_PROVIDER_AUTHORITY = null;
    private static final String DEFAULT_CLOUD_PROVIDER_PKGNAME = null;
    private static final int DEFAULT_CLOUD_PROVIDER_UID = -1;
    private static final long DEFAULT_SYNC_DELAY_MS =
            PickerDbFacade.getDefaultPickerDbSyncDelayMs();

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_MEDIA_INCREMENTAL = 1;
    private static final int SYNC_TYPE_MEDIA_FULL = 2;
    private static final int SYNC_TYPE_MEDIA_RESET = 3;

    @IntDef(flag = false, prefix = { "SYNC_TYPE_" }, value = {
                SYNC_TYPE_NONE,
            SYNC_TYPE_MEDIA_INCREMENTAL,
            SYNC_TYPE_MEDIA_FULL,
            SYNC_TYPE_MEDIA_RESET,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface SyncType {}

    private final Object mLock = new Object();
    private final PickerDbFacade mDbFacade;
    private final Context mContext;
    private final SharedPreferences mSyncPrefs;
    private final SharedPreferences mUserPrefs;
    private final String mLocalProvider;
    private final long mSyncDelayMs;
    private final Runnable mSyncAllMediaCallback;

    @GuardedBy("mLock")
    private CloudProviderInfo mCloudProviderInfo;

    public PickerSyncController(Context context, PickerDbFacade dbFacade) {
        this(context, dbFacade, LOCAL_PICKER_PROVIDER_AUTHORITY, DEFAULT_SYNC_DELAY_MS);
    }

    @VisibleForTesting
    PickerSyncController(Context context, PickerDbFacade dbFacade,
            String localProvider, long syncDelayMs) {
        mContext = context;
        mSyncPrefs = mContext.getSharedPreferences(PICKER_SYNC_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mUserPrefs = mContext.getSharedPreferences(PICKER_USER_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mDbFacade = dbFacade;
        mLocalProvider = localProvider;
        mSyncDelayMs = syncDelayMs;
        mSyncAllMediaCallback = this::syncAllMedia;

        final String cloudProviderAuthority = mUserPrefs.getString(
                PREFS_KEY_CLOUD_PROVIDER_AUTHORITY,
                DEFAULT_CLOUD_PROVIDER_AUTHORITY);
        final String cloudProviderPackageName = mUserPrefs.getString(
                PREFS_KEY_CLOUD_PROVIDER_PKGNAME,
                DEFAULT_CLOUD_PROVIDER_PKGNAME);
        final int cloudProviderUid = mUserPrefs.getInt(PREFS_KEY_CLOUD_PROVIDER_UID,
                DEFAULT_CLOUD_PROVIDER_UID);
        if (cloudProviderAuthority == null) {
            mCloudProviderInfo = CloudProviderInfo.EMPTY;
        } else {
            mCloudProviderInfo = new CloudProviderInfo(cloudProviderAuthority,
                cloudProviderPackageName, cloudProviderUid);
        }
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncAllMedia() {
        syncAllMediaFromProvider(mLocalProvider, /* retryOnFailure */ true);

        synchronized (mLock) {
            final String cloudProvider = mCloudProviderInfo.authority;

            syncAllMediaFromProvider(cloudProvider, /* retryOnFailure */ true);

            // Reset the album_media table every time we sync all media
            resetAlbumMedia();

            // Set the latest cloud provider on the facade
            mDbFacade.setCloudProvider(cloudProvider);
        }
    }

    /**
     * Syncs album media from the local and currently enabled cloud {@link CloudMediaProvider}
     * instances
     */
    public void syncAlbumMedia(String albumId, boolean isLocal) {
        if (isLocal) {
            syncAlbumMediaFromProvider(mLocalProvider, albumId);
        } else {
            synchronized (mLock) {
                syncAlbumMediaFromProvider(mCloudProviderInfo.authority, albumId);
            }
        }
    }

    private void resetAlbumMedia() {
        executeSyncAlbumReset(mLocalProvider, /* albumId */ null);

        synchronized (mLock) {
            final String cloudProvider = mCloudProviderInfo.authority;
            executeSyncAlbumReset(cloudProvider, /* albumId */ null);
        }
    }

    private void resetAllMedia(String authority) {
        executeSyncReset(authority);
        resetCachedMediaCollectionInfo(authority);
    }

    /**
     * Returns the supported cloud {@link CloudMediaProvider} infos.
     */
    public CloudProviderInfo getCloudProviderInfo(String authority) {
        for (CloudProviderInfo info : getSupportedCloudProviders()) {
            if (info.authority.equals(authority)) {
                return info;
            }
        }

        return CloudProviderInfo.EMPTY;
    }

    /**
     * Returns the supported cloud {@link CloudMediaProvider} authorities.
     */
    @VisibleForTesting
    List<CloudProviderInfo> getSupportedCloudProviders() {
        final List<CloudProviderInfo> result = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        final Intent intent = new Intent(CloudMediaProviderContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> providers = pm.queryIntentContentProviders(intent, /* flags */ 0);

        for (ResolveInfo info : providers) {
            ProviderInfo providerInfo = info.providerInfo;
            if (providerInfo.authority != null
                    && CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(
                            providerInfo.readPermission)) {
                result.add(new CloudProviderInfo(providerInfo.authority,
                                providerInfo.applicationInfo.packageName,
                                providerInfo.applicationInfo.uid));
            }
        }

        return result;
    }

    /**
     * Enables a provider with {@code authority} as the default cloud {@link CloudMediaProvider}.
     * If {@code authority} is set to {@code null}, it simply clears the cloud provider.
     *
     * Note, that this doesn't sync the new provider after switching, however, no cloud items will
     * be available from the picker db until the next sync. Callers should schedule a sync in the
     * background after switching providers.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     * otherwise
     */
    public boolean setCloudProvider(String authority) {
        synchronized (mLock) {
            if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        final CloudProviderInfo newProviderInfo = getCloudProviderInfo(authority);
        if (authority == null || !newProviderInfo.isEmpty()) {
            synchronized (mLock) {
                final String oldAuthority = mCloudProviderInfo.authority;
                setCloudProviderInfo(newProviderInfo);
                resetCachedMediaCollectionInfo(newProviderInfo.authority);

                // Disable cloud provider queries on the db until next sync
                // This will temporarily *clear* the cloud provider on the db facade and prevent
                // any queries from seeing cloud media until a sync where the cloud provider will be
                // reset on the facade
                mDbFacade.setCloudProvider(null);

                Log.i(TAG, "Cloud provider changed successfully. Old: "
                        + oldAuthority + ". New: " + newProviderInfo.authority);
            }

            return true;
        }

        Log.w(TAG, "Cloud provider not supported: " + authority);
        return false;
    }

    public String getCloudProvider() {
        synchronized (mLock) {
            return mCloudProviderInfo.authority;
        }
    }

    public String getLocalProvider() {
        return mLocalProvider;
    }

    public boolean isProviderEnabled(String authority) {
        if (mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mLock) {
            if (!mCloudProviderInfo.isEmpty() && mCloudProviderInfo.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderEnabled(String authority, int uid) {
        if (uid == Process.myUid() && mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mLock) {
            if (!mCloudProviderInfo.isEmpty() && uid == mCloudProviderInfo.uid
                    && mCloudProviderInfo.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderSupported(String authority, int uid) {
        if (uid == Process.myUid() && mLocalProvider.equals(authority)) {
            return true;
        }

        final List<CloudProviderInfo> infos = getSupportedCloudProviders();
        for (CloudProviderInfo info : infos) {
            if (info.uid == uid && info.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Notifies about media events like inserts/updates/deletes from cloud and local providers and
     * syncs the changes in the background.
     *
     * There is a delay before executing the background sync to artificially throttle the burst
     * notifications.
     */
    public void notifyMediaEvent() {
        BackgroundThread.getHandler().removeCallbacks(mSyncAllMediaCallback);
        BackgroundThread.getHandler().postDelayed(mSyncAllMediaCallback, mSyncDelayMs);
    }

    /**
     * Notifies about package removal
     */
    public void notifyPackageRemoval(String packageName) {
        synchronized (mLock) {
            if (mCloudProviderInfo.matches(packageName)) {
                Log.i(TAG, "Package " + packageName
                        + " is the current cloud provider and got removed");
                setCloudProvider(null);
            }
        }
    }

    private void syncAlbumMediaFromProvider(String authority, String albumId) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(EXTRA_ALBUM_ID, albumId);

        try {
            executeSyncAlbumReset(authority, albumId);

            if (authority != null) {
                executeSyncAddAlbum(authority, albumId, queryArgs);
            }
        } catch (RuntimeException e) {
            // Unlike syncAllMediaFromProvider, we don't retry here because any errors would have
            // occurred in fetching all the album_media since incremental sync is not supported.
            // A full sync is therefore unlikely to resolve any issue
            Log.e(TAG, "Failed to sync album media", e);
        }
    }

    private void syncAllMediaFromProvider(String authority, boolean retryOnFailure) {
        try {
            final SyncRequestParams params = getSyncRequestParams(authority);

            switch (params.syncType) {
                case SYNC_TYPE_MEDIA_RESET:
                    // Can only happen when |authority| has been set to null and we need to clean up
                    resetAllMedia(authority);
                    break;
                case SYNC_TYPE_MEDIA_FULL:
                    resetAllMedia(authority);

                    executeSyncAdd(authority, params.getMediaCollectionId(),
                            /* isIncrementalSync */ false, /* queryArgs */ Bundle.EMPTY);

                    // Commit sync position
                    cacheMediaCollectionInfo(authority, params.latestMediaCollectionInfo);
                    break;
                case SYNC_TYPE_MEDIA_INCREMENTAL:
                    final Bundle queryArgs = new Bundle();
                    queryArgs.putLong(EXTRA_SYNC_GENERATION, params.syncGeneration);

                    executeSyncAdd(authority, params.getMediaCollectionId(),
                            /* isIncrementalSync */ true, queryArgs);
                    executeSyncRemove(authority, params.getMediaCollectionId(), queryArgs);

                    // Commit sync position
                    cacheMediaCollectionInfo(authority, params.latestMediaCollectionInfo);
                    break;
                case SYNC_TYPE_NONE:
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected sync type: " + params.syncType);
            }
        } catch (RuntimeException e) {
            // Reset all media for the cloud provider in case it never succeeds
            resetAllMedia(authority);

            // Attempt a full sync. If this fails, the db table would have been reset,
            // flushing all old content and leaving the picker UI empty.
            Log.e(TAG, "Failed to sync all media. Reset media and retry: " + retryOnFailure, e);
            if (retryOnFailure) {
                syncAllMediaFromProvider(authority, /* retryOnFailure */ false);
            }
        }
    }

    private void executeSyncReset(String authority) {
        Log.i(TAG, "Executing SyncReset. authority: " + authority);

        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetMediaOperation(authority)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            Log.i(TAG, "SyncReset. Authority: " + authority +  ". Result count: " + writeCount);
        }
    }

    private void executeSyncAlbumReset(String authority, String albumId) {
        Log.i(TAG, "Executing SyncAlbumReset. authority: " + authority + ". albumId: "
                + albumId);

        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            Log.i(TAG, "Successfully executed SyncResetAlbum. authority: " + authority
                    + ". albumId: " + albumId + ". Result count: " + writeCount);
        }
    }

    private void executeSyncAdd(String authority, String expectedMediaCollectionId,
            boolean isIncrementalSync, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);
        final List<String> expectedHonoredArgs = new ArrayList<>();
        if (isIncrementalSync) {
            expectedHonoredArgs.add(EXTRA_SYNC_GENERATION);
        }

        Log.i(TAG, "Executing SyncAdd. authority: " + authority);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddMediaOperation(authority)) {
            executePagedSync(uri, expectedMediaCollectionId, expectedHonoredArgs, queryArgs,
                    operation);
        }
    }

    private void executeSyncAddAlbum(String authority, String albumId, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);

        Log.i(TAG, "Executing SyncAddAlbum. authority: " + authority + ". albumId: " + albumId);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddAlbumMediaOperation(authority, albumId)) {

            // We don't need to validate the mediaCollectionId for album_media sync since it's
            // always a full sync
            executePagedSync(uri, /* mediaCollectionId */ null, Arrays.asList(EXTRA_ALBUM_ID),
                    queryArgs, operation);
        }
    }

    private void executeSyncRemove(String authority, String mediaCollectionId, Bundle queryArgs) {
        final Uri uri = getDeletedMediaUri(authority);

        Log.i(TAG, "Executing SyncRemove. authority: " + authority);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginRemoveMediaOperation(authority)) {
            executePagedSync(uri, mediaCollectionId, Arrays.asList(EXTRA_SYNC_GENERATION),
                    queryArgs, operation);
        }
    }

    private void setCloudProviderInfo(CloudProviderInfo info) {
        synchronized (mLock) {
            mCloudProviderInfo = info;
        }

        final SharedPreferences.Editor editor = mUserPrefs.edit();

        if (info.isEmpty()) {
            editor.remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY);
            editor.remove(PREFS_KEY_CLOUD_PROVIDER_PKGNAME);
            editor.remove(PREFS_KEY_CLOUD_PROVIDER_UID);
        } else {
            editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, info.authority);
            editor.putString(PREFS_KEY_CLOUD_PROVIDER_PKGNAME, info.packageName);
            editor.putInt(PREFS_KEY_CLOUD_PROVIDER_UID, info.uid);
        }

        editor.apply();
    }

    private void cacheMediaCollectionInfo(String authority, Bundle bundle) {
        if (authority == null) {
            Log.d(TAG, "Ignoring cache media info for null authority with bundle: " + bundle);
            return;
        }

        final SharedPreferences.Editor editor = mSyncPrefs.edit();

        if (bundle == null) {
            editor.remove(getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID));
            editor.remove(getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION));
        } else {
            final String collectionId = bundle.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
            final long generation = bundle.getLong(
                    MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

            editor.putString(getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID),
                    collectionId);
            editor.putLong(getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION),
                    generation);
        }

        editor.apply();
    }

    private void resetCachedMediaCollectionInfo(String authority) {
        cacheMediaCollectionInfo(authority, /* bundle */ null);
    }

    private Bundle getCachedMediaCollectionInfo(String authority) {
        final Bundle bundle = new Bundle();

        final String collectionId = mSyncPrefs.getString(
                getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID),
                /* default */ null);
        final long generation = mSyncPrefs.getLong(
                getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION),
                /* default */ -1);

        bundle.putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, collectionId);
        bundle.putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, generation);

        return bundle;
    }

    private Bundle getLatestMediaCollectionInfo(String authority) {
        return mContext.getContentResolver().call(getMediaCollectionInfoUri(authority),
                CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO, /* arg */ null,
                /* extras */ null);
    }

    @SyncType
    private SyncRequestParams getSyncRequestParams(String authority) {
        if (authority == null) {
            // Only cloud authority can be null
            Log.d(TAG, "Fetching SyncRequestParams. Null cloud authority. Result: SYNC_TYPE_RESET");
            return SyncRequestParams.forResetMedia();
        }

        final Bundle cachedMediaCollectionInfo = getCachedMediaCollectionInfo(authority);
        final Bundle latestMediaCollectionInfo = getLatestMediaCollectionInfo(authority);

        final String latestCollectionId =
                latestMediaCollectionInfo.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
        final long latestGeneration =
                latestMediaCollectionInfo.getLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

        final String cachedCollectionId =
                cachedMediaCollectionInfo.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
        final long cachedGeneration = cachedMediaCollectionInfo.getLong(
                MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

        Log.d(TAG, "Fetching SyncRequestParams. Authority: " + authority
                + ". LatestMediaCollectionInfo: " + latestMediaCollectionInfo
                + ". CachedMediaCollectionInfo: " + cachedMediaCollectionInfo);

        if (TextUtils.isEmpty(latestCollectionId) || latestGeneration < 0) {
            throw new IllegalStateException("Unexpected media collection info. mediaCollectionId: "
                    + latestCollectionId + ". lastMediaSyncGeneration: " + latestGeneration);
        }

        if (!Objects.equals(latestCollectionId, cachedCollectionId)) {
            Log.d(TAG, "SyncRequestParams. Authority: " + authority + ". Result: SYNC_TYPE_FULL");
            return SyncRequestParams.forFullMedia(latestMediaCollectionInfo);
        }

        if (cachedGeneration == latestGeneration) {
            Log.d(TAG, "SyncRequestParams. Authority: " + authority + ". Result: SYNC_TYPE_NONE");
            return SyncRequestParams.forNone();
        }

        Log.d(TAG, "SyncRequestParams. Authority: " + authority
                + ". Result: SYNC_TYPE_INCREMENTAL");
        return SyncRequestParams.forIncremental(cachedGeneration, latestMediaCollectionInfo);
    }

    private String getPrefsKey(String authority, String key) {
        return (isLocal(authority) ? PREFS_KEY_LOCAL_PREFIX : PREFS_KEY_CLOUD_PREFIX) + key;
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }

    private void executePagedSync(Uri uri, String expectedMediaCollectionId,
            List<String> expectedHonoredArgs, Bundle queryArgs,
            PickerDbFacade.DbWriteOperation dbWriteOperation) {
        int cursorCount = 0;
        int totalRowcount = 0;
        // Set to check the uniqueness of tokens across pages.
        Set<String> tokens = new ArraySet<>();

        String nextPageToken = null;
        do {
            if (nextPageToken != null) {
                queryArgs.putString(EXTRA_PAGE_TOKEN, nextPageToken);
            }

            try (Cursor cursor = query(uri, queryArgs)) {
                nextPageToken = validateCursor(cursor, expectedMediaCollectionId,
                        expectedHonoredArgs, tokens);

                int writeCount = dbWriteOperation.execute(cursor);

                totalRowcount += writeCount;
                cursorCount += cursor.getCount();
            }
        } while (nextPageToken != null);

        dbWriteOperation.setSuccess();
        Log.i(TAG, "Paged sync successful. QueryArgs: " + queryArgs + ". Result count: "
                + totalRowcount + ". Cursor count: " + cursorCount);
    }

    private static String validateCursor(Cursor cursor, String expectedMediaCollectionId,
            List<String> expectedHonoredArgs, Set<String> usedPageTokens) {
        final Bundle bundle = cursor.getExtras();

        if (bundle == null) {
            throw new IllegalStateException("Unable to verify the media collection id");
        }

        final String mediaCollectionId = bundle.getString(EXTRA_MEDIA_COLLECTION_ID);
        final String pageToken = bundle.getString(EXTRA_PAGE_TOKEN);
        List<String> honoredArgs = bundle.getStringArrayList(EXTRA_HONORED_ARGS);
        if (honoredArgs == null) {
            honoredArgs = new ArrayList<>();
        }

        if (expectedMediaCollectionId != null
                && !expectedMediaCollectionId.equals(mediaCollectionId)) {
            throw new IllegalStateException("Mismatched media collection id. Expected: "
                    + expectedMediaCollectionId + ". Found: " + mediaCollectionId);
        }

        if (!honoredArgs.containsAll(expectedHonoredArgs)) {
            throw new IllegalStateException("Unspecified honored args. Expected: "
                    + Arrays.toString(expectedHonoredArgs.toArray())
                    + ". Found: " + Arrays.toString(honoredArgs.toArray()));
        }

        if (usedPageTokens.contains(pageToken)) {
            throw new IllegalStateException("Found repeated page token: " + pageToken);
        } else {
            usedPageTokens.add(pageToken);
        }

        return pageToken;
    }

    @VisibleForTesting
    static class CloudProviderInfo {
        static final CloudProviderInfo EMPTY = new CloudProviderInfo();
        private final String authority;
        private final String packageName;
        private final int uid;

        private CloudProviderInfo() {
            this.authority = DEFAULT_CLOUD_PROVIDER_AUTHORITY;
            this.packageName = DEFAULT_CLOUD_PROVIDER_PKGNAME;
            this.uid = DEFAULT_CLOUD_PROVIDER_UID;
        }

        CloudProviderInfo(String authority, String packageName, int uid) {
            Objects.requireNonNull(authority);
            Objects.requireNonNull(packageName);

            this.authority = authority;
            this.packageName = packageName;
            this.uid = uid;
        }

        boolean isEmpty() {
            return equals(EMPTY);
        }

        boolean matches(String packageName) {
            return !isEmpty() && this.packageName.equals(packageName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;

            CloudProviderInfo that = (CloudProviderInfo) obj;

            return Objects.equals(authority, that.authority) &&
                    Objects.equals(packageName, that.packageName) &&
                    Objects.equals(uid, that.uid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authority, packageName, uid);
        }
    }

    private static class SyncRequestParams {
        private static final SyncRequestParams SYNC_REQUEST_NONE =
                new SyncRequestParams(SYNC_TYPE_NONE);
        private static final SyncRequestParams SYNC_REQUEST_MEDIA_RESET =
                new SyncRequestParams(SYNC_TYPE_MEDIA_RESET);

        private final int syncType;
        // Only valid for SYNC_TYPE_INCREMENTAL
        private final long syncGeneration;
        // Only valid for SYNC_TYPE_[INCREMENTAL|FULL]
        private final Bundle latestMediaCollectionInfo;

        private SyncRequestParams(@SyncType int syncType) {
            this(syncType, /* syncGeneration */ 0, /* latestMediaCollectionInfo */ null);
        }

        private SyncRequestParams(@SyncType int syncType, long syncGeneration,
                Bundle latestMediaCollectionInfo) {
            this.syncType = syncType;
            this.syncGeneration = syncGeneration;
            this.latestMediaCollectionInfo = latestMediaCollectionInfo;
        }

        String getMediaCollectionId() {
            return latestMediaCollectionInfo.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
        }

        static SyncRequestParams forNone() {
            return SYNC_REQUEST_NONE;
        }

        static SyncRequestParams forResetMedia() {
            return SYNC_REQUEST_MEDIA_RESET;
        }

        static SyncRequestParams forFullMedia(Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_FULL, /* generation */ 0,
                    latestMediaCollectionInfo);
        }

        static SyncRequestParams forIncremental(long generation, Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_INCREMENTAL, generation,
                    latestMediaCollectionInfo);
        }
    }
}
