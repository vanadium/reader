// Copyright 2015 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package io.v.android.apps.reader.db;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import io.v.android.apps.reader.model.Listener;
import io.v.android.apps.reader.vdl.Device;
import io.v.android.apps.reader.vdl.DeviceSet;
import io.v.android.apps.reader.vdl.File;

/**
 * Provides high-level methods for getting and setting the state of PDF reader.
 * It is an interface instead of a concrete class to make testing easier.
 */
public interface DB {

    class Singleton {

        private static volatile DB instance;

        public static DB get(Context context) {
            DB result = instance;
            if (instance == null) {
                synchronized (Singleton.class) {
                    result = instance;
                    if (result == null) {
                        // uncomment either one
//                        instance = result = new FakeDB(context);
                        instance = result = new SyncbaseDB(context);
                    }
                }
            }

            return result;
        }
    }

    /**
     * Perform initialization steps.  This method must be called early in the lifetime
     * of the activity.  As part of the initialization, it might send an intent to
     * another activity.
     *
     * @param activity implements onActivityResult() to call into DB.onActivityResult.
     */
    void init(Activity activity);

    /**
     * If init() sent an intent to another Activity, the result must be forwarded
     * from our app's activity to this method.
     *
     * @return true if the requestCode matches an intent sent by this implementation.
     */
    boolean onActivityResult(int requestCode, int resultCode, Intent data);

    /**
     * Provides a list of elements that fits well with RecyclerView.Adapter.
     */
    interface DBList<E> {
        /**
         * Returns the number of available elements.
         */
        int getItemCount();

        /**
         * Returns the element at the given position.
         */
        E getItem(int position);

        /**
         * Returns the element with the given id.
         */
        E getItemById(String id);

        /**
         * Sets the listener for changes to the list.
         * There can only be one listener.
         */
        void setListener(Listener listener);

        /**
         * Indicates that the list is no longer needed
         * and should stop notifying its listener.
         */
        void discard();
    }

    /**
     * Gets the list of available PDF files.
     *
     * @return a list of PDF files.
     */
    DBList<File> getFileList();

    /**
     * Gets the list of devices of this user.
     *
     * @return a list of devices.
     */
    DBList<Device> getDeviceList();

    /**
     * Gets the list of currently active device sets.
     *
     * @return a list of device sets.
     */
    DBList<DeviceSet> getDeviceSetList();

    /**
     * Adds a new device set to the db.
     *
     * @param ds the device set to be added.
     */
    void addDeviceSet(DeviceSet ds);

    /**
     * Deletes a device set with the given id.
     *
     * @param id the id of the device set.
     */
    void deleteDeviceSet(String id);

}
