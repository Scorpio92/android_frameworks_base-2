/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.server.ProcessMap;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;

public final class ProcessTracker {
    static final String TAG = "ProcessTracker";

    public static final int STATE_NOTHING = -1;
    public static final int STATE_PERSISTENT = 0;
    public static final int STATE_TOP = 1;
    public static final int STATE_FOREGROUND = 2;
    public static final int STATE_VISIBLE = 3;
    public static final int STATE_PERCEPTIBLE = 4;
    public static final int STATE_BACKUP = 5;
    public static final int STATE_SERVICE = 6;
    public static final int STATE_HOME = 7;
    public static final int STATE_PREVIOUS = 8;
    public static final int STATE_CACHED = 9;
    public static final int STATE_COUNT = STATE_CACHED+1;

    static final int[] ALL_PROC_STATES = new int[] { STATE_PERSISTENT, STATE_TOP,
            STATE_FOREGROUND, STATE_VISIBLE, STATE_PERCEPTIBLE, STATE_BACKUP,
            STATE_SERVICE, STATE_HOME, STATE_PREVIOUS, STATE_CACHED
    };

    public static final int PSS_SAMPLE_COUNT = 0;
    public static final int PSS_MINIMUM = 1;
    public static final int PSS_AVERAGE = 2;
    public static final int PSS_MAXIMUM = 3;
    public static final int PSS_COUNT = PSS_MAXIMUM+1;

    public static final int ADJ_NOTHING = -1;
    public static final int ADJ_MEM_FACTOR_NORMAL = 0;
    public static final int ADJ_MEM_FACTOR_MODERATE = 1;
    public static final int ADJ_MEM_FACTOR_LOW = 2;
    public static final int ADJ_MEM_FACTOR_CRITICAL = 3;
    public static final int ADJ_MEM_FACTOR_COUNT = ADJ_MEM_FACTOR_CRITICAL+1;
    public static final int ADJ_SCREEN_MOD = ADJ_MEM_FACTOR_COUNT;
    public static final int ADJ_SCREEN_OFF = 0;
    public static final int ADJ_SCREEN_ON = ADJ_SCREEN_MOD;
    public static final int ADJ_COUNT = ADJ_SCREEN_ON*2;

    static final int[] ALL_SCREEN_ADJ = new int[] { ADJ_SCREEN_OFF, ADJ_SCREEN_ON };
    static final int[] ALL_MEM_ADJ = new int[] { ADJ_MEM_FACTOR_NORMAL, ADJ_MEM_FACTOR_MODERATE,
            ADJ_MEM_FACTOR_LOW, ADJ_MEM_FACTOR_CRITICAL };

    // Most data is kept in a sparse data structure: an integer array which integer
    // holds the type of the entry, and the identifier for a long array that data
    // exists in and the offset into the array to find it.  The constants below
    // define the encoding of that data in an integer.

    // Where the "type"/"state" part of the data appears in an offset integer.
    static int OFFSET_TYPE_SHIFT = 0;
    static int OFFSET_TYPE_MASK = 0xff;

    // Where the "which array" part of the data appears in an offset integer.
    static int OFFSET_ARRAY_SHIFT = 8;
    static int OFFSET_ARRAY_MASK = 0xff;

    // Where the "index into array" part of the data appears in an offset integer.
    static int OFFSET_INDEX_SHIFT = 16;
    static int OFFSET_INDEX_MASK = 0xffff;

    static final String[] STATE_NAMES = new String[] {
            "Persistent ", "Top        ", "Foreground ", "Visible    ", "Perceptible",
            "Backup     ", "Service    ", "Home       ", "Previous   ", "Cached     "
    };

    static final String[] ADJ_SCREEN_NAMES_CSV = new String[] {
            "off", "on"
    };

    static final String[] ADJ_MEM_NAMES_CSV = new String[] {
            "norm", "mod",  "low", "crit"
    };

    static final String[] STATE_NAMES_CSV = new String[] {
            "pers", "top", "fore", "vis", "percept",
            "backup", "service", "home", "prev", "cached"
    };

    static final String[] ADJ_SCREEN_TAGS = new String[] {
            "0", "1"
    };

    static final String[] ADJ_MEM_TAGS = new String[] {
            "n", "m",  "l", "c"
    };

    static final String[] STATE_TAGS = new String[] {
            "y", "t", "f", "v", "r",
            "b", "s", "h", "p", "c"
    };

    static final String CSV_SEP = "\t";

    static long WRITE_PERIOD = 30*60*1000;      // Write file every 30 minutes or so.

    final File mBaseDir;
    final AtomicFile mFile;
    final State mState = new State();
    long mLastWriteTime;
    boolean mShuttingDown;

    final Object mPendingWriteLock = new Object();
    Parcel mPendingWrite;
    final ReentrantLock mWriteLock = new ReentrantLock();

    public static final class ProcessState {
        final State mState;
        final ProcessState mCommonProcess;
        final String mPackage;
        final int mUid;
        final String mName;

        int[] mDurationsTable;
        int mDurationsTableSize;

        //final long[] mDurations = new long[STATE_COUNT*ADJ_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        int mLastPssState = STATE_NOTHING;
        long mLastPssTime;
        int[] mPssTable;
        int mPssTableSize;

        int mNumExcessiveWake;
        int mNumExcessiveCpu;

        boolean mMultiPackage;

        long mTmpTotalTime;

        /**
         * Create a new top-level process state, for the initial case where there is only
         * a single package running in a process.  The initial state is not running.
         */
        public ProcessState(State state, String pkg, int uid, String name) {
            mState = state;
            mCommonProcess = this;
            mPackage = pkg;
            mUid = uid;
            mName = name;
        }

        /**
         * Create a new per-package process state for an existing top-level process
         * state.  The current running state of the top-level process is also copied,
         * marked as started running at 'now'.
         */
        public ProcessState(ProcessState commonProcess, String pkg, int uid, String name,
                long now) {
            mState = commonProcess.mState;
            mCommonProcess = commonProcess;
            mPackage = pkg;
            mUid = uid;
            mName = name;
            mCurState = commonProcess.mCurState;
            mStartTime = now;
        }

