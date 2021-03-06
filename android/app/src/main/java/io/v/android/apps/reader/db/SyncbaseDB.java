// Copyright 2015 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package io.v.android.apps.reader.db;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import io.v.android.apps.reader.model.DeviceInfoFactory;
import io.v.android.apps.reader.model.Listener;
import io.v.android.apps.reader.vdl.Device;
import io.v.android.apps.reader.vdl.DeviceSet;
import io.v.android.apps.reader.vdl.File;
import io.v.android.libs.security.BlessingsManager;
import io.v.android.v23.V;
import io.v.baku.toolkit.VAndroidContextMixin;
import io.v.baku.toolkit.debug.DebugUtils;
import io.v.impl.google.naming.NamingUtil;
import io.v.impl.google.services.syncbase.SyncbaseServer;
import io.v.v23.InputChannels;
import io.v.v23.OptionDefs;
import io.v.v23.Options;
import io.v.v23.VIterable;
import io.v.v23.context.VContext;
import io.v.v23.rpc.Server;
import io.v.v23.security.BlessingPattern;
import io.v.v23.security.Blessings;
import io.v.v23.security.VCertificate;
import io.v.v23.security.access.AccessList;
import io.v.v23.security.access.Constants;
import io.v.v23.security.access.Permissions;
import io.v.v23.services.syncbase.nosql.BatchOptions;
import io.v.v23.services.syncbase.nosql.BlobRef;
import io.v.v23.services.syncbase.nosql.KeyValue;
import io.v.v23.services.syncbase.nosql.SyncgroupMemberInfo;
import io.v.v23.services.syncbase.nosql.SyncgroupSpec;
import io.v.v23.services.syncbase.nosql.TableRow;
import io.v.v23.services.watch.ResumeMarker;
import io.v.v23.syncbase.Syncbase;
import io.v.v23.syncbase.SyncbaseApp;
import io.v.v23.syncbase.SyncbaseService;
import io.v.v23.syncbase.nosql.BatchDatabase;
import io.v.v23.syncbase.nosql.BlobReader;
import io.v.v23.syncbase.nosql.BlobWriter;
import io.v.v23.syncbase.nosql.Database;
import io.v.v23.syncbase.nosql.RowRange;
import io.v.v23.syncbase.nosql.Syncgroup;
import io.v.v23.syncbase.nosql.Table;
import io.v.v23.syncbase.nosql.WatchChange;
import io.v.v23.verror.ExistException;
import io.v.v23.verror.VException;
import io.v.v23.vom.VomUtil;

import static io.v.v23.VFutures.sync;

/**
 * A class representing the syncbase instance.
 */
public class SyncbaseDB implements DB {

    private static final String TAG = SyncbaseDB.class.getSimpleName();

    private static final String SYNCBASE_APP = "reader";
    private static final String SYNCBASE_DB = "db";
    private static final String TABLE_FILES = "files";
    private static final String TABLE_DEVICES = "devices";
    private static final String TABLE_DEVICE_SETS = "deviceSets";

    private static final int SYNCGROUP_JOIN_DELAY = 5000;

    private Permissions mPermissions;
    private Context mContext;
    private VContext mVContext;
    private SyncbaseHierarchy mLocalSB;

    private SettableFuture<Void> mInitialized;

    private String mUsername;
    private String mSyncgroupName;

    SyncbaseDB(Context context) {
        mContext = context;
    }

