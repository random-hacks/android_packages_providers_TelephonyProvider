/*
 * Copyright (C) 2015-2019 The MoKee Open Source Project
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

package com.android.providers.telephony;

import android.app.backup.BackupManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.provider.Telephony.PhoneLocation;
import android.text.TextUtils;
import android.util.Log;
import android.net.Uri;

public class PhoneLocationProvider extends ContentProvider {

    private static final String TAG = "PhoneLocationProvider";
    private static final boolean DEBUG = false;

    private static final String DATABASE_NAME = "phonelocation.db";
    private static final int DATABASE_VERSION = 2;

    private static final String LOCATION_TABLE = "location";

    private static final int PL_ALL = 0;
    private static final int PL_ID = 1;
    private static final int PL_NUMBER = 2;
    private static final int PL_PHONE_TYPE = 3;
    private static final int PL_LOCATION = 4;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURIMatcher.addURI("phonelocation", null, PL_ALL);
        sURIMatcher.addURI("phonelocation", "#", PL_ID);
        sURIMatcher.addURI("phonelocation", "bynumber/*", PL_NUMBER);
        sURIMatcher.addURI("phonelocation", "byphonetype/*", PL_PHONE_TYPE);
        sURIMatcher.addURI("phonelocation", "bylocation/*", PL_LOCATION);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {

        private Context mContext;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the database schema
            db.execSQL("CREATE TABLE " + LOCATION_TABLE +
                    "(_id INTEGER PRIMARY KEY," +
                    "number TEXT UNIQUE," +
                    "location TEXT," +
                    "phone_type INTEGER," +
                    "engine_type INTEGER," +
                    "user_mark TEXT," +
                    "update_time INTEGER);");
            db.execSQL("CREATE INDEX `number` ON `" + LOCATION_TABLE + "` (`number`);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1) {
                db.execSQL("CREATE INDEX `number` ON `" + LOCATION_TABLE + "` (`number`);");
            }
        }

    }

    private DatabaseHelper mOpenHelper;
    private BackupManager mBackupManager;

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        mBackupManager = new BackupManager(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(LOCATION_TABLE);

        // Generate the body of the query.
        int match = sURIMatcher.match(uri);
        switch (match) {
            case PL_ALL:
                break;
            case PL_ID:
                qb.appendWhere(PhoneLocation._ID + " = " + uri.getLastPathSegment());
                break;
            case PL_NUMBER:
                qb.appendWhere(PhoneLocation.NUMBER + " = \"" + uri.getLastPathSegment() + "\"");
                break;
            case PL_PHONE_TYPE:
                qb.appendWhere(PhoneLocation.PHONE_TYPE + " = " + uri.getLastPathSegment());
                break;
            case PL_LOCATION:
                qb.appendWhere(PhoneLocation.LOCATION + " = " + uri.getLastPathSegment());
                break;
            default:
                Log.e(TAG, "query: invalid request: " + uri);
                return null;
        }

        if (TextUtils.isEmpty(sortOrder)) {
            sortOrder = PhoneLocation.DEFAULT_SORT_ORDER;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = null;
        try {
            ret = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        } catch (SQLiteException e) {
            Log.e(TAG, "returning NULL cursor, query: " + uri, e);
        }

        // TODO: Does this need to be a URI for this provider.
        if (ret != null)
            ret.setNotificationUri(getContext().getContentResolver(), uri);
        return ret;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int match = sURIMatcher.match(uri);
        if (match != PL_ALL) {
            return null;
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowID = db.insertWithOnConflict(LOCATION_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (rowID <= 0) {
            return null;
        }

        if (DEBUG) Log.d(TAG, "inserted " + values + "rowID = " + rowID);
        notifyChange(uri);

        return ContentUris.withAppendedId(PhoneLocation.CONTENT_URI, rowID);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/phonelocation-entry";
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        int count = 0;
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (DEBUG) {
            Log.v(TAG, "Update uri=" + uri + ", match=" + match);
        }

        String uriNumber = match == PL_NUMBER ? uri.getLastPathSegment() : null;

        if (values == null) {
            Log.e(TAG, "Invalid update values " + values);
            return 0;
        }

        switch (match) {
            case PL_ALL:
                count = db.update(LOCATION_TABLE, values, where, whereArgs);
                break;
            case PL_NUMBER:
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException("Cannot update URI " + uri + " with a where clause");
                }
                db.beginTransaction();
                try {
                    count = db.update(LOCATION_TABLE, values, PhoneLocation.NUMBER + " = ?", new String[] {uriNumber});
                    if (count == 0) {
                        if (db.insert(LOCATION_TABLE, null, values) > 0) {
                            count = 1;
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                break;
            default:
                throw new UnsupportedOperationException("Cannot update that URI: " + uri);
        }

        if (DEBUG) Log.d(TAG, "Update result count " + count);

        if (count > 0) {
            notifyChange(uri);
        }

        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    private void notifyChange(Uri changeUri) {
        if (changeUri == null) {
            getContext().getContentResolver().notifyChange(PhoneLocation.CONTENT_URI, null);
        } else {
            getContext().getContentResolver().notifyChange(changeUri, null);
        }
        mBackupManager.dataChanged();
    }

}