        ProcessState clone(String pkg, long now) {
            ProcessState pnew = new ProcessState(this, pkg, mUid, mName, now);
            if (mDurationsTable != null) {
                mState.mAddLongTable = new int[mDurationsTable.length];
                mState.mAddLongTableSize = 0;
                for (int i=0; i<mDurationsTableSize; i++) {
                    int origEnt = mDurationsTable[i];
                    int type = (origEnt>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                    int newOff = mState.addLongData(i, type, 1);
                    mState.mAddLongTable[i] = newOff | type;
                    mState.setLong(newOff, 0, mState.getLong(origEnt, 0));
                }
                pnew.mDurationsTable = mState.mAddLongTable;
                pnew.mDurationsTableSize = mState.mAddLongTableSize;
            }
            /*
            if (mPssTable != null) {
                mState.mAddLongTable = new int[mPssTable.length];
                mState.mAddLongTableSize = 0;
                for (int i=0; i<mPssTableSize; i++) {
                    int origEnt = mPssTable[i];
                    int type = (origEnt>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                    int newOff = mState.addLongData(i, type, PSS_COUNT);
                    mState.mAddLongTable[i] = newOff | type;
                    for (int j=0; j<PSS_COUNT; j++) {
                        mState.setLong(newOff, j, mState.getLong(origEnt, j));
                    }
                }
                pnew.mPssTable = mState.mAddLongTable;
                pnew.mPssTableSize = mState.mAddLongTableSize;
            }
            */
            pnew.mNumExcessiveWake = mNumExcessiveWake;
            pnew.mNumExcessiveCpu = mNumExcessiveCpu;
            return pnew;
        }

        void writeToParcel(Parcel out, long now) {
            commitStateTime(now);
            out.writeInt(mMultiPackage ? 1 : 0);
            out.writeInt(mDurationsTableSize);
            for (int i=0; i<mDurationsTableSize; i++) {
                out.writeInt(mDurationsTable[i]);
            }
            out.writeInt(mPssTableSize);
            for (int i=0; i<mPssTableSize; i++) {
                out.writeInt(mPssTable[i]);
            }
            out.writeInt(mNumExcessiveWake);
            out.writeInt(mNumExcessiveCpu);
        }

        private int[] readTable(Parcel in, String what) {
            int size = in.readInt();
            if (size < 0) {
                Slog.w(TAG, "Ignoring existing stats; bad " + what + " table size: " + size);
                return null;
            }
            int[] table = new int[size];
            for (int i=0; i<size; i++) {
                table[i] = in.readInt();
                if (!mState.validateLongOffset(table[i])) {
                    Slog.w(TAG, "Ignoring existing stats; bad " + what + " table entry: 0x"
                            + Integer.toHexString(table[i]));
                    return null;
                }
            }
            return table;
        }

        boolean readFromParcel(Parcel in) {
            mMultiPackage = in.readInt() != 0;
            mDurationsTable = readTable(in, "durations");
            if (mDurationsTable == null) {
                return false;
            }
            mDurationsTableSize = mDurationsTable.length;
            mPssTable = readTable(in, "pss");
            if (mPssTable == null) {
                return false;
            }
            mPssTableSize = mPssTable.length;
            mNumExcessiveWake = in.readInt();
            mNumExcessiveCpu = in.readInt();
            return true;
        }

        public void setState(int state, int memFactor, long now,
                ArrayMap<String, ProcessTracker.ProcessState> pkgList) {
            if (state != STATE_NOTHING) {
                state += memFactor*STATE_COUNT;
            }

            // First update the common process.
            mCommonProcess.setState(state, now);

            // If the common process is not multi-package, there is nothing else to do.
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).setState(state, now);
            }
        }

        void setState(int state, long now) {
            if (mCurState != state) {
                commitStateTime(now);
                mCurState = state;
            }
        }

        void commitStateTime(long now) {
            if (mCurState != STATE_NOTHING) {
                long dur = now - mStartTime;
                int idx = State.binarySearch(mDurationsTable, mDurationsTableSize, mCurState);
                int off;
                if (idx >= 0) {
                    off = mDurationsTable[idx];
                } else {
                    mState.mAddLongTable = mDurationsTable;
                    mState.mAddLongTableSize = mDurationsTableSize;
                    off = mState.addLongData(~idx, mCurState, 1);
                    mDurationsTable = mState.mAddLongTable;
                    mDurationsTableSize = mState.mAddLongTableSize;
                }
                long[] longs = mState.mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
                longs[(off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK] += dur;
            }
            mStartTime = now;
        }

        public void addPss(long pss, boolean always) {
            if (!always) {
                if (mLastPssState == mCurState && SystemClock.uptimeMillis()
                        < (mLastPssTime+(30*1000))) {
                    return;
                }
            }
            mLastPssState = mCurState;
            mLastPssTime = SystemClock.uptimeMillis();
            if (mCurState != STATE_NOTHING) {
                int idx = State.binarySearch(mPssTable, mPssTableSize, mCurState);
                int off;
                if (idx >= 0) {
                    off = mPssTable[idx];
                } else {
                    mState.mAddLongTable = mPssTable;
                    mState.mAddLongTableSize = mPssTableSize;
                    off = mState.addLongData(~idx, mCurState, PSS_COUNT);
                    mPssTable = mState.mAddLongTable;
                    mPssTableSize = mState.mAddLongTableSize;
                }
                long[] longs = mState.mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
                idx = (off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK;
                long count = longs[idx+PSS_SAMPLE_COUNT];
                if (count == 0) {
                    longs[idx+PSS_SAMPLE_COUNT] = 1;
                    longs[idx+PSS_MINIMUM] = pss;
                    longs[idx+PSS_AVERAGE] = pss;
                    longs[idx+PSS_MAXIMUM] = pss;
                } else {
                    longs[idx+PSS_SAMPLE_COUNT] = count+1;
                    if (longs[idx+PSS_MINIMUM] > pss) {
                        longs[idx+PSS_MINIMUM] = pss;
                    }
                    longs[idx+PSS_AVERAGE] = (long)( ((longs[idx+PSS_AVERAGE]*(double)count)+pss)
                            / (count+1) );
                    if (longs[idx+PSS_MAXIMUM] < pss) {
                        longs[idx+PSS_MAXIMUM] = pss;
                    }
                }
            }
        }

        public void reportExcessiveWake(ArrayMap<String, ProcessTracker.ProcessState> pkgList) {
            mCommonProcess.mNumExcessiveWake++;
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).mNumExcessiveWake++;
            }
        }