    @Override
    public void init(Activity activity) {
        // Make sure that the initialization logic runs at most once.
        if (mInitialized != null) {
            return;
        }

        mInitialized = SettableFuture.create();

        if (mVContext == null) {
            if (activity instanceof VAndroidContextMixin) {
                // In case of the activity inherits from one of the baku-toolkit's base activities,
                // retrieve the Vanadium context from there directly.
                mVContext = ((VAndroidContextMixin) activity)
                        .getVAndroidContextTrait().getVContext();
            } else {
                // Otherwise, initialize Vanadium runtime here with -vmodule=*=5 setting.
                if (DebugUtils.isApkDebug(activity)) {
                    Options opts = new Options();
                    opts.set(OptionDefs.LOG_VMODULE, "*=5");
                    mVContext = V.init(mContext, opts);
                } else {
                    mVContext = V.init(mContext);
                }
            }

            mVContext = V.withExecutor(mVContext, Executors.newSingleThreadExecutor());

            try {
                mVContext = V.withListenSpec(
                        mVContext, V.getListenSpec(mVContext).withProxy("proxy"));
            } catch (VException e) {
                handleError("Couldn't setup vanadium proxy: " + e.getMessage());
            }
        }

        AccessList acl = new AccessList(
                ImmutableList.of(new BlessingPattern("...")), ImmutableList.<String>of());
        mPermissions = new Permissions(ImmutableMap.of(
                Constants.READ.getValue(), acl,
                Constants.WRITE.getValue(), acl,
                Constants.ADMIN.getValue(), acl,
                Constants.RESOLVE.getValue(), acl,
                Constants.DEBUG.getValue(), acl));
        getBlessings(activity);
    }

    @Override
    public ListenableFuture<Void> onInitialized() {
        return mInitialized;
    }

    @Override
    public boolean isInitialized() {
        return mInitialized != null && mInitialized.isDone();
    }

    private void getBlessings(Activity activity) {
        ListenableFuture<Blessings> blessingsFuture = BlessingsManager
                .getBlessings(mVContext, activity, "VanadiumBlessings", true);

        Futures.addCallback(blessingsFuture, new FutureCallback<Blessings>() {
            @Override
            public void onSuccess(Blessings result) {
                mUsername = mountNameFromBlessings(result);
                setupLocalSyncbase();
            }

            @Override
            public void onFailure(Throwable t) {
                handleError("Could not get blessing: " + t.getMessage());
            }
        });
    }

    private void setupLocalSyncbase() {
        // "users/<user_email>/android/reader/<device_id>/syncbase"
        final String syncbaseName = NamingUtil.join(
                "users",
                mUsername,
                "android/reader",
                DeviceInfoFactory.getDevice(mContext).getId(),
                "syncbase"
        );
        Log.i(TAG, "SyncbaseName: " + syncbaseName);

        // Prepare the syncbase storage directory.
        java.io.File storageDir = new java.io.File(mContext.getFilesDir(), "syncbase");

        // Clear the contents of local syncbase DB.
        // TODO(youngseokyoon): remove this once Syncbase can properly handle locally stored data.
        try {
            FileUtils.deleteDirectory(storageDir);
        } catch (IOException e) {
            handleError("Couldn't clear the syncbase storage directory");
        }

        storageDir.mkdirs();

        try {
            mVContext = SyncbaseServer.withNewServer(
                    mVContext,
                    new SyncbaseServer.Params()
                            .withName(syncbaseName)
                            .withPermissions(mPermissions)
                            .withStorageRootDir(storageDir.getAbsolutePath()));
        } catch (SyncbaseServer.StartException e) {
            handleError("Couldn't start syncbase server");
            return;
        }

        try {
            Server syncbaseServer = V.getServer(mVContext);
            String serverName = "/" + syncbaseServer.getStatus().getEndpoints()[0];

            Log.i(TAG, "Local Syncbase ServerName: " + serverName);

            mLocalSB = createHierarchy(serverName, "local");

            setupCloudSyncbase();
        } catch (VException e) {
            handleError("Couldn't setup syncbase service: " + e.getMessage());
        }
    }

    /**
     * This method assumes that there is a separate cloudsync instance running at:
     * "users/[user_email]/reader/cloudsync"
     */
    private void setupCloudSyncbase() {
        try {
            // "users/<user_email>/reader/cloudsync"
            String cloudsyncName = NamingUtil.join(
                    "users",
                    mUsername,
                    "reader/cloudsync"
            );

            SyncbaseHierarchy cloudSB = createHierarchy(cloudsyncName, "cloud");

            createSyncgroup(cloudSB.db);
        } catch (VException e) {
            handleError("Couldn't setup cloudsync: " + e.getMessage());
        }
    }

