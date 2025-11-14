/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.fanstrace;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public class MainFragment extends PreferenceFragmentCompat {
    public static final String ACTION_REFRESH_TAGS = "com.android.fanstrace.REFRESH_TAGS";

    private SwitchPreferenceCompat mTracingOn;
    private SwitchPreferenceCompat mDfxTracingOn;
    private MultiSelectListPreference mTags;
    private AlertDialog mAlertDialog;
    private SharedPreferences mPrefs;
    private boolean mRefreshing;
    private BroadcastReceiver mRefreshReceiver;

    private final SharedPreferences.OnSharedPreferenceChangeListener mSharedPreferenceChangeListener =
            (sharedPreferences, key) -> refreshUi();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.main, rootKey);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mTracingOn = findPreference(getString(R.string.pref_key_tracing_on));
        mDfxTracingOn = findPreference(getString(R.string.pref_key_dfx_tracing_on));
        mTags = findPreference(getString(R.string.pref_key_tags));

        initializePreferences();
    }

    private void initializePreferences() {
        Context context = getContext();

        // Tracing On 开关
        mTracingOn.setOnPreferenceClickListener(preference -> {
            int enable = ((MainActivity) requireActivity()).getSwitch();
            if (enable == 1) {
                Receiver.updateTracing(context);
            } else {
                // Restore original state
                mTracingOn.setChecked(!mTracingOn.isChecked());
                Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // DFX Tracing On 开关
        mDfxTracingOn.setOnPreferenceClickListener(preference -> {
            int enable = ((MainActivity) requireActivity()).getSwitch();
            if (enable == 1) {
                Receiver.updateTracing(context);
            } else {
                // Restore original state
                mDfxTracingOn.setChecked(!mDfxTracingOn.isChecked());
                Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // 标签设置
        mTags.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!mRefreshing) {
                Set<String> set = (Set<String>) newValue;
                TreeMap<String, String> available = TraceUtils.listCategories();
                ArrayList<String> clean = new ArrayList<>(set.size());

                for (String s : set) {
                    if (available.containsKey(s)) {
                        clean.add(s);
                    }
                }
                set.clear();
                set.addAll(clean);
            }
            return true;
        });

        // 恢复默认标签
        findPreference("restore_default_tags").setOnPreferenceClickListener(preference -> {
            refreshUi(true);
            Toast.makeText(context,
                    context.getString(R.string.default_categories_restored),
                    Toast.LENGTH_SHORT).show();
            return true;
        });

        // 清除保存的跟踪数据
        findPreference("clear_saved_traces").setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.clear_saved_traces_question)
                    .setMessage(R.string.all_traces_will_be_deleted)
                    .setPositiveButton(R.string.clear,
                            (dialog, which) -> TraceUtils.clearSavedTraces())
                    .setNegativeButton(android.R.string.no,
                            (dialog, which) -> dialog.dismiss())
                    .create()
                    .show();
            return true;
        });

        refreshUi();

        // Set enable state based on permission immediately after initialization
        int enable = ((MainActivity) requireActivity()).getSwitch();
        mTracingOn.setEnabled(enable == 1);
        mDfxTracingOn.setEnabled(enable == 1);

        mRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshUi();
            }
        };
    }

    @Override
    public void onStart() {
        super.onStart();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences())
                .registerOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);
        requireActivity().registerReceiver(
                mRefreshReceiver,
                new IntentFilter(ACTION_REFRESH_TAGS),
                Context.RECEIVER_NOT_EXPORTED);

        // Only update tracing if user has permission
        int enable = ((MainActivity) requireActivity()).getSwitch();
        if (enable == 1) {
            Receiver.updateTracing(getContext());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh enable state in case it changed
        int enable = ((MainActivity) requireActivity()).getSwitch();
        mTracingOn.setEnabled(enable == 1);
        mDfxTracingOn.setEnabled(enable == 1);
    }

    @Override
    public void onStop() {
        super.onStop();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences())
                .unregisterOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);
        requireActivity().unregisterReceiver(mRefreshReceiver);

        if (mAlertDialog != null) {
            mAlertDialog.cancel();
            mAlertDialog = null;
        }
    }


    private void refreshUi() {
        refreshUi(/* restoreDefaultTags =*/false);
    }

    /*
     * Refresh the preferences UI to make sure it reflects the current state of the preferences and
     * system.
     */
    private void refreshUi(boolean restoreDefaultTags) {
        Context context = getContext();

        // Make sure the Record Trace toggle matches the preference value.
        mTracingOn.setChecked(mTracingOn.getPreferenceManager().getSharedPreferences().getBoolean(
                mTracingOn.getKey(), false));

        // Make sure the Record DFX Trace toggle matches the preference value.
        mDfxTracingOn.setChecked(mDfxTracingOn.getPreferenceManager().getSharedPreferences().getBoolean(
                mDfxTracingOn.getKey(), false));

        // Update category list to match the categories available on the system.
        Set<Entry<String, String>> availableTags = TraceUtils.listCategories().entrySet();
        ArrayList<String> entries = new ArrayList<String>(availableTags.size());
        ArrayList<String> values = new ArrayList<String>(availableTags.size());
        for (Entry<String, String> entry : availableTags) {
            entries.add(entry.getKey() + ": " + entry.getValue());
            values.add(entry.getKey());
        }

        mRefreshing = true;
        try {
            mTags.setEntries(entries.toArray(new String[0]));
            mTags.setEntryValues(values.toArray(new String[0]));
            if (restoreDefaultTags || !mPrefs.contains(context.getString(R.string.pref_key_tags))) {
                mTags.setValues(Receiver.getDefaultTagList());
            }
        } finally {
            mRefreshing = false;
        }

        // Update subtitles on this screen.
        Set<String> categories = mTags.getValues();
        mTags.setSummary(Receiver.getDefaultTagList().equals(categories)
                ? context.getString(R.string.default_categories)
                : context.getResources().getQuantityString(
                R.plurals.num_categories_selected, categories.size(),
                categories.size()));

        ListPreference bufferSize =
                (ListPreference) findPreference(context.getString(R.string.pref_key_buffer_size));
        bufferSize.setSummary(bufferSize.getEntry().toString());

        ListPreference perfCpuMode =
                (ListPreference) findPreference(context.getString(R.string.pref_key_perf_cpu_mode));
        perfCpuMode.setSummary(perfCpuMode.getEntry());
    }
}
