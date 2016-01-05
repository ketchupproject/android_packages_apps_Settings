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
 * limitations under the License.
 */

package com.android.settings.vpn2;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.Credentials;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.settings.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dialog to configure always-on VPN.
 */
public class LockdownConfigFragment extends DialogFragment {
    private List<VpnProfile> mProfiles;
    private List<AppVpnInfo> mApps;
    private List<CharSequence> mTitles;
    private int mCurrentIndex;

    private static final String TAG_LOCKDOWN = "lockdown";
    private static final String LOG_TAG = "LockdownConfigFragment";

    private static class TitleAdapter extends ArrayAdapter<CharSequence> {
        public TitleAdapter(Context context, List<CharSequence> objects) {
            super(context, com.android.internal.R.layout.select_dialog_singlechoice_material,
                    android.R.id.text1, objects);
        }
    }

    public static void show(VpnSettings parent) {
        if (!parent.isAdded()) return;

        final LockdownConfigFragment dialog = new LockdownConfigFragment();
        dialog.show(parent.getFragmentManager(), TAG_LOCKDOWN);
    }

    private static String getStringOrNull(KeyStore keyStore, String key) {
        final byte[] value = keyStore.get(key);
        return value == null ? null : new String(value);
    }

    private void initProfiles(KeyStore keyStore, Resources res) {
        final ConnectivityManager cm = ConnectivityManager.from(getActivity());
        final String lockdownKey = getStringOrNull(keyStore, Credentials.LOCKDOWN_VPN);
        final String alwaysOnPackage =  cm.getAlwaysOnVpnPackageForUser(UserHandle.myUserId());

        // Legacy VPN has a separate always-on mechanism which takes over the whole device, so
        // this option is restricted to the primary user only.
        if (UserManager.get(getContext()).isPrimaryUser()) {
            mProfiles = VpnSettings.loadVpnProfiles(keyStore, VpnProfile.TYPE_PPTP);
        } else {
            mProfiles = Collections.<VpnProfile>emptyList();
        }
        mApps = VpnSettings.getVpnApps(getActivity(), /* includeProfiles */ false);

        mTitles = new ArrayList<>(1 + mProfiles.size() + mApps.size());
        mTitles.add(res.getText(R.string.vpn_lockdown_none));
        mCurrentIndex = 0;

        // Add true lockdown VPNs to the list first.
        for (VpnProfile profile : mProfiles) {
            if (TextUtils.equals(profile.key, lockdownKey)) {
                mCurrentIndex = mTitles.size();
            }
            mTitles.add(profile.name);
        }

        // Add third-party app VPNs (VpnService) for the current profile to set as always-on.
        for (AppVpnInfo app : mApps) {
            try {
                String appName = VpnConfig.getVpnLabel(getContext(), app.packageName).toString();
                if (TextUtils.equals(app.packageName, alwaysOnPackage)) {
                    mCurrentIndex = mTitles.size();
                }
                mTitles.add(appName);
            } catch (PackageManager.NameNotFoundException pkgNotFound) {
                Log.w(LOG_TAG, "VPN package not found: '" + app.packageName + "'", pkgNotFound);
            }
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final KeyStore keyStore = KeyStore.getInstance();

        initProfiles(keyStore, context.getResources());

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        final LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());

        builder.setTitle(R.string.vpn_menu_lockdown);

        final View view = dialogInflater.inflate(R.layout.vpn_lockdown_editor, null, false);
        final ListView listView = (ListView) view.findViewById(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setAdapter(new TitleAdapter(context, mTitles));
        listView.setItemChecked(mCurrentIndex, true);
        builder.setView(view);

        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final int newIndex = listView.getCheckedItemPosition();
                if (mCurrentIndex == newIndex) return;

                final ConnectivityManager conn = ConnectivityManager.from(getActivity());

                if (newIndex == 0) {
                    keyStore.delete(Credentials.LOCKDOWN_VPN);
                    conn.setAlwaysOnVpnPackageForUser(UserHandle.myUserId(), null);
                } else if (newIndex <= mProfiles.size()) {
                    final VpnProfile profile = mProfiles.get(newIndex - 1);
                    if (!profile.isValidLockdownProfile()) {
                        Toast.makeText(context, R.string.vpn_lockdown_config_error,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    conn.setAlwaysOnVpnPackageForUser(UserHandle.myUserId(), null);
                    keyStore.put(Credentials.LOCKDOWN_VPN, profile.key.getBytes(),
                            KeyStore.UID_SELF, /* flags */ 0);
                } else {
                    keyStore.delete(Credentials.LOCKDOWN_VPN);

                    final AppVpnInfo appVpn = mApps.get(newIndex - 1 - mProfiles.size());
                    conn.setAlwaysOnVpnPackageForUser(appVpn.userId, appVpn.packageName);
                }

                // kick profiles since we changed them
                conn.updateLockdownVpn();
            }
        });

        return builder.create();
    }
}