    /**
     * Creates a syncgroup at cloudsync with the following name:
     * "users/[user_email]/reader/cloudsync/%%sync/cloudsync"
     */
    private void createSyncgroup(Database db) {
        mSyncgroupName = NamingUtil.join(
                "users",
                mUsername,
                "reader/cloudsync/%%sync/cloudsync"
        );

        Syncgroup group = db.getSyncgroup(mSyncgroupName);

        List<TableRow> prefixes = ImmutableList.of(
                new TableRow(TABLE_FILES, ""),
                new TableRow(TABLE_DEVICES, ""),
                new TableRow(TABLE_DEVICE_SETS, "")
        );

        List<String> mountTables = ImmutableList.of(
                NamingUtil.join(
                        "users",
                        mUsername,
                        "reader/rendezvous"
                )
        );

        SyncgroupSpec spec = new SyncgroupSpec(
                "reader syncgroup",
                mPermissions,
                prefixes,
                mountTables,
                false
        );

        try {
            sync(group.create(mVContext, spec, new SyncgroupMemberInfo()));
            Log.i(TAG, "Syncgroup is created successfully.");
        } catch (ExistException e) {
            Log.i(TAG, "Syncgroup already exists.");
        } catch (VException e) {
            handleError("Syncgroup could not be created: " + e.getMessage());
            return;
        }

        // TODO(youngseokyoon): investigate why this is needed.
        // Join the syncgroup a few seconds later to make sure that the syncgroup is ready.
        Handler handler = new Handler();
        handler.postDelayed(() -> joinSyncgroup(), SYNCGROUP_JOIN_DELAY);
    }

    /**
     * Sets up the local syncbase to join the syncgroup.
     */
    private void joinSyncgroup() {
        Syncgroup group = mLocalSB.db.getSyncgroup(mSyncgroupName);

        try {
            SyncgroupSpec spec = sync(group.join(mVContext, new SyncgroupMemberInfo()));
            Log.i(TAG, "Successfully joined the syncgroup!");
            Log.i(TAG, "Syncgroup spec: " + spec);

            Map<String, SyncgroupMemberInfo> members = sync(group.getMembers(mVContext));
            for (String memberName : members.keySet()) {
                Log.i(TAG, "Member: " + memberName);
            }
        } catch (VException e) {
            handleError("Could not join the syncgroup: " + e.getMessage());
            return;
        }

        mInitialized.set(null);

        // When successfully joined the syncgroup, first register the device information.
        registerDevice();
    }

    private void registerDevice() {
        try {
            Device thisDevice = DeviceInfoFactory.getDevice(mContext);
            sync(mLocalSB.devices.put(mVContext, thisDevice.getId(), thisDevice, Device.class));
            Log.i(TAG, "Registered this device to the syncbase table: " + thisDevice);
        } catch (VException e) {
            handleError("Could not register this device: " + e.getMessage());
        }
    }

    /**
     * Creates the "[app]/[db]/[table]" hierarchy at the provided syncbase name.
     */
    private SyncbaseHierarchy createHierarchy(
            String syncbaseName, String debugName) throws VException {

        SyncbaseService service = Syncbase.newService(syncbaseName);

        SyncbaseHierarchy result = new SyncbaseHierarchy();

        result.app = service.getApp(SYNCBASE_APP);
        if (!sync(result.app.exists(mVContext))) {
            sync(result.app.create(mVContext, mPermissions));
            Log.i(TAG, String.format(
                    "\"%s\" app is created at %s", result.app.name(), debugName));
        } else {
            Log.i(TAG, String.format(
                    "\"%s\" app already exists at %s", result.app.name(), debugName));
        }

        result.db = result.app.getNoSqlDatabase(SYNCBASE_DB, null);
        if (!sync(result.db.exists(mVContext))) {
            sync(result.db.create(mVContext, mPermissions));
            Log.i(TAG, String.format(
                    "\"%s\" db is created at %s", result.db.name(), debugName));
        } else {
            Log.i(TAG, String.format(
                    "\"%s\" db already exists at %s", result.db.name(), debugName));
        }

        result.files = result.db.getTable(TABLE_FILES);
        if (!sync(result.files.exists(mVContext))) {
            sync(result.files.create(mVContext, mPermissions));
            Log.i(TAG, String.format(
                    "\"%s\" table is created at %s", result.files.name(), debugName));
        } else {
            Log.i(TAG, String.format(
                    "\"%s\" table already exists at %s", result.files.name(), debugName));
        }

        result.devices = result.db.getTable(TABLE_DEVICES);
        if (!sync(result.devices.exists(mVContext))) {
            sync(result.devices.create(mVContext, mPermissions));
            Log.i(TAG, String.format(
                    "\"%s\" table is created at %s", result.devices.name(), debugName));
        } else {
            Log.i(TAG, String.format(
                    "\"%s\" table already exists at %s", result.devices.name(), debugName));
        }

        result.deviceSets = result.db.getTable(TABLE_DEVICE_SETS);
        if (!sync(result.deviceSets.exists(mVContext))) {
            sync(result.deviceSets.create(mVContext, mPermissions));
            Log.i(TAG, String.format(
                    "\"%s\" table is created at %s", result.deviceSets.name(), debugName));
        } else {
            Log.i(TAG, String.format(
                    "\"%s\" table already exists at %s", result.deviceSets.name(), debugName));
        }

        return result;
    }