        public void reportExcessiveCpu(ArrayMap<String, ProcessTracker.ProcessState> pkgList) {
            mCommonProcess.mNumExcessiveCpu++;
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).mNumExcessiveCpu++;
            }
        }

        private ProcessState pullFixedProc(ArrayMap<String, ProcessTracker.ProcessState> pkgList,
                int index) {
            ProcessState proc = pkgList.valueAt(index);
            if (proc.mMultiPackage) {
                // The array map is still pointing to a common process state
                // that is now shared across packages.  Update it to point to
                // the new per-package state.
                proc = mState.mPackages.get(pkgList.keyAt(index),
                        proc.mUid).mProcesses.get(proc.mName);
                if (proc == null) {
                    throw new IllegalStateException("Didn't create per-package process");
                }
                pkgList.setValueAt(index, proc);
            }
            return proc;
        }

        long getDuration(int state, long now) {
            int idx = State.binarySearch(mDurationsTable, mDurationsTableSize, state);
            long time = idx >= 0 ? mState.getLong(mDurationsTable[idx], 0) : 0;
            if (mCurState == state) {
                time += now - mStartTime;
            }
            return time;
        }

        long getPssSampleCount(int state) {
            int idx = State.binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mState.getLong(mPssTable[idx], PSS_SAMPLE_COUNT) : 0;
        }

        long getPssMinimum(int state) {
            int idx = State.binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mState.getLong(mPssTable[idx], PSS_MINIMUM) : 0;
        }

        long getPssAverage(int state) {
            int idx = State.binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mState.getLong(mPssTable[idx], PSS_AVERAGE) : 0;
        }

        long getPssMaximum(int state) {
            int idx = State.binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mState.getLong(mPssTable[idx], PSS_MAXIMUM) : 0;
        }
    }

    public static final class ServiceState {
        final long[] mStartedDurations = new long[ADJ_COUNT];
        int mStartedCount;
        int mStartedState = STATE_NOTHING;
        long mStartedStartTime;

        final long[] mBoundDurations = new long[ADJ_COUNT];
        int mBoundCount;
        int mBoundState = STATE_NOTHING;
        long mBoundStartTime;

        final long[] mExecDurations = new long[ADJ_COUNT];
        int mExecCount;
        int mExecState = STATE_NOTHING;
        long mExecStartTime;

        void writeToParcel(Parcel out, long now) {
            if (mStartedState != STATE_NOTHING) {
                mStartedDurations[mStartedState] += now - mStartedStartTime;
                mStartedStartTime = now;
            }
            if (mBoundState != STATE_NOTHING) {
                mBoundDurations[mBoundState] += now - mBoundStartTime;
                mBoundStartTime = now;
            }
            if (mExecState != STATE_NOTHING) {
                mExecDurations[mExecState] += now - mExecStartTime;
                mExecStartTime = now;
            }
            out.writeLongArray(mStartedDurations);
            out.writeInt(mStartedCount);
            out.writeLongArray(mBoundDurations);
            out.writeInt(mBoundCount);
            out.writeLongArray(mExecDurations);
            out.writeInt(mExecCount);
        }

        boolean readFromParcel(Parcel in) {
            in.readLongArray(mStartedDurations);
            mStartedCount = in.readInt();
            in.readLongArray(mBoundDurations);
            mBoundCount = in.readInt();
            in.readLongArray(mExecDurations);
            mExecCount = in.readInt();
            return true;
        }

        public void setStarted(boolean started, int memFactor, long now) {
            int state = started ? memFactor : STATE_NOTHING;
            if (mStartedState != state) {
                if (mStartedState != STATE_NOTHING) {
                    mStartedDurations[mStartedState] += now - mStartedStartTime;
                } else if (started) {
                    mStartedCount++;
                }
                mStartedState = state;
                mStartedStartTime = now;
            }
        }

        public void setBound(boolean bound, int memFactor, long now) {
            int state = bound ? memFactor : STATE_NOTHING;
            if (mBoundState != state) {
                if (mBoundState != STATE_NOTHING) {
                    mBoundDurations[mBoundState] += now - mBoundStartTime;
                } else if (bound) {
                    mBoundCount++;
                }
                mBoundState = state;
                mBoundStartTime = now;
            }
        }

        public void setExecuting(boolean executing, int memFactor, long now) {
            int state = executing ? memFactor : STATE_NOTHING;
            if (mExecState != state) {
                if (mExecState != STATE_NOTHING) {
                    mExecDurations[mExecState] += now - mExecStartTime;
                } else if (executing) {
                    mExecCount++;
                }
                mExecState = state;
                mExecStartTime = now;
            }
        }
    }

    public static final class PackageState {
        final ArrayMap<String, ProcessState> mProcesses = new ArrayMap<String, ProcessState>();
        final ArrayMap<String, ServiceState> mServices = new ArrayMap<String, ServiceState>();
        final int mUid;

        public PackageState(int uid) {
            mUid = uid;
        }
    }

    static final class State {
        // Current version of the parcel format.
        public static final int PARCEL_VERSION = 1;
        // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
        private static final int MAGIC = 0x50535453;

        long mTimePeriodStart;
        long mTimePeriodEnd;

        final ProcessMap<PackageState> mPackages = new ProcessMap<PackageState>();
        final ProcessMap<ProcessState> mProcesses = new ProcessMap<ProcessState>();
        final long[] mMemFactorDurations = new long[ADJ_COUNT];
        int mMemFactor = STATE_NOTHING;
        long mStartTime;

        static final int LONGS_SIZE = 4096;
        final ArrayList<long[]> mLongs = new ArrayList<long[]>();
        int mNextLong;

        int[] mAddLongTable;
        int mAddLongTableSize;

        State() {
            reset();
        }

        void reset() {
            mTimePeriodStart = mTimePeriodEnd = System.currentTimeMillis();
            mPackages.getMap().clear();
            mProcesses.getMap().clear();
            mLongs.clear();
            mLongs.add(new long[LONGS_SIZE]);
            mNextLong = 0;
            Arrays.fill(mMemFactorDurations, 0);
            mMemFactor = STATE_NOTHING;
            mStartTime = 0;
        }

        void writeToParcel(Parcel out) {
            long now = SystemClock.uptimeMillis();
            out.writeInt(MAGIC);
            out.writeInt(PARCEL_VERSION);
            out.writeInt(STATE_COUNT);
            out.writeInt(ADJ_COUNT);
            out.writeInt(PSS_COUNT);
            out.writeInt(LONGS_SIZE);

            out.writeLong(mTimePeriodStart);
            out.writeLong(mTimePeriodEnd);

            out.writeInt(mLongs.size());
            out.writeInt(mNextLong);
            for (int i=0; i<(mLongs.size()-1); i++) {
                out.writeLongArray(mLongs.get(i));
            }
            long[] lastLongs = mLongs.get(mLongs.size()-1);
            for (int i=0; i<mNextLong; i++) {
                out.writeLong(lastLongs[i]);
            }

            if (mMemFactor != STATE_NOTHING) {
                mMemFactorDurations[mMemFactor] += now - mStartTime;
                mStartTime = now;
            }
            out.writeLongArray(mMemFactorDurations);

            ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
            final int NPROC = procMap.size();
            out.writeInt(NPROC);
            for (int ip=0; ip<NPROC; ip++) {
                out.writeString(procMap.keyAt(ip));
                SparseArray<ProcessState> uids = procMap.valueAt(ip);
                final int NUID = uids.size();
                out.writeInt(NUID);
                for (int iu=0; iu<NUID; iu++) {
                    out.writeInt(uids.keyAt(iu));
                    ProcessState proc = uids.valueAt(iu);
                    out.writeString(proc.mPackage);
                    proc.writeToParcel(out, now);
                }
            }
            ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
            final int NPKG = pkgMap.size();
            out.writeInt(NPKG);
            for (int ip=0; ip<NPKG; ip++) {
                out.writeString(pkgMap.keyAt(ip));
                SparseArray<PackageState> uids = pkgMap.valueAt(ip);
                final int NUID = uids.size();
                out.writeInt(NUID);
                for (int iu=0; iu<NUID; iu++) {
                    out.writeInt(uids.keyAt(iu));
                    PackageState pkgState = uids.valueAt(iu);
                    final int NPROCS = pkgState.mProcesses.size();
                    out.writeInt(NPROCS);
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        out.writeString(pkgState.mProcesses.keyAt(iproc));
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (proc.mCommonProcess == proc) {
                            // This is the same as the common process we wrote above.
                            out.writeInt(0);
                        } else {
                            // There is separate data for this package's process.
                            out.writeInt(1);
                            proc.writeToParcel(out, now);
                        }
                    }
                    final int NSRVS = pkgState.mServices.size();
                    out.writeInt(NSRVS);
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        out.writeString(pkgState.mServices.keyAt(isvc));
                        ServiceState svc = pkgState.mServices.valueAt(isvc);
                        svc.writeToParcel(out, now);
                    }
                }
            }
        }

        private boolean readCheckedInt(Parcel in, int val, String what) {
            int got;
            if ((got=in.readInt()) != val) {
                Slog.w(TAG, "Ignoring existing stats; bad " + ": " + got);
                return false;
            }
            return true;
        }

        void readFromParcel(Parcel in) {
            reset();
            if (!readCheckedInt(in, MAGIC, "magic number")) {
                return;
            }
            if (!readCheckedInt(in, PARCEL_VERSION, "version")) {
                return;
            }
            if (!readCheckedInt(in, STATE_COUNT, "state count")) {
                return;
            }
            if (!readCheckedInt(in, ADJ_COUNT, "adj count")) {
                return;
            }
            if (!readCheckedInt(in, PSS_COUNT, "pss count")) {
                return;
            }
            if (!readCheckedInt(in, LONGS_SIZE, "longs size")) {
                return;
            }

            mTimePeriodStart = in.readLong();
            mTimePeriodEnd = in.readLong();

            int NLONGS = in.readInt();
            int NEXTLONG = in.readInt();
            for (int i=0; i<(NLONGS-1); i++) {
                while (i >= mLongs.size()) {
                    mLongs.add(new long[LONGS_SIZE]);
                }
                in.readLongArray(mLongs.get(i));
            }
            long[] longs = new long[LONGS_SIZE];
            mNextLong = NEXTLONG;
            for (int i=0; i<NEXTLONG; i++) {
                longs[i] = in.readLong();
            }
            mLongs.add(longs);

            in.readLongArray(mMemFactorDurations);

            int NPROC = in.readInt();
            if (NPROC < 0) {
                Slog.w(TAG, "Ignoring existing stats; bad process count: " + NPROC);
                return;
            }
            while (NPROC > 0) {
                NPROC--;
                String procName = in.readString();
                if (procName == null) {
                    Slog.w(TAG, "Ignoring existing stats; bad process name");
                    return;
                }
                int NUID = in.readInt();
                if (NUID < 0) {
                    Slog.w(TAG, "Ignoring existing stats; bad uid count: " + NUID);
                    return;
                }
                while (NUID > 0) {
                    NUID--;
                    int uid = in.readInt();
                    if (uid < 0) {
                        Slog.w(TAG, "Ignoring existing stats; bad uid: " + uid);
                        return;
                    }
                    String pkgName = in.readString();
                    if (pkgName == null) {
                        Slog.w(TAG, "Ignoring existing stats; bad process package name");
                        return;
                    }
                    ProcessState proc = new ProcessState(this, pkgName, uid, procName);
                    if (!proc.readFromParcel(in)) {
                        return;
                    }
                    mProcesses.put(procName, uid, proc);
                }
            }

            int NPKG = in.readInt();
            if (NPKG < 0) {
                Slog.w(TAG, "Ignoring existing stats; bad package count: " + NPKG);
                return;
            }
            while (NPKG > 0) {
                NPKG--;
                String pkgName = in.readString();
                if (pkgName == null) {
                    Slog.w(TAG, "Ignoring existing stats; bad package name");
                    return;
                }
                int NUID = in.readInt();
                if (NUID < 0) {
                    Slog.w(TAG, "Ignoring existing stats; bad uid count: " + NUID);
                    return;
                }
                while (NUID > 0) {
                    NUID--;
                    int uid = in.readInt();
                    if (uid < 0) {
                        Slog.w(TAG, "Ignoring existing stats; bad uid: " + uid);
                        return;
                    }
                    PackageState pkgState = new PackageState(uid);
                    int NPROCS = in.readInt();
                    if (NPROCS < 0) {
                        Slog.w(TAG, "Ignoring existing stats; bad package process count: " + NPROCS);
                        return;
                    }
                    while (NPROCS > 0) {
                        NPROCS--;
                        String procName = in.readString();
                        if (procName == null) {
                            Slog.w(TAG, "Ignoring existing stats; bad package process name");
                            return;
                        }
                        int hasProc = in.readInt();
                        if (hasProc != 0) {
                            // The process for this package is unique to the package; we
                            // need to load it.  We don't need to do anything about it if
                            // it is not unique because if someone later looks for it
                            // they will find and use it from the global procs.
                            ProcessState commonProc = mProcesses.get(procName, uid);
                            if (commonProc == null) {
                                Slog.w(TAG, "Ignoring existing stats; no common proc: " + procName);
                                return;
                            }
                            ProcessState proc = new ProcessState(commonProc, pkgName, uid,
                                    procName, 0);
                            if (!proc.readFromParcel(in)) {
                                return;
                            }
                            pkgState.mProcesses.put(procName, proc);
                        }
                    }
                    int NSRVS = in.readInt();
                    if (NSRVS < 0) {
                        Slog.w(TAG, "Ignoring existing stats; bad package service count: " + NSRVS);
                        return;
                    }
                    while (NSRVS > 0) {
                        NSRVS--;
                        String serviceName = in.readString();
                        if (serviceName == null) {
                            Slog.w(TAG, "Ignoring existing stats; bad package service name");
                            return;
                        }
                        ServiceState serv = new ServiceState();
                        if (!serv.readFromParcel(in)) {
                            return;
                        }
                        pkgState.mServices.put(serviceName, serv);
                    }
                }
            }
        }

        int addLongData(int index, int type, int num) {
            int tableLen = mAddLongTable != null ? mAddLongTable.length : 0;
            if (mAddLongTableSize >= tableLen) {
                int newSize = ArrayUtils.idealIntArraySize(tableLen + 1);
                int[] newTable = new int[newSize];
                if (tableLen > 0) {
                    System.arraycopy(mAddLongTable, 0, newTable, 0, tableLen);
                }
                mAddLongTable = newTable;
            }
            if (mAddLongTableSize > 0 && mAddLongTableSize - index != 0) {
                System.arraycopy(mAddLongTable, index, mAddLongTable, index + 1,
                        mAddLongTableSize - index);
            }
            int off = allocLongData(num);
            mAddLongTable[index] = type | off;
            mAddLongTableSize++;
            return off;
        }

        int allocLongData(int num) {
            int whichLongs = mLongs.size()-1;
            long[] longs = mLongs.get(whichLongs);
            if (mNextLong + num > longs.length) {
                longs = new long[LONGS_SIZE];
                mLongs.add(longs);
                whichLongs++;
                mNextLong = 0;
            }
            int off = (whichLongs<<OFFSET_ARRAY_SHIFT) | (mNextLong<<OFFSET_INDEX_SHIFT);
            mNextLong += num;
            return off;
        }

        boolean validateLongOffset(int off) {
            int arr = (off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK;
            if (arr >= mLongs.size()) {
                return false;
            }
            int idx = (off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK;
            if (idx >= LONGS_SIZE) {
                return false;
            }
            return true;
        }

        void setLong(int off, int index, long value) {
            long[] longs = mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
            longs[index + ((off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK)] = value;
        }

        long getLong(int off, int index) {
            long[] longs = mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
            return longs[index + ((off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK)];
        }

        static int binarySearch(int[] array, int size, int value) {
            int lo = 0;
            int hi = size - 1;

            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int midVal = (array[mid] >> OFFSET_TYPE_SHIFT) & OFFSET_TYPE_MASK;

                if (midVal < value) {
                    lo = mid + 1;
                } else if (midVal > value) {
                    hi = mid - 1;
                } else {
                    return mid;  // value found
                }
            }
            return ~lo;  // value not present
        }

        PackageState getPackageStateLocked(String packageName, int uid) {
            PackageState as = mPackages.get(packageName, uid);
            if (as != null) {
                return as;
            }
            as = new PackageState(uid);
            mPackages.put(packageName, uid, as);
            return as;
        }

        ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
            final PackageState pkgState = getPackageStateLocked(packageName, uid);
            ProcessState ps = pkgState.mProcesses.get(processName);
            if (ps != null) {
                return ps;
            }
            ProcessState commonProc = mProcesses.get(processName, uid);
            if (commonProc == null) {
                commonProc = new ProcessState(this, packageName, uid, processName);
                mProcesses.put(processName, uid, commonProc);
            }
            if (!commonProc.mMultiPackage) {
                if (packageName.equals(commonProc.mPackage)) {
                    // This common process is not in use by multiple packages, and
                    // is for the calling package, so we can just use it directly.
                    ps = commonProc;
                } else {
                    // This common process has not been in use by multiple packages,
                    // but it was created for a different package than the caller.
                    // We need to convert it to a multi-package process.
                    commonProc.mMultiPackage = true;
                    // The original package it was created for now needs to point
                    // to its own copy.
                    long now = SystemClock.uptimeMillis();
                    pkgState.mProcesses.put(commonProc.mPackage, commonProc.clone(
                            commonProc.mPackage, now));
                    ps = new ProcessState(commonProc, packageName, uid, processName, now);
                }
            } else {
                // The common process is for multiple packages, we need to create a
                // separate object for the per-package data.
                ps = new ProcessState(commonProc, packageName, uid, processName,
                        SystemClock.uptimeMillis());
            }
            pkgState.mProcesses.put(processName, ps);
            return ps;
        }

        void dump(PrintWriter pw, String reqPackage, boolean dumpAll) {
            final long now = SystemClock.uptimeMillis();
            ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
            boolean printedHeader = false;
            for (int ip=0; ip<pkgMap.size(); ip++) {
                String pkgName = pkgMap.keyAt(ip);
                if (reqPackage != null && !reqPackage.equals(pkgName)) {
                    continue;
                }
                SparseArray<PackageState> uids = pkgMap.valueAt(ip);
                for (int iu=0; iu<uids.size(); iu++) {
                    int uid = uids.keyAt(iu);
                    PackageState pkgState = uids.valueAt(iu);
                    final int NPROCS = pkgState.mProcesses.size();
                    final int NSRVS = pkgState.mServices.size();
                    if (NPROCS > 0 || NSRVS > 0) {
                        if (!printedHeader) {
                            pw.println("Per-Package Process Stats:");
                            printedHeader = true;
                        }
                        pw.print("  * "); pw.print(pkgName); pw.print(" / ");
                                UserHandle.formatUid(pw, uid); pw.println(":");
                    }
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        pw.print("      Process ");
                        pw.print(pkgState.mProcesses.keyAt(iproc));
                        pw.print(" (");
                        pw.print(proc.mDurationsTableSize);
                        pw.print(" entries)");
                        pw.println(":");
                        dumpProcessState(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                                ALL_PROC_STATES, now);
                        dumpProcessPss(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                                ALL_PROC_STATES);
                    }
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        pw.print("      Service ");
                        pw.print(pkgState.mServices.keyAt(isvc));
                        pw.println(":");
                        ServiceState svc = pkgState.mServices.valueAt(isvc);
                        if (svc.mStartedCount != 0) {
                            pw.print("        Started op count "); pw.print(svc.mStartedCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mStartedDurations, svc.mStartedState,
                                    svc.mStartedStartTime, now);
                        }
                        if (svc.mBoundCount != 0) {
                            pw.print("        Bound op count "); pw.print(svc.mBoundCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mBoundDurations, svc.mBoundState,
                                    svc.mBoundStartTime, now);
                        }
                        if (svc.mExecCount != 0) {
                            pw.print("        Executing op count "); pw.print(svc.mExecCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mExecDurations, svc.mExecState,
                                    svc.mExecStartTime, now);
                        }
                    }
                }
            }

            dumpFilteredProcesses(pw, "Processes running while critical mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_CRITICAL},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while low mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_LOW},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while moderate mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_MODERATE},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while normal mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_NORMAL},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            pw.println();
            pw.println("Run time Stats:");
            dumpSingleTime(pw, "  ", mMemFactorDurations, mMemFactor, mStartTime, now);
            if (dumpAll) {
                pw.println();
                pw.println("Internal state:");
                pw.print("  Num long arrays: "); pw.println(mLongs.size());
                pw.print("  Next long entry: "); pw.println(mNextLong);
            }
        }

        void dumpFilteredProcesses(PrintWriter pw, String header, String prefix,
                int[] screenStates, int[] memStates, int[] procStates, long now, String reqPackage) {
            ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                    procStates, now, reqPackage);
            if (procs.size() > 0) {
                pw.println();
                pw.println(header);
                dumpProcessList(pw, prefix, procs, screenStates, memStates, procStates, now);
            }
        }

        ArrayList<ProcessState> collectProcessesLocked(int[] screenStates, int[] memStates,
                int[] procStates, long now, String reqPackage) {
            ArraySet<ProcessState> foundProcs = new ArraySet<ProcessState>();
            ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
            for (int ip=0; ip<pkgMap.size(); ip++) {
                if (reqPackage != null && !reqPackage.equals(pkgMap.keyAt(ip))) {
                    continue;
                }
                SparseArray<PackageState> procs = pkgMap.valueAt(ip);
                for (int iu=0; iu<procs.size(); iu++) {
                    PackageState state = procs.valueAt(iu);
                    for (int iproc=0; iproc<state.mProcesses.size(); iproc++) {
                        ProcessState proc = state.mProcesses.valueAt(iproc);
                        foundProcs.add(proc.mCommonProcess);
                    }
                }
            }
            ArrayList<ProcessState> outProcs = new ArrayList<ProcessState>(foundProcs.size());
            for (int i=0; i<foundProcs.size(); i++) {
                ProcessState proc = foundProcs.valueAt(i);
                if (computeProcessTimeLocked(proc, screenStates, memStates,
                        procStates, now) > 0) {
                    outProcs.add(proc);
                }
            }
            Collections.sort(outProcs, new Comparator<ProcessState>() {
                @Override
                public int compare(ProcessState lhs, ProcessState rhs) {
                    if (lhs.mTmpTotalTime < rhs.mTmpTotalTime) {
                        return -1;
                    } else if (lhs.mTmpTotalTime > rhs.mTmpTotalTime) {
                        return 1;
                    }
                    return 0;
                }
            });
            return outProcs;
        }

        void dumpCheckin(PrintWriter pw, String reqPackage) {
            final long now = SystemClock.uptimeMillis();
            ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
            pw.println("vers,1");
            for (int ip=0; ip<pkgMap.size(); ip++) {
                String pkgName = pkgMap.keyAt(ip);
                if (reqPackage != null && !reqPackage.equals(pkgName)) {
                    continue;
                }
                SparseArray<PackageState> uids = pkgMap.valueAt(ip);
                for (int iu=0; iu<uids.size(); iu++) {
                    int uid = uids.keyAt(iu);
                    PackageState pkgState = uids.valueAt(iu);
                    final int NPROCS = pkgState.mProcesses.size();
                    final int NSRVS = pkgState.mServices.size();
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        pw.print("pkgproc,");
                        pw.print(pkgName);
                        pw.print(",");
                        pw.print(uid);
                        pw.print(",");
                        pw.print(pkgState.mProcesses.keyAt(iproc));
                        dumpAllProcessStateCheckin(pw, proc, now);
                        pw.println();
                        if (proc.mPssTableSize > 0) {
                            pw.print("pkgpss,");
                            pw.print(pkgName);
                            pw.print(",");
                            pw.print(uid);
                            pw.print(",");
                            pw.print(pkgState.mProcesses.keyAt(iproc));
                            dumpAllProcessPssCheckin(pw, proc);
                            pw.println();
                        }
                        if (proc.mNumExcessiveWake > 0 || proc.mNumExcessiveCpu > 0) {
                            pw.print("pkgkills,");
                            pw.print(pkgName);
                            pw.print(",");
                            pw.print(uid);
                            pw.print(",");
                            pw.print(pkgState.mProcesses.keyAt(iproc));
                            pw.print(",");
                            pw.print(proc.mNumExcessiveWake);
                            pw.print(",");
                            pw.print(proc.mNumExcessiveCpu);
                            pw.println();
                        }
                    }
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        String serviceName = pkgState.mServices.keyAt(isvc);
                        ServiceState svc = pkgState.mServices.valueAt(isvc);
                        dumpServiceTimeCheckin(pw, "pkgsvc-start", pkgName, uid, serviceName,
                                svc, svc.mStartedCount, svc.mStartedDurations, svc.mStartedState,
                                svc.mStartedStartTime, now);
                        dumpServiceTimeCheckin(pw, "pkgsvc-bound", pkgName, uid, serviceName,
                                svc, svc.mBoundCount, svc.mBoundDurations, svc.mBoundState,
                                svc.mBoundStartTime, now);
                        dumpServiceTimeCheckin(pw, "pkgsvc-exec", pkgName, uid, serviceName,
                                svc, svc.mExecCount, svc.mExecDurations, svc.mExecState,
                                svc.mExecStartTime, now);
                    }
                }
            }

            ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
            for (int ip=0; ip<procMap.size(); ip++) {
                String procName = procMap.keyAt(ip);
                SparseArray<ProcessState> uids = procMap.valueAt(ip);
                for (int iu=0; iu<uids.size(); iu++) {
                    int uid = uids.keyAt(iu);
                    ProcessState procState = uids.valueAt(iu);
                    if (procState.mDurationsTableSize > 0) {
                        pw.print("proc,");
                        pw.print(procName);
                        pw.print(",");
                        pw.print(uid);
                        dumpAllProcessStateCheckin(pw, procState, now);
                        pw.println();
                    }
                    if (procState.mPssTableSize > 0) {
                        pw.print("pss,");
                        pw.print(procName);
                        pw.print(",");
                        pw.print(uid);
                        dumpAllProcessPssCheckin(pw, procState);
                        pw.println();
                    }
                    if (procState.mNumExcessiveWake > 0 || procState.mNumExcessiveCpu > 0) {
                        pw.print("kills,");
                        pw.print(procName);
                        pw.print(",");
                        pw.print(uid);
                        pw.print(",");
                        pw.print(procState.mNumExcessiveWake);
                        pw.print(",");
                        pw.print(procState.mNumExcessiveCpu);
                        pw.println();
                    }
                }
            }
            pw.print("total");
            dumpAdjTimesCheckin(pw, ",", mMemFactorDurations, mMemFactor,
                    mStartTime, now);
            pw.println();
        }
    }

    public ProcessTracker(File file) {
        mBaseDir = file;
        mBaseDir.mkdirs();
        mFile = new AtomicFile(new File(file, "current.bin"));
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
        return mState.getProcessStateLocked(packageName, uid, processName);
    }

    public ServiceState getServiceStateLocked(String packageName, int uid, String className) {
        final PackageState as = mState.getPackageStateLocked(packageName, uid);
        ServiceState ss = as.mServices.get(className);
        if (ss != null) {
            return ss;
        }
        ss = new ServiceState();
        as.mServices.put(className, ss);
        return ss;
    }

    public boolean setMemFactorLocked(int memFactor, boolean screenOn, long now) {
        if (screenOn) {
            memFactor += ADJ_SCREEN_ON;
        }
        if (memFactor != mState.mMemFactor) {
            if (mState.mMemFactor != STATE_NOTHING) {
                mState.mMemFactorDurations[mState.mMemFactor] += now - mState.mStartTime;
            }
            mState.mMemFactor = memFactor;
            mState.mStartTime = now;
            ArrayMap<String, SparseArray<PackageState>> pmap = mState.mPackages.getMap();
            for (int i=0; i<pmap.size(); i++) {
                SparseArray<PackageState> uids = pmap.valueAt(i);
                for (int j=0; j<uids.size(); j++) {
                    PackageState pkg = uids.valueAt(j);
                    ArrayMap<String, ServiceState> services = pkg.mServices;
                    for (int k=0; k<services.size(); k++) {
                        ServiceState service = services.valueAt(k);
                        if (service.mStartedState != STATE_NOTHING) {
                            service.setStarted(true, memFactor, now);
                        }
                        if (service.mBoundState != STATE_NOTHING) {
                            service.setBound(true, memFactor, now);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public int getMemFactorLocked() {
        return mState.mMemFactor != STATE_NOTHING ? mState.mMemFactor : 0;
    }

    static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        int pos = 0;
        int avail = stream.available();
        byte[] data = new byte[avail];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            //Log.i("foo", "Read " + amt + " bytes at " + pos
            //        + " of avail " + data.length);
            if (amt <= 0) {
                //Log.i("foo", "**** FINISHED READING: pos=" + pos
                //        + " len=" + data.length);
                return data;
            }
            pos += amt;
            avail = stream.available();
            if (avail > data.length-pos) {
                byte[] newData = new byte[pos+avail];
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    public void readLocked() {
        try {
            FileInputStream stream = mFile.openRead();

            byte[] raw = readFully(stream);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, raw.length);
            in.setDataPosition(0);
            stream.close();

            mState.readFromParcel(in);
        } catch(Throwable e) {
            Slog.e(TAG, "Error reading process statistics", e);
        }

    }

    public boolean shouldWriteNowLocked(long now) {
        return now > (mLastWriteTime+WRITE_PERIOD);
    }

    public void shutdownLocked() {
        Slog.w(TAG, "Writing process stats before shutdown...");
        writeStateSyncLocked();
        mShuttingDown = true;
    }

    public void writeStateAsyncLocked() {
        writeStateLocked(false);
    }

    public void writeStateSyncLocked() {
        writeStateLocked(true);
    }

    private void writeStateLocked(boolean sync) {
        if (mShuttingDown) {
            return;
        }

        synchronized (mPendingWriteLock) {
            long now = SystemClock.uptimeMillis();
            mPendingWrite = Parcel.obtain();
            mState.mTimePeriodEnd = System.currentTimeMillis();
            mState.writeToParcel(mPendingWrite);
            mLastWriteTime = SystemClock.uptimeMillis();
            Slog.i(TAG, "Prepared write state in " + (SystemClock.uptimeMillis()-now) + "ms");
            if (!sync) {
                BackgroundThread.getHandler().post(new Runnable() {
                    @Override public void run() {
                        commitWriteState();
                    }
                });
                return;
            }
        }

        commitWriteState();
    }

    void commitWriteState() {
        Parcel data;
        synchronized (mPendingWriteLock) {
            data = mPendingWrite;
            if (data == null) {
                return;
            }
            mPendingWrite = null;
            mWriteLock.lock();
        }

        FileOutputStream stream = null;
        try {
            stream = mFile.startWrite();
            stream.write(data.marshall());
            stream.flush();
            mFile.finishWrite(stream);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing process statistics", e);
            mFile.failWrite(stream);
        } finally {
            data.recycle();
            mWriteLock.unlock();
        }
    }

    static private void printScreenLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("             ");
                break;
            case ADJ_SCREEN_OFF:
                pw.print("Screen Off / ");
                break;
            case ADJ_SCREEN_ON:
                pw.print("Screen On  / ");
                break;
            default:
                pw.print("?????????? / ");
                break;
        }
    }

    static private void printScreenLabelCsv(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                break;
            case ADJ_SCREEN_OFF:
                pw.print(ADJ_SCREEN_NAMES_CSV[0]);
                break;
            case ADJ_SCREEN_ON:
                pw.print(ADJ_SCREEN_NAMES_CSV[1]);
                break;
            default:
                pw.print("???");
                break;
        }
    }

    static private void printMemLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("       ");
                break;
            case ADJ_MEM_FACTOR_NORMAL:
                pw.print("Norm / ");
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.print("Mod  / ");
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.print("Low  / ");
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.print("Crit / ");
                break;
            default:
                pw.print("???? / ");
                break;
        }
    }

    static private void printMemLabelCsv(PrintWriter pw, int offset) {
        if (offset >= ADJ_MEM_FACTOR_NORMAL) {
            if (offset <= ADJ_MEM_FACTOR_CRITICAL) {
                pw.print(ADJ_MEM_NAMES_CSV[offset]);
            } else {
                pw.print("???");
            }
        }
    }

    static void dumpSingleTime(PrintWriter pw, String prefix, long[] durations,
            int curState, long curStartTime, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                String running = "";
                if (curState == state) {
                    time += now - curStartTime;
                    running = " (running)";
                }
                if (time != 0) {
                    pw.print(prefix);
                    printScreenLabel(pw, printedScreen != iscreen
                            ? iscreen : STATE_NOTHING);
                    printedScreen = iscreen;
                    printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
                    printedMem = imem;
                    TimeUtils.formatDuration(time, pw); pw.println(running);
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            printScreenLabel(pw, STATE_NOTHING);
            pw.print("TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    static void dumpAdjTimesCheckin(PrintWriter pw, String sep, long[] durations,
            int curState, long curStartTime, long now) {
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                if (curState == state) {
                    time += now - curStartTime;
                }
                if (time != 0) {
                    printAdjTagAndValue(pw, state, time);
                }
            }
        }
    }

    static void dumpServiceTimeCheckin(PrintWriter pw, String label, String packageName,
            int uid, String serviceName, ServiceState svc, int opCount, long[] durations,
            int curState, long curStartTime, long now) {
        if (opCount <= 0) {
            return;
        }
        pw.print(label);
        pw.print(",");
        pw.print(packageName);
        pw.print(",");
        pw.print(uid);
        pw.print(",");
        pw.print(serviceName);
        pw.print(opCount);
        dumpAdjTimesCheckin(pw, ",", durations, curState, curStartTime, now);
        pw.println();
    }

    static long computeProcessTimeLocked(ProcessState proc, int[] screenStates, int[] memStates,
                int[] procStates, long now) {
        long totalTime = 0;
        /*
        for (int i=0; i<proc.mDurationsTableSize; i++) {
            int val = proc.mDurationsTable[i];
            totalTime += proc.mState.getLong(val, 0);
            if ((val&0xff) == proc.mCurState) {
                totalTime += now - proc.mStartTime;
            }
        }
        */
        for (int is=0; is<screenStates.length; is++) {
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    int bucket = ((screenStates[is]+ memStates[im]) * STATE_COUNT)
                            + procStates[ip];
                    totalTime += proc.getDuration(bucket, now);
                }
            }
        }
        proc.mTmpTotalTime = totalTime;
        return totalTime;
    }

    static void dumpProcessState(PrintWriter pw, String prefix, ProcessState proc,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long time = proc.getDuration(bucket, now);
                    String running = "";
                    if (proc.mCurState == bucket) {
                        running = " (running)";
                    }
                    if (time != 0) {
                        pw.print(prefix);
                        if (screenStates.length > 1) {
                            printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
                            printedMem = imem;
                        }
                        pw.print(STATE_NAMES[procStates[ip]]); pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                        totalTime += time;
                    }
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            if (screenStates.length > 1) {
                printScreenLabel(pw, STATE_NOTHING);
            }
            if (memStates.length > 1) {
                printMemLabel(pw, STATE_NOTHING);
            }
            pw.print("TOTAL      : ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    static void dumpProcessPss(PrintWriter pw, String prefix, ProcessState proc, int[] screenStates,
            int[] memStates, int[] procStates) {
        boolean printedHeader = false;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long count = proc.getPssSampleCount(bucket);
                    if (count > 0) {
                        if (!printedHeader) {
                            pw.print(prefix);
                            pw.print("PSS (");
                            pw.print(proc.mPssTableSize);
                            pw.println(" entries):");
                            printedHeader = true;
                        }
                        pw.print(prefix);
                        pw.print("  ");
                        if (screenStates.length > 1) {
                            printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
                            printedMem = imem;
                        }
                        pw.print(STATE_NAMES[procStates[ip]]); pw.print(": ");
                        pw.print(count);
                        pw.print(" samples ");
                        pw.print(proc.getPssMinimum(bucket));
                        pw.print("kB ");
                        pw.print(proc.getPssAverage(bucket));
                        pw.print("kB ");
                        pw.print(proc.getPssMaximum(bucket));
                        pw.println("kB");
                    }
                }
            }
        }
        if (proc.mNumExcessiveWake != 0) {
            pw.print(prefix); pw.print("Killed for excessive wake locks: ");
                    pw.print(proc.mNumExcessiveWake); pw.println(" times");
        }
        if (proc.mNumExcessiveCpu != 0) {
            pw.print(prefix); pw.print("Killed for excessive CPU use: ");
                    pw.print(proc.mNumExcessiveCpu); pw.println(" times");
        }
    }

    static void dumpStateHeadersCsv(PrintWriter pw, String sep, int[] screenStates,
            int[] memStates, int[] procStates) {
        final int NS = screenStates != null ? screenStates.length : 1;
        final int NM = memStates != null ? memStates.length : 1;
        final int NP = procStates != null ? procStates.length : 1;
        for (int is=0; is<NS; is++) {
            for (int im=0; im<NM; im++) {
                for (int ip=0; ip<NP; ip++) {
                    pw.print(sep);
                    boolean printed = false;
                    if (screenStates != null && screenStates.length > 1) {
                        printScreenLabelCsv(pw, screenStates[is]);
                        printed = true;
                    }
                    if (memStates != null && memStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        printMemLabelCsv(pw, memStates[im]);
                        printed = true;
                    }
                    if (procStates != null && procStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        pw.print(STATE_NAMES_CSV[procStates[ip]]);
                    }
                }
            }
        }
    }

    static void dumpProcessStateCsv(PrintWriter pw, ProcessState proc,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now) {
        final int NSS = sepScreenStates ? screenStates.length : 1;
        final int NMS = sepMemStates ? memStates.length : 1;
        final int NPS = sepProcStates ? procStates.length : 1;
        for (int iss=0; iss<NSS; iss++) {
            for (int ims=0; ims<NMS; ims++) {
                for (int ips=0; ips<NPS; ips++) {
                    final int vsscreen = sepScreenStates ? screenStates[iss] : 0;
                    final int vsmem = sepMemStates ? memStates[ims] : 0;
                    final int vsproc = sepProcStates ? procStates[ips] : 0;
                    final int NSA = sepScreenStates ? 1 : screenStates.length;
                    final int NMA = sepMemStates ? 1 : memStates.length;
                    final int NPA = sepProcStates ? 1 : procStates.length;
                    long totalTime = 0;
                    for (int isa=0; isa<NSA; isa++) {
                        for (int ima=0; ima<NMA; ima++) {
                            for (int ipa=0; ipa<NPA; ipa++) {
                                final int vascreen = sepScreenStates ? 0 : screenStates[isa];
                                final int vamem = sepMemStates ? 0 : memStates[ima];
                                final int vaproc = sepProcStates ? 0 : procStates[ipa];
                                final int bucket = ((vsscreen + vascreen + vsmem + vamem)
                                        * STATE_COUNT) + vsproc + vaproc;
                                totalTime += proc.getDuration(bucket, now);
                            }
                        }
                    }
                    pw.print(CSV_SEP);
                    pw.print(totalTime);
                }
            }
        }
    }

    static void dumpProcessList(PrintWriter pw, String prefix, ArrayList<ProcessState> procs,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        String innerPrefix = prefix + "  ";
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print(proc.mName);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.print(" (");
            pw.print(proc.mDurationsTableSize);
            pw.print(" entries)");
            pw.println(":");
            dumpProcessState(pw, innerPrefix, proc, screenStates, memStates, procStates, now);
            if (proc.mPssTableSize > 0) {
                dumpProcessPss(pw, innerPrefix, proc, screenStates, memStates, procStates);
            }
        }
    }

    static void dumpProcessListCsv(PrintWriter pw, ArrayList<ProcessState> procs,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now) {
        pw.print("process");
        pw.print(CSV_SEP);
        pw.print("uid");
        dumpStateHeadersCsv(pw, CSV_SEP, sepScreenStates ? screenStates : null,
                sepMemStates ? memStates : null,
                sepProcStates ? procStates : null);
        pw.println();
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(proc.mName);
            pw.print(CSV_SEP);
            UserHandle.formatUid(pw, proc.mUid);
            dumpProcessStateCsv(pw, proc, sepScreenStates, screenStates,
                    sepMemStates, memStates, sepProcStates, procStates, now);
            pw.println();
        }
    }

    boolean dumpFilteredProcessesCsv(PrintWriter pw, String header,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now, String reqPackage) {
        ArrayList<ProcessState> procs = mState.collectProcessesLocked(screenStates, memStates,
                procStates, now, reqPackage);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println(header);
            }
            dumpProcessListCsv(pw, procs, sepScreenStates, screenStates,
                    sepMemStates, memStates, sepProcStates, procStates, now);
            return true;
        }
        return false;
    }

    static int printArrayEntry(PrintWriter pw, String[] array, int value, int mod) {
        int index = value/mod;
        if (index >= 0 && index < array.length) {
            pw.print(array[index]);
        } else {
            pw.print('?');
        }
        return value - index*mod;
    }

    static void printProcStateTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD*STATE_COUNT);
        state = printArrayEntry(pw, ADJ_MEM_TAGS,  state, STATE_COUNT);
        printArrayEntry(pw, STATE_TAGS,  state, 1);
    }

    static void printAdjTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD);
        printArrayEntry(pw, ADJ_MEM_TAGS, state, 1);
    }

    static void printProcStateTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printProcStateTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    static void printAdjTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printAdjTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    static void dumpAllProcessStateCheckin(PrintWriter pw, ProcessState proc, long now) {
        boolean didCurState = false;
        for (int i=0; i<proc.mDurationsTableSize; i++) {
            int off = proc.mDurationsTable[i];
            int type = (off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
            long time = proc.mState.getLong(off, 0);
            if (proc.mCurState == type) {
                didCurState = true;
                time += now - proc.mStartTime;
            }
            printProcStateTagAndValue(pw, type, time);
        }
        if (!didCurState && proc.mCurState != STATE_NOTHING) {
            printProcStateTagAndValue(pw, proc.mCurState, now - proc.mStartTime);
        }
    }

    static void dumpAllProcessPssCheckin(PrintWriter pw, ProcessState proc) {
        for (int i=0; i<proc.mPssTableSize; i++) {
            int off = proc.mPssTable[i];
            int type = (off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
            long count = proc.mState.getLong(off, PSS_SAMPLE_COUNT);
            long min = proc.mState.getLong(off, PSS_MINIMUM);
            long avg = proc.mState.getLong(off, PSS_AVERAGE);
            long max = proc.mState.getLong(off, PSS_MAXIMUM);
            pw.print(',');
            printProcStateTag(pw, type);
            pw.print(':');
            pw.print(count);
            pw.print(':');
            pw.print(min);
            pw.print(':');
            pw.print(avg);
            pw.print(':');
            pw.print(max);
        }
    }

    static int[] parseStateList(String[] states, int mult, String arg, boolean[] outSep,
            String[] outError) {
        ArrayList<Integer> res = new ArrayList<Integer>();
        int lastPos = 0;
        for (int i=0; i<=arg.length(); i++) {
            char c = i < arg.length() ? arg.charAt(i) : 0;
            if (c != ',' && c != '+' && c != ' ' && c != 0) {
                continue;
            }
            boolean isSep = c == ',';
            if (lastPos == 0) {
                // We now know the type of op.
                outSep[0] = isSep;
            } else if (c != 0 && outSep[0] != isSep) {
                outError[0] = "inconsistent separators (can't mix ',' with '+')";
                return null;
            }
            if (lastPos < (i-1)) {
                String str = arg.substring(lastPos, i);
                for (int j=0; j<states.length; j++) {
                    if (str.equals(states[j])) {
                        res.add(j);
                        str = null;
                        break;
                    }
                }
                if (str != null) {
                    outError[0] = "invalid word \"" + str + "\"";
                    return null;
                }
            }
            lastPos = i + 1;
        }

        int[] finalRes = new int[res.size()];
        for (int i=0; i<res.size(); i++) {
            finalRes[i] = res.get(i) * mult;
        }
        return finalRes;
    }

    static private void dumpHelp(PrintWriter pw) {
        pw.println("Process stats (procstats) dump options:");
        pw.println("    [--checkin|--csv] [csv-screen] [csv-proc] [csv-mem]");
        pw.println("    [--reset] [--write] [-h] [<package.name>]");
        pw.println("  --checkin: format output for a checkin report.");
        pw.println("  --csv: output data suitable for putting in a spreadsheet.");
        pw.println("  --csv-screen: on, off.");
        pw.println("  --csv-mem: norm, mod, low, crit.");
        pw.println("  --csv-proc: pers, top, fore, vis, precept, backup,");
        pw.println("    service, home, prev, cached");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --write: write current in-memory stats to disk.");
        pw.println("  -a: print everything.");
        pw.println("  -h: print this help text.");
        pw.println("  <package.name>: optional name of package to filter output by.");
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();

        boolean isCheckin = false;
        boolean isCsv = false;
        boolean dumpAll = false;
        String reqPackage = null;
        boolean csvSepScreenStats = false;
        int[] csvScreenStats = new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON};
        boolean csvSepMemStats = false;
        int[] csvMemStats = new int[] {ADJ_MEM_FACTOR_CRITICAL};
        boolean csvSepProcStats = true;
        int[] csvProcStats = new int[] {
                STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                STATE_PREVIOUS, STATE_CACHED };
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    isCheckin = true;
                } else if ("--csv".equals(arg)) {
                    isCsv = true;
                } else if ("--csv-screen".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-screen");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvScreenStats = parseStateList(ADJ_SCREEN_NAMES_CSV, ADJ_SCREEN_MOD,
                            args[i], sep, error);
                    if (csvScreenStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepScreenStats = sep[0];
                } else if ("--csv-mem".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-mem");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvMemStats = parseStateList(ADJ_MEM_NAMES_CSV, 1, args[i], sep, error);
                    if (csvMemStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepMemStats = sep[0];
                } else if ("--csv-proc".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-proc");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvProcStats = parseStateList(STATE_NAMES_CSV, 1, args[i], sep, error);
                    if (csvProcStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepProcStats = sep[0];
                } else if ("--reset".equals(arg)) {
                    mState.reset();
                    pw.println("Process stats reset.");
                    return;
                } else if ("--write".equals(arg)) {
                    writeStateSyncLocked();
                    pw.println("Process stats written.");
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    dumpAll = true;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        IPackageManager pm = AppGlobals.getPackageManager();
                        if (pm.getPackageUid(arg, UserHandle.getCallingUserId()) >= 0) {
                            reqPackage = arg;
                        }
                    } catch (RemoteException e) {
                    }
                    if (reqPackage == null) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }

        if (isCsv) {
            pw.print("Processes running summed over");
            if (!csvSepScreenStats) {
                for (int i=0; i<csvScreenStats.length; i++) {
                    pw.print(" ");
                    printScreenLabelCsv(pw, csvScreenStats[i]);
                }
            }
            if (!csvSepMemStats) {
                for (int i=0; i<csvMemStats.length; i++) {
                    pw.print(" ");
                    printMemLabelCsv(pw, csvMemStats[i]);
                }
            }
            if (!csvSepProcStats) {
                for (int i=0; i<csvProcStats.length; i++) {
                    pw.print(" ");
                    pw.print(STATE_NAMES_CSV[csvProcStats[i]]);
                }
            }
            pw.println();
            dumpFilteredProcessesCsv(pw, null,
                    csvSepScreenStats, csvScreenStats, csvSepMemStats, csvMemStats,
                    csvSepProcStats, csvProcStats, now, reqPackage);
            /*
            dumpFilteredProcessesCsv(pw, "Processes running while critical mem:",
                    false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    true, new int[] {ADJ_MEM_FACTOR_CRITICAL},
                    true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                            STATE_PREVIOUS, STATE_CACHED},
                    now, reqPackage);
            dumpFilteredProcessesCsv(pw, "Processes running over all mem:",
                    false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    false, new int[] {ADJ_MEM_FACTOR_CRITICAL, ADJ_MEM_FACTOR_LOW,
                            ADJ_MEM_FACTOR_MODERATE, ADJ_MEM_FACTOR_MODERATE},
                    true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                            STATE_PREVIOUS, STATE_CACHED},
                    now, reqPackage);
            */
            return;
        }

        if (isCheckin) {
            mState.dumpCheckin(pw, reqPackage);
        } else {
            mState.dump(pw, reqPackage, dumpAll);
        }
    }
}