    /**
     * This method finds the last certificate in our blessing's certificate
     * chains whose extension contains an '@'. We will assume that extension to
     * represent our username.
     */
    private static String mountNameFromBlessings(Blessings blessings) {
        for (List<VCertificate> chain : blessings.getCertificateChains()) {
            for (VCertificate certificate : Lists.reverse(chain)) {
                String ext = certificate.getExtension();
                if (ext.contains("@")) {
                    // Return only the user email portion after the app id.
                    return ext.substring(ext.lastIndexOf(':') + 1);
                }
            }
        }
        return "";
    }

    @Override
    public DBList<File> getFileList() {
        if (!isInitialized()) {
            return new EmptyList<>();
        }

        return new SyncbaseFileList(TABLE_FILES, File.class);
    }

    @Override
    public DBList<Device> getDeviceList() {
        if (!isInitialized()) {
            return new EmptyList<>();
        }

        return new SyncbaseDeviceList(TABLE_DEVICES, Device.class);
    }

    @Override
    public DBList<DeviceSet> getDeviceSetList() {
        if (!isInitialized()) {
            return new EmptyList<>();
        }

        return new SyncbaseDeviceSetList(TABLE_DEVICE_SETS, DeviceSet.class);
    }

    @Override
    public void addFile(File file) {
        try {
            sync(mLocalSB.files.put(mVContext, file.getId(), file, File.class));
        } catch (VException e) {
            handleError("Failed to add the file(" + file + "): " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String id) {
        try {
            sync(mLocalSB.files.delete(mVContext, id));
        } catch (VException e) {
            handleError("Failed to delete the file with id " + id + ": " + e.getMessage());
        }
    }

    @Override
    public void addDeviceSet(DeviceSet ds) {
        try {
            sync(mLocalSB.deviceSets.put(mVContext, ds.getId(), ds, DeviceSet.class));
        } catch (VException e) {
            handleError("Failed to add the device set(" + ds + "): " + e.getMessage());
        }
    }

    @Override
    public void updateDeviceSet(DeviceSet ds) {
        try {
            sync(mLocalSB.deviceSets.put(mVContext, ds.getId(), ds, DeviceSet.class));
        } catch (VException e) {
            handleError("Failed to update the device set(" + ds + "): " + e.getMessage());
        }
    }

    @Override
    public void deleteDeviceSet(String id) {
        try {
            sync(mLocalSB.deviceSets.delete(mVContext, id));
        } catch (VException e) {
            handleError("Failed to delete the device set with id " + id + ": " + e.getMessage());
        }
    }

    @Override
    public FileBuilder getFileBuilder(String title) throws Exception {
        return new SyncbaseFileBuilder(title);
    }

    @Override
    public InputStream getInputStreamForFile(File file) {
        if (file == null || file.getRef() == null) {
            return null;
        }

        try {
            BlobReader reader = mLocalSB.db.readBlob(mVContext, file.getRef());
            return reader.stream(mVContext, 0L);
        } catch (VException e) {
            handleError("Could not open the input stream for file " + file.getRef().toString()
                    + ": " + e.getMessage());
        }

        return null;
    }

    @Override
    public InputStream getInputStreamForFile(String fileId) {
        try {
            File file = (File) sync(mLocalSB.files.get(mVContext, fileId, File.class));
            return getInputStreamForFile(file);
        } catch (VException e) {
            return null;
        }
    }

    private void handleError(String msg) {
        Log.e(TAG, msg);
        Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show();
    }

    // TODO(youngseokyoon): Remove once the list is implemented properly.
    private static class EmptyList<E> implements DBList<E> {
        @Override
        public int getItemCount() {
            return 0;
        }

        @Override
        public E getItem(int position) {
            return null;
        }

        @Override
        public E getItemById(String id) {
            return null;
        }

        @Override
        public void setListener(Listener listener) {
        }

        @Override
        public void discard() {
        }
    }

    private class SyncbaseFileList extends SyncbaseDBList<File> {

        public SyncbaseFileList(String tableName, Class clazz) {
            super(tableName, clazz);
        }

        @Override
        protected String getId(File file) {
            return file.getId();
        }
    }

    private class SyncbaseDeviceList extends SyncbaseDBList<Device> {

        public SyncbaseDeviceList(String tableName, Class clazz) {
            super(tableName, clazz);
        }

        @Override
        protected String getId(Device device) {
            return device.getId();
        }
    }

    private class SyncbaseDeviceSetList extends SyncbaseDBList<DeviceSet> {

        public SyncbaseDeviceSetList(String tableName, Class clazz) {
            super(tableName, clazz);
        }

        @Override
        protected String getId(DeviceSet deviceSet) {
            return deviceSet.getId();
        }
    }

    private abstract class SyncbaseDBList<E> implements DBList<E> {

        private final String TAG;

        private VContext mCancelableVContext;
        private Handler mHandler;
        private Listener mListener;
        private ResumeMarker mResumeMarker;
        private String mTableName;
        private Class mClass;
        private List<E> mItems;

        public SyncbaseDBList(String tableName, Class clazz) {
            mCancelableVContext = mVContext.withCancel();
            mTableName = tableName;
            mClass = clazz;
            mItems = new ArrayList<>();
            mHandler = new Handler(Looper.getMainLooper());

            TAG = String.format("%s<%s>",
                    SyncbaseDBList.class.getSimpleName(), mClass.getSimpleName());

            readInitialData();

            // Run this in a background thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    watchForChanges();
                }
            }).start();
        }

        private void readInitialData() {
            try {
                Log.i(TAG, "Reading initial data from table: " + mTableName);

                BatchDatabase batch = sync(mLocalSB.db.beginBatch(
                        mCancelableVContext, new BatchOptions("fetch", true)));

                // Read existing data from the table.
                Table table = batch.getTable(mTableName);
                VIterable<KeyValue> kvs = InputChannels.asIterable(
                        table.scan(mCancelableVContext, RowRange.range("", "")));
                for (KeyValue kv : kvs) {
                    @SuppressWarnings("unchecked")
                    E item = (E) VomUtil.decode(kv.getValue(), mClass);
                    mItems.add(item);
                }

                // Remember this resume marker for the watch call.
                mResumeMarker = sync(batch.getResumeMarker(mVContext));

                sync(batch.abort(mCancelableVContext));

                Log.i(TAG, "Done reading initial data from table: " + mTableName);
            } catch (Exception e) {
                handleError(e.getMessage());
            }
        }

        private void watchForChanges() {
            try {
                // Watch for new changes coming from other Syncbase peers.
                VIterable<WatchChange> watchStream = InputChannels.asIterable(
                        mLocalSB.db.watch(mCancelableVContext, mTableName, "", mResumeMarker));

                Log.i(TAG, "Watching for changes of table: " + mTableName + "...");

                for (final WatchChange wc : watchStream) {
                    printWatchChange(wc);

                    // Handle the watch change differently, depending on the change type.
                    switch (wc.getChangeType()) {
                        case PUT_CHANGE:
                            // Run this in the UI thread.
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handlePutChange(wc);
                                }
                            });
                            break;

                        case DELETE_CHANGE:
                            // Run this in the UI thread.
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleDeleteChange(wc);
                                }
                            });
                            break;
                    }
                }
            } catch (Exception e) {
                handleError(e.getMessage());
                Log.e(TAG, "Stack Trace: ", e);
            }
        }

        private void printWatchChange(WatchChange wc) {
            Log.i(TAG, "*** New Watch Change ***");
            Log.i(TAG, "- ChangeType: " + wc.getChangeType().toString());
            Log.i(TAG, "- RowName: " + wc.getRowName());
            Log.i(TAG, "- TableName: " + wc.getTableName());
            Log.i(TAG, "- VomValue: " + VomUtil.bytesToHexString(wc.getVomValue()));
            Log.i(TAG, "- isContinued: " + wc.isContinued());
            Log.i(TAG, "- isFromSync: " + wc.isFromSync());
            Log.i(TAG, "========================");
        }

        private void handlePutChange(WatchChange wc) {
            E item = null;

            try {
                item = (E) VomUtil.decode(wc.getVomValue(), mClass);
            } catch (VException e) {
                handleError("Could not decode the Vom: " + e.getMessage());
            }

            if (item == null) {
                return;
            }

            boolean handled = false;
            for (int i = 0; i < mItems.size(); ++i) {
                E e = mItems.get(i);

                if (wc.getRowName().equals(getId(e))) {
                    // Update the file record here.

                    mItems.remove(i);
                    mItems.add(i, item);

                    if (mListener != null) {
                        mListener.notifyItemChanged(i);
                    }

                    handled = true;
                }
            }

            if (handled) {
                return;
            }

            // This is a new row added in the table.
            mItems.add(item);

            if (mListener != null) {
                mListener.notifyItemInserted(mItems.size() - 1);
            }
        }

        private void handleDeleteChange(WatchChange wc) {
            boolean handled = false;
            for (int i = 0; i < mItems.size(); ++i) {
                E e = mItems.get(i);

                if (wc.getRowName().equals(getId(e))) {
                    mItems.remove(i);

                    if (mListener != null) {
                        mListener.notifyItemRemoved(i);
                    }

                    handled = true;
                }
            }

            if (!handled) {
                handleError("DELETE_CHANGE arrived but no matching item found in the table.");
            }
        }

        protected abstract String getId(E e);

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public E getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public E getItemById(String id) {
            for (E e : mItems) {
                if (getId(e).equals(id)) {
                    return e;
                }
            }

            return null;
        }

        @Override
        public void setListener(Listener listener) {
            assert mListener == null;
            mListener = listener;
        }

        @Override
        public void discard() {
            Log.i(TAG, "Cancelling the watch stream.");
            mCancelableVContext.cancel();
        }
    }

    private static class SyncbaseHierarchy {
        public SyncbaseApp app;
        public Database db;
        public Table files;
        public Table devices;
        public Table deviceSets;
    }

    private class SyncbaseFileBuilder implements DB.FileBuilder {

        private MessageDigest mDigest;
        private String mTitle;
        private long mSize;
        private BlobWriter mBlobWriter;
        private OutputStream mOutputStream;

        public SyncbaseFileBuilder(String title) throws Exception {
            mDigest = MessageDigest.getInstance("MD5");
            mTitle = title;
            mSize = 0L;

            mBlobWriter = sync(mLocalSB.db.writeBlob(mVContext, null));
            mOutputStream = mBlobWriter.stream(mVContext);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mOutputStream.write(b, off, len);
            mDigest.update(b, off, len);
            mSize += len;
        }

        @Override
        public void cancel() {
            try {
                mOutputStream.close();
                mBlobWriter.delete(mVContext);
            } catch (IOException e) {
                Log.e(TAG, "Could not cancel the writing: " + e.getMessage(), e);
            }
        }

        @Override
        public File build() {
            try {
                Log.i(TAG, "build() method called.");
                mOutputStream.close();
                Log.i(TAG, "after mOutputStream.close()");
                sync(mBlobWriter.commit(mVContext));
                Log.i(TAG, "after commit.");

                String id = VomUtil.bytesToHexString(mDigest.digest());
                Log.i(TAG, "after digest.");
                BlobRef ref = mBlobWriter.getRef();
                Log.i(TAG, "after getRef().");

                return new File(
                        id,
                        ref,
                        mTitle,
                        mSize,
                        io.v.android.apps.reader.Constants.PDF_MIME_TYPE);

            } catch (IOException | VException e) {
                Log.e(TAG, "Could not build the File: " + e.getMessage(), e);
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            mOutputStream.close();
            mOutputStream = null;
        }
    }
}